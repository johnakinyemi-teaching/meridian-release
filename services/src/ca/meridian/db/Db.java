package ca.meridian.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Access to the reading store.
 *
 * From 2014 this class started the sqlite3 command line program for every
 * query and read back the JSON it printed, because the collector host
 * never had a driver installed. That was meant to be temporary (MRD-77).
 * It now opens the store in this process, through the SQLite JDBC driver
 * kept in services/lib and put on the class path by services/build.sh.
 *
 * What callers get is deliberately the same as before, because the oldest
 * routes send this text to the consoles as it is and the newer routes
 * parse it:
 *
 *   - queryJson returns the rows as "sqlite3 -json" printed them: an array
 *     with one object per row and one row per line, [] for no rows, and
 *     each REAL in SQLite's own spelling (5.951 is 5.95099999999999962);
 *   - a failure is an IOException that says "sqlite3 exited N". N is
 *     SQLite's result code, which was the program's exit status: 1 for an
 *     error in the statement or a store that cannot be opened, 5 for a
 *     locked store, 26 for a file that is not a database. The routes send
 *     that as their 500 body, and DbClient's retry recognises a lock by it.
 *
 * Each call opens the store, runs one statement and closes it again, as
 * each run of the program did, so nothing is held between queries.
 *
 * A. Okonkwo, 2014-09
 */
public final class Db {

    private static final Object[] NO_PARAMS = new Object[0];

    // The program used "%!.20g" for a REAL. Asking SQLite to do the same
    // keeps every digit as it was, which Java's own formatting would not.
    private static final String SPELL_REAL = "SELECT printf('%!.20g', ?1)";

    private final String dbPath;

    public Db(String dbPath) {
        this.dbPath = dbPath;
    }

    /** The store file this talks to. Used to find files that live beside it. */
    public String path() {
        return dbPath;
    }

    /** Runs a query and returns its result as a JSON array string. */
    public String queryJson(String sql) throws IOException {
        return queryJson(sql, NO_PARAMS);
    }

    /**
     * Runs a query whose values are bound, not written into the SQL text,
     * and returns its result as a JSON array string. The SQL names them
     * ?1, ?2, ... in the order given; a value is a String or an integer.
     *
     * The statement is prepared with its placeholders and the values are
     * handed to SQLite separately, so no character of a value is ever read
     * as SQL (MRD-166).
     */
    public String queryJson(String sql, Object... params) throws IOException {
        for (Object value : params) {
            if (!(value instanceof String || value instanceof Integer || value instanceof Long)) {
                throw new IllegalArgumentException("cannot bind "
                        + (value == null ? "null" : value.getClass().getName()));
            }
        }
        try {
            try (Connection store = open();
                 PreparedStatement statement = store.prepareStatement(sql)) {
                int placeholders = statement.getParameterMetaData().getParameterCount();
                if (placeholders != params.length) {
                    throw new IllegalArgumentException(params.length + " value(s) for "
                            + placeholders + " placeholder(s)");
                }
                for (int i = 0; i < params.length; i++) {
                    if (params[i] instanceof String) {
                        statement.setString(i + 1, (String) params[i]);
                    } else {
                        statement.setLong(i + 1, ((Number) params[i]).longValue());
                    }
                }
                if (!statement.execute()) {
                    return "[]";
                }
                try (ResultSet rows = statement.getResultSet()) {
                    return asTheProgramPrintedIt(rows, store);
                }
            } catch (SQLException e) {
                throw new IOException("sqlite3 exited " + resultCode(e));
            }
        } catch (LinkageError driverNotLoaded) {
            // The driver could not load SQLite itself, for example because the
            // temporary directory it unpacks it into is unwritable or noexec.
            DbLog.warn(DbLog.DRIVER_NOT_LOADED, driverNotLoaded);
            throw new IOException("sqlite3 exited 1");
        }
    }

    /** Opens the store. A failure here is reported as code 1, as the program's was. */
    private Connection open() throws SQLException {
        Properties settings = new Properties();
        // The program gave up on a locked store at once. The driver would
        // otherwise wait three seconds before saying so.
        settings.setProperty("busy_timeout", "0");
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + dbPath, settings);
        } catch (SQLException cannotOpen) {
            // "unable to open database" was exit status 1 whatever the reason: a
            // missing directory, a directory in the file's place, an unreadable
            // file. SQLite's own code for it (14) never reached a caller.
            DbLog.warnIfTheDriverFailed(cannotOpen);
            throw new SQLException(cannotOpen.getMessage(), cannotOpen.getSQLState(), 1, cannotOpen);
        }
    }

    /**
     * SQLite's result code for a failed statement, as the program's exit
     * status reported it: the low eight bits, and never 0.
     */
    private static int resultCode(SQLException e) {
        int code = e.getErrorCode() & 0xFF;
        return code == 0 ? 1 : code;
    }

    private static String asTheProgramPrintedIt(ResultSet rows, Connection store) throws SQLException {
        ResultSetMetaData columns = rows.getMetaData();
        int n = columns.getColumnCount();
        StringBuilder out = new StringBuilder();
        boolean any = false;
        try (PreparedStatement spellReal = store.prepareStatement(SPELL_REAL)) {
            while (rows.next()) {
                out.append(any ? ",\n{" : "[{");
                any = true;
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        out.append(',');
                    }
                    quote(out, columns.getColumnLabel(i));
                    out.append(':');
                    Object value = rows.getObject(i);
                    if (value == null) {
                        out.append("null");
                    } else if (value instanceof Double || value instanceof Float) {
                        real(out, ((Number) value).doubleValue(), spellReal);
                    } else if (value instanceof Number) {
                        out.append(value);
                    } else if (value instanceof byte[]) {
                        quote(out, new String((byte[]) value, StandardCharsets.UTF_8));
                    } else {
                        quote(out, value.toString());
                    }
                }
                out.append('}');
            }
        }
        return any ? out.append(']').toString() : "[]";
    }

    private static void real(StringBuilder out, double r, PreparedStatement spellReal) throws SQLException {
        if (r == Double.POSITIVE_INFINITY) {
            out.append("9.0e+999");
        } else if (r == Double.NEGATIVE_INFINITY) {
            out.append("-9.0e+999");
        } else if (Math.abs(r) < 9.0e18 && r == (double) (long) r) {
            // A whole number is written with one decimal place: 174.0.
            // Only where a long holds it exactly; beyond that SQLite spells it.
            out.append((long) r).append(".0");
        } else {
            spellReal.setDouble(1, r);
            try (ResultSet spelled = spellReal.executeQuery()) {
                spelled.next();
                out.append(spelled.getString(1));
            }
        }
    }

    /**
     * A JSON string the way the program wrote one: a backslash before a
     * quote or a backslash, the short escapes for backspace, form feed,
     * newline, carriage return and tab, and everything from a space
     * upward as it is. Any other control character is written as a
     * \\u00XX escape; the program left the backslash off those, which is
     * not valid JSON and is not copied here.
     */
    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c == '\b') {
                out.append("\\b");
            } else if (c == '\f') {
                out.append("\\f");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }
}

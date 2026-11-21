package ca.meridian.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Access to the reading store.
 *
 * The collector host never had the SQLite JDBC driver installed and
 * the deployment process for adding one was never agreed, so this
 * class shells out to the sqlite3 command line and reads its JSON
 * output. It was meant to be temporary. See MRD-77.
 *
 * A. Okonkwo, 2014-09
 */
public final class Db {

    private static final Object[] NO_PARAMS = new Object[0];

    // sqlite3 exits 1 for a dot-command that fails, whatever the reason.
    // Reading the schema first makes a locked or unreadable store fail on
    // a statement instead, with the exit code a plain query has always
    // given (5 for a lock, 26 for a file that is not a database), so the
    // callers that bind values keep the 500 body they always sent. It
    // prints nothing.
    private static final String OPEN_FIRST = "SELECT 1 FROM sqlite_master LIMIT 0";

    private final String dbPath;

    public Db(String dbPath) {
        this.dbPath = dbPath;
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
     * There is still no driver here (MRD-77), so the binding is done by
     * the sqlite3 tool itself: one ".parameter set" per value, ahead of
     * the statement, which sqlite3 then prepares and binds. A String is
     * handed over as the hex of its bytes, so no character of it is ever
     * read as SQL; it reaches the statement only as a bound value.
     *
     * With no values this is the plain call, argument for argument.
     */
    public String queryJson(String sql, Object... params) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("sqlite3");
        command.add("-json");
        command.add(dbPath);
        if (params.length > 0) {
            command.add(OPEN_FIRST);
            for (int i = 0; i < params.length; i++) {
                command.add(".parameter set ?" + (i + 1) + " " + literal(params[i]));
            }
        }
        command.add(sql);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }

        int rc;
        try {
            rc = p.waitFor();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running sqlite3", ie);
        }
        if (rc != 0) {
            throw new IOException("sqlite3 exited " + rc);
        }

        String s = out.toString().trim();
        return s.isEmpty() ? "[]" : s;
    }

    /** The value as the last word of a ".parameter set" line. */
    private static String literal(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof String) {
            StringBuilder hex = new StringBuilder();
            for (byte b : ((String) value).getBytes(StandardCharsets.UTF_8)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 0x10));
                hex.append(Character.forDigit(b & 0xF, 0x10));
            }
            // x'..' is the bytes as a blob; ||'' makes it the TEXT those
            // bytes spell. No spaces and no quotes, so there is nothing for
            // a shell or for ProcessBuilder's Windows argv quoting to split
            // on: only the hex digits of the value ever reach sqlite3.
            return "x'" + hex + "'||''";
        }
        throw new IllegalArgumentException("cannot bind "
                + (value == null ? "null" : value.getClass().getName()));
    }

    public List<String> queryColumn(String sql) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("sqlite3", dbPath, sql);
        Process p = pb.start();
        List<String> vals = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                vals.add(line);
            }
        }
        try {
            p.waitFor();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return vals;
    }
}

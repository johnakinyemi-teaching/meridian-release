package ca.meridian.test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes the REAL numbers in an expected answer the way the service writes
 * them on this machine.
 *
 * The service sends a REAL in SQLite's own spelling, and that spelling is
 * not the same everywhere. 5.951 is 5.95099999999999962 from the SQLite in
 * services/lib on an x86-64 machine. The sqlite3 program spells it
 * 5.950999999999999624 from version 3.50 and 5.951 from 3.53, and the
 * digits past the seventeenth can also depend on the processor.
 *
 * The recordings under expected/ were made in the devcontainer on x86-64,
 * and some checks compare the service with whatever sqlite3 program is
 * installed. So that neither kind fails on another machine for the
 * spelling alone, each expected REAL is read as a number and written back
 * by the SQLite the service itself uses, with the format the sqlite3
 * program has always used. Where the recordings were made that changes
 * nothing. The value always comes from the recording or the program, never
 * from the service.
 *
 * Two things this cannot notice are checked on their own in
 * StoreAccessTest: that the SQLite in use is still 3.46.1, and that on
 * x86-64 the oracle value still has the spelling it has always had.
 */
final class Reals {

    private static final Map<String, String> SPELLED = new HashMap<>();
    private static Connection memory;

    private Reals() {
    }

    static String asTheServiceSpellsThem(String json) throws IOException {
        StringBuilder out = new StringBuilder(json.length());
        int n = json.length();
        int i = 0;
        while (i < n) {
            char ch = json.charAt(i);
            if (ch == '"') {
                // A string: copy it whole, so digits inside it are left alone.
                int end = i + 1;
                while (end < n && json.charAt(end) != '"') {
                    end += json.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, n);
                out.append(json, i, end);
                i = end;
            } else if (ch == '-' || (ch >= '0' && ch <= '9')) {
                int end = i + 1;
                while (end < n && "0123456789+-.eE".indexOf(json.charAt(end)) >= 0) {
                    end++;
                }
                String number = json.substring(i, end);
                out.append(isReal(number) ? spelled(number) : number);
                i = end;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isReal(String number) {
        return number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0;
    }

    private static synchronized String spelled(String number) throws IOException {
        String known = SPELLED.get(number);
        if (known != null) {
            return known;
        }
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException notANumber) {
            return number;
        }
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            // 9.0e+999 is how an infinity is written. It has one spelling.
            return number;
        }
        try {
            if (memory == null) {
                memory = DriverManager.getConnection("jdbc:sqlite::memory:");
            }
            try (PreparedStatement spell = memory.prepareStatement("SELECT printf('%!.20g', ?1)")) {
                spell.setDouble(1, value);
                try (ResultSet row = spell.executeQuery()) {
                    row.next();
                    String written = row.getString(1);
                    SPELLED.put(number, written);
                    return written;
                }
            }
        } catch (SQLException e) {
            throw new IOException("could not spell " + number + ": " + e.getMessage(), e);
        }
    }
}

package ca.meridian.test;

import ca.meridian.db.Db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Db.queryJson with bound values, called directly.
 *
 * The HTTP tests can only send what a request line can carry. These reach
 * the binding itself with the text a URL cannot: newlines, double quotes,
 * backslashes, text outside ASCII, and strings that look like SQL or like
 * the hex the value travels as. Each value is read back by SQLite as
 * hex(?1), so the check does not depend on how sqlite3 escapes JSON.
 */
final class DbBindTest {

    private DbBindTest() {
    }

    static void run(Checks c, Path testDir) throws IOException {
        Store store = Store.fromFixture(testDir);
        Db db = new Db(store.path());

        c.section("Db.queryJson with bound values");

        c.same("the plain call, with no values, answers as it always did",
                "[{\"stnid\":\"GUELPH\",\"year\":2024},\n{\"stnid\":\"GUELPH\",\"year\":2025},\n"
                        + "{\"stnid\":\"KITCHNER\",\"year\":2025},\n{\"stnid\":\"MILTON\",\"year\":2025}]",
                db.queryJson("SELECT stnid, year FROM forecast ORDER BY stnid, year"));

        c.same("a String arrives as text, an int as an integer",
                "[{\"t1\":\"text\",\"v1\":\"GUELPH\",\"t2\":\"integer\",\"v2\":2025}]",
                db.queryJson("SELECT typeof(?1) AS t1, ?1 AS v1, typeof(?2) AS t2, ?2 AS v2",
                        "GUELPH", 2025));

        c.same("a bound station matches the rows the quoted literal matched",
                db.queryJson("SELECT year FROM forecast WHERE stnid = 'GUELPH' ORDER BY year"),
                db.queryJson("SELECT year FROM forecast WHERE stnid = ?1 ORDER BY year", "GUELPH"));

        c.same("integers at the edges of int and long",
                "[{\"a\":-1,\"b\":-2147483648,\"c\":2147483647,\"d\":9223372036854775807,\"t\":\"integer\"}]",
                db.queryJson("SELECT ?1 AS a, ?2 AS b, ?3 AS c, ?4 AS d, typeof(?4) AS t",
                        -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));

        c.same("eleven values: ?10 and ?11 are not read as ?1",
                "[{\"v\":\"abcdefghijk\"}]",
                db.queryJson("SELECT ?1||?2||?3||?4||?5||?6||?7||?8||?9||?10||?11 AS v",
                        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));

        c.section("Db.queryJson: the text SQLite receives is the text that was bound");
        String[] awkward = {
            "",
            "'",
            "''",
            "\"",
            "\\",
            "a'b\"c\\d",
            "line one\nline two",
            "carriage\rreturn",
            "tab\there",
            " leading and trailing ",
            "x'); DROP TABLE forecast;--",
            "'; DELETE FROM forecast; --",
            "CAST(x'00' AS TEXT)",
            "4755454C5048",
            "NULL",
            "?1",
            "%27",
            "\u00e9t\u00e9 \u65e5\u672c\u8a9e \ud83d\ude00",
        };
        for (String text : awkward) {
            String expected = "[{\"t\":\"text\",\"h\":\"" + hexUpper(text) + "\",\"n\":"
                    + text.codePointCount(0, text.length()) + "}]";
            c.same("bound: " + show(text), expected,
                    db.queryJson("SELECT typeof(?1) AS t, hex(?1) AS h, length(?1) AS n", text));
        }
        StringBuilder everyChar = new StringBuilder();
        for (char ch = 1; ch <= 0xFF; ch++) {
            everyChar.append(ch);
        }
        String all = everyChar.toString();
        c.same("bound: every character from U+0001 to U+00FF in one value",
                "[{\"t\":\"text\",\"h\":\"" + hexUpper(all) + "\",\"n\":255}]",
                db.queryJson("SELECT typeof(?1) AS t, hex(?1) AS h, length(?1) AS n", all));

        c.same("bound: a value 60,000 characters long arrives whole",
                "[{\"t\":\"text\",\"n\":60000,\"all_a\":1}]",
                db.queryJson("SELECT typeof(?1) AS t, length(?1) AS n, replace(?1, 'A', '') = '' AS all_a",
                        "A".repeat(60_000)));

        c.same("none of those values was run as a statement", 4, store.count("forecast"));

        c.section("Db.queryJson: what it refuses, and how failures surface");
        c.same("null is refused before sqlite3 is started", "cannot bind null",
                refusal(db, new Object[] { null }));
        c.same("a double is refused", "cannot bind java.lang.Double",
                refusal(db, new Object[] { 1.5 }));
        c.same("a failing statement is still reported by its exit code",
                "sqlite3 exited 1", failure(db, "SELECT nope FROM forecast WHERE stnid = ?1", "GUELPH"));
    }

    private static String refusal(Db db, Object[] params) throws IOException {
        try {
            db.queryJson("SELECT ?1 AS v", params);
            return "accepted";
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    private static String failure(Db db, String sql, Object... params) {
        try {
            db.queryJson(sql, params);
            return "no failure";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static String hexUpper(String s) {
        StringBuilder hex = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /** The value made printable for the check's name. */
    private static String show(String s) {
        if (s.isEmpty()) {
            return "(empty string)";
        }
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

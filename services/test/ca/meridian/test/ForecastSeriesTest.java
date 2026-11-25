package ca.meridian.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Characterization tests for GET /api/forecast and GET /api/series, the two
 * routes MRD-166 is about.
 *
 * Each check sends one request to a running ApiServer and compares the
 * whole answer (status, content type, CORS header, body) with what the
 * service gave before any change. Bodies are compared byte for byte:
 * these two routes send the store's JSON as it is, in the form the sqlite3
 * program printed it, so the text is the contract (the portal depends on
 * "their exact JSON", ApiServer.java).
 *
 * The quote-in-station section is MRD-166 itself: requests that used to be
 * run as SQL and now only look a station up by that text.
 */
final class ForecastSeriesTest {

    private static final String ALL_FORECASTS = "forecast-all.json";

    private ForecastSeriesTest() {
    }

    static void run(Checks c, Path testDir) throws IOException {
        Store store = Store.fromFixture(testDir);
        try (Http api = Http.serve(store)) {
            forecast(c, api);
            series(c, api);
            neighbours(c, api);
            quoteInStationIsData(c, api, store);
        }
        brokenStores(c, testDir);
    }

    private static void forecast(Checks c, Http api) throws IOException {
        c.section("/api/forecast");
        c.passthrough("no station: every run, by station then year",
                api.get("/api/forecast"), ALL_FORECASTS);
        c.passthrough("station=GUELPH: its two runs, by year",
                api.get("/api/forecast?station=GUELPH"), "forecast-guelph.json");
        c.passthrough("station=KITCHNER: its one run",
                api.get("/api/forecast?station=KITCHNER"), "forecast-kitchner.json");
        c.passthrough("station=MILTON: quote, comma and backslash in the model name",
                api.get("/api/forecast?station=MILTON"), "forecast-milton.json");

        c.section("/api/forecast: ids that match no station answer 200 []");
        String[] noMatch = {
            "NOWHERE",      // well formed, unknown
            "guelph",       // the old routes do not fold case
            "AB",           // shorter than any station id
            "GU-ELPH",      // punctuation
            "TOOLONGID",    // longer than any station id
            "",             // station= with nothing after it
            "GU%27ELPH",    // an encoded quote stays as the text %27: never decoded
            "%20GUELPH",    // likewise an encoded space
            "GUELPH+",      // a plus is not turned into a space
        };
        for (String id : noMatch) {
            c.json("station=" + id, api.get("/api/forecast?station=" + id), "[]");
        }
        c.json("a station 60,000 characters long",
                api.get("/api/forecast?station=" + "A".repeat(60_000)), "[]");

        c.section("/api/forecast: how the old query parser reads odd query strings");
        c.passthrough("empty query string: no filter",
                api.get("/api/forecast?"), ALL_FORECASTS);
        c.passthrough("a key with no '=' is ignored: no filter",
                api.get("/api/forecast?station"), ALL_FORECASTS);
        c.passthrough("an empty key is ignored: no filter",
                api.get("/api/forecast?=GUELPH"), ALL_FORECASTS);
        c.passthrough("another parameter only: no filter",
                api.get("/api/forecast?year=2025"), ALL_FORECASTS);
        c.passthrough("station given twice: the last one wins",
                api.get("/api/forecast?station=GUELPH&station=KITCHNER"), "forecast-kitchner.json");
        c.passthrough("an extra parameter does not disturb the station filter",
                api.get("/api/forecast?station=GUELPH&year=2024"), "forecast-guelph.json");

        c.section("/api/forecast: wired by hand, so no method or path checks");
        c.passthrough("POST is answered like GET",
                api.send("POST", "/api/forecast?station=GUELPH"), "forecast-guelph.json");
        c.passthrough("OPTIONS is answered like GET",
                api.send("OPTIONS", "/api/forecast?station=GUELPH"), "forecast-guelph.json");
        c.passthrough("a path that only starts with the route name reaches it",
                api.get("/api/forecastX?station=GUELPH"), "forecast-guelph.json");
    }

    private static void series(Checks c, Http api) throws IOException {
        c.section("/api/series");
        c.passthrough("GUELPH 2025: three days, by day of year",
                api.get("/api/series?station=GUELPH&year=2025"), "series-guelph-2025.json");
        c.passthrough("GUELPH 2024: the year filter separates the two years",
                api.get("/api/series?station=GUELPH&year=2024"), "series-guelph-2024.json");
        c.passthrough("MILTON 2025: NULL values come back as null",
                api.get("/api/series?station=MILTON&year=2025"), "series-milton-2025.json");
        c.passthrough("year=+2025 parses as 2025",
                api.get("/api/series?station=GUELPH&year=+2025"), "series-guelph-2025.json");
        c.passthrough("parameters in the other order",
                api.get("/api/series?year=2025&station=GUELPH"), "series-guelph-2025.json");
        c.passthrough("POST is answered like GET",
                api.send("POST", "/api/series?station=GUELPH&year=2025"), "series-guelph-2025.json");

        c.section("/api/series: nothing matches, 200 []");
        c.json("unknown station", api.get("/api/series?station=NOWHERE&year=2025"), "[]");
        c.json("lower case station", api.get("/api/series?station=guelph&year=2025"), "[]");
        c.json("empty station", api.get("/api/series?station=&year=2025"), "[]");
        c.json("encoded quote in the station", api.get("/api/series?station=GU%27ELPH&year=2025"), "[]");
        c.json("a year with no data", api.get("/api/series?station=GUELPH&year=1800"), "[]");
        c.json("a negative year", api.get("/api/series?station=GUELPH&year=-1"), "[]");
        c.json("the largest int", api.get("/api/series?station=GUELPH&year=2147483647"), "[]");

        c.section("/api/series: the two 400 messages");
        String required = "station and year are required";
        c.error("no parameters", api.get("/api/series"), 400, required);
        c.error("station only", api.get("/api/series?station=GUELPH"), 400, required);
        c.error("year only", api.get("/api/series?year=2025"), 400, required);
        String notInt = "year must be an integer";
        c.error("year=abc", api.get("/api/series?station=GUELPH&year=abc"), 400, notInt);
        c.error("year= with nothing after it", api.get("/api/series?station=GUELPH&year="), 400, notInt);
        c.error("year=2025.0", api.get("/api/series?station=GUELPH&year=2025.0"), 400, notInt);
        c.error("year with an encoded space", api.get("/api/series?station=GUELPH&year=%202025"), 400, notInt);
        c.error("year past the int range", api.get("/api/series?station=GUELPH&year=2147483648"), 400, notInt);
        c.error("a quote in the year never reaches SQL",
                api.get("/api/series?station=GUELPH&year=2025'OR'1'='1"), 400, notInt);
    }

    /**
     * Routes that share Db with the two under test but are not being
     * changed. They are here so a change to Db that leaks into them shows.
     */
    private static void neighbours(Checks c, Http api) throws IOException {
        c.section("neighbouring routes on the same Db");
        c.json("/api/health", api.get("/api/health"), "{\"status\":\"ok\",\"version\":\"4.2.1\"}");
        c.recorded("/api/stations", api.get("/api/stations"), "stations.json");
        c.recorded("/api/summary?station=GUELPH (Router, DbClient, QueryBuilder)",
                api.get("/api/summary?station=GUELPH"), "summary-guelph.json");
        c.error("/api/summary rejects a quoted station before any SQL",
                api.get("/api/summary?station=GU'ELPH"), 400, "not a station id: GU'ELPH");
    }

    /**
     * MRD-166. A raw single quote in the station used to end the SQL string
     * literal the handler pasted it into, and whatever followed was run as
     * SQL: these same requests answered 500 sqlite3 exited 1, returned every
     * station's rows, returned rows of another table, read and wrote files
     * on the server, and deleted every row of a table. The station is now a
     * bound value, so each of them simply asks for a station with that odd
     * id.
     */
    private static void quoteInStationIsData(Checks c, Http api, Store store) throws IOException {
        c.section("MRD-166: a quote in the station is data, not SQL");

        c.json("forecast, unmatched quote GU'ELPH: no such station",
                api.get("/api/forecast?station=GU'ELPH"), "[]");
        c.json("series, unmatched quote GU'ELPH: no such station",
                api.get("/api/series?station=GU'ELPH&year=2025"), "[]");

        c.json("forecast, X'OR'1'='1: no other station's runs come back",
                api.get("/api/forecast?station=X'OR'1'='1"), "[]");
        c.json("series, X'OR'1'='1: no other station's days come back",
                api.get("/api/series?station=X'OR'1'='1&year=2025"), "[]");

        c.json("forecast, UNION SELECT: the reading table is not read",
                api.get("/api/forecast?station=X'UNION/**/SELECT/**/stnid,year,tmax,doy,stnid,stnid"
                        + "/**/FROM/**/reading--"),
                "[]");

        // The sqlite3 tool has readfile() and writefile(), so the same quote
        // used to read and write files on the server as the service's user.
        Path secret = Paths.get(store.path()).resolveSibling("secret.txt");
        Files.write(secret, "meridian-test-secret".getBytes(StandardCharsets.UTF_8));
        c.json("forecast, UNION SELECT readfile(..): the file is not read",
                api.get("/api/forecast?station=X'/**/UNION/**/SELECT/**/readfile('"
                        + secret + "'),1,1,1,1,1--"),
                "[]");

        Path planted = Paths.get(store.path()).resolveSibling("planted.txt");
        c.json("forecast, ;SELECT writefile(..) answers 200 []",
                api.get("/api/forecast?station=x';SELECT/**/writefile('"
                        + planted + "','over-http');--"),
                "[]");
        c.isTrue("and no file was written", !Files.exists(planted), planted + " exists");

        // A second statement after the quote used to be executed as well.
        // Still done on a copy, so a regression cannot damage the store
        // the other tests share.
        Store victim = store.copy();
        try (Http onVictim = Http.serve(victim)) {
            c.same("a copy of the store starts with 4 forecast rows", 4, victim.count("forecast"));
            c.json("forecast, x';DELETE FROM forecast;-- answers 200 []",
                    onVictim.get("/api/forecast?station=x';DELETE/**/FROM/**/forecast;--"), "[]");
            c.same("and that GET deleted nothing", 4, victim.count("forecast"));

            c.same("the copy starts with 7 daily rows", 7, victim.count("forecast_daily"));
            c.json("series, x';DELETE FROM forecast_daily;-- answers 200 []",
                    onVictim.get("/api/series?station=x';DELETE/**/FROM/**/forecast_daily;--&year=2025"), "[]");
            c.same("and that GET deleted nothing", 7, victim.count("forecast_daily"));
        }
        c.same("the store the other tests use is untouched", 4, store.count("forecast"));

        // The same claim the other way round: when a station really is
        // called X'OR'1'='1, that request finds its rows and only its rows.
        Store odd = store.copy();
        odd.sql("INSERT INTO forecast VALUES"
                + " ('X''OR''1''=''1', 2025, '2026-10-27T14:28:24', 4.5, 174, 'cropmod V2.3')");
        odd.sql("INSERT INTO forecast_daily VALUES"
                + " ('X''OR''1''=''1', 2025, 100, 50.5, 2.5, 0.0, 0.0, 0.0)");
        try (Http onOdd = Http.serve(odd)) {
            c.json("forecast: a station whose id is X'OR'1'='1 is found, alone",
                    onOdd.get("/api/forecast?station=X'OR'1'='1"),
                    "[{\"stnid\":\"X'OR'1'='1\",\"year\":2025,\"yield_t\":4.5,\"ndays\":174,"
                            + "\"model\":\"cropmod V2.3\",\"run_at\":\"2026-10-27T14:28:24\"}]");
            c.json("series: a station whose id is X'OR'1'='1 is found, alone",
                    onOdd.get("/api/series?station=X'OR'1'='1&year=2025"),
                    "[{\"doy\":100,\"sw\":50.5,\"et\":2.5,\"drain\":0.0,\"biom\":0.0,\"lai\":0.0}]");
            c.passthrough("and GUELPH's answer does not change because that row exists",
                    onOdd.get("/api/forecast?station=GUELPH"), "forecast-guelph.json");
        }

        // Binding keeps a quote from ending the SQL text, but the station is
        // still only safe while the SQL compares it with "=". Under a pattern
        // operator such as GLOB, "GU*" would match GUELPH with no quote at all.
        c.section("MRD-166: a wildcard in the station is text, not a pattern");
        c.json("forecast, station=GU*: no station has that id, GUELPH is not matched",
                api.get("/api/forecast?station=GU*"), "[]");
    }

    /** What the two routes answer when the store itself is unusable. */
    private static void brokenStores(Checks c, Path testDir) throws IOException {
        c.section("store with no tables: 500 sqlite3 exited 1");
        try (Http api = Http.serve(Store.withNoTables())) {
            c.error("forecast, no station", api.get("/api/forecast"), 500, "sqlite3 exited 1");
            c.error("forecast, station=GUELPH", api.get("/api/forecast?station=GUELPH"), 500, "sqlite3 exited 1");
            c.error("series", api.get("/api/series?station=GUELPH&year=2025"), 500, "sqlite3 exited 1");
            c.json("health does not touch the store", api.get("/api/health"),
                    "{\"status\":\"ok\",\"version\":\"4.2.1\"}");
        }

        c.section("store file that is not a database: 500 sqlite3 exited 26");
        try (Http api = Http.serve(Store.notADatabase())) {
            c.error("forecast, station=GUELPH", api.get("/api/forecast?station=GUELPH"), 500, "sqlite3 exited 26");
            c.error("series", api.get("/api/series?station=GUELPH&year=2025"), 500, "sqlite3 exited 26");
        }

        // DbClient's retry tells a lock from any other failure only by the
        // text "exited 5" (DbClient.isTransient), so the code matters.
        c.section("store locked by another process: 500 sqlite3 exited 5, then recovers");
        Store locked = Store.fromFixture(testDir);
        try (Http api = Http.serve(locked)) {
            try (Store.Lock held = locked.lockExclusively()) {
                c.error("forecast, no station", api.get("/api/forecast"), 500, "sqlite3 exited 5");
                c.error("forecast, station=GUELPH", api.get("/api/forecast?station=GUELPH"), 500, "sqlite3 exited 5");
                c.error("series", api.get("/api/series?station=GUELPH&year=2025"), 500, "sqlite3 exited 5");
            }
            c.passthrough("forecast answers again once the lock is released",
                    api.get("/api/forecast?station=GUELPH"), "forecast-guelph.json");
            c.passthrough("series answers again once the lock is released",
                    api.get("/api/series?station=GUELPH&year=2025"), "series-guelph-2025.json");
        }
    }
}

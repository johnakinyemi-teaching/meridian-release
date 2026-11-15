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
 * these two routes pass sqlite3's own JSON through, so the text is the
 * contract (the portal depends on "their exact JSON", ApiServer.java).
 *
 * The last section pins the MRD-166 defect as it stands. Those checks
 * describe behaviour that is wrong and is expected to change with the fix.
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
            injectionAsItStandsToday(c, api, store);
        }
        brokenStores(c, testDir);
    }

    private static void forecast(Checks c, Http api) throws IOException {
        c.section("/api/forecast");
        c.recorded("no station: every run, by station then year",
                api.get("/api/forecast"), ALL_FORECASTS);
        c.recorded("station=GUELPH: its two runs, by year",
                api.get("/api/forecast?station=GUELPH"), "forecast-guelph.json");
        c.recorded("station=KITCHNER: its one run",
                api.get("/api/forecast?station=KITCHNER"), "forecast-kitchner.json");
        c.recorded("station=MILTON: quote, comma and backslash in the model name",
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
        c.recorded("empty query string: no filter",
                api.get("/api/forecast?"), ALL_FORECASTS);
        c.recorded("a key with no '=' is ignored: no filter",
                api.get("/api/forecast?station"), ALL_FORECASTS);
        c.recorded("an empty key is ignored: no filter",
                api.get("/api/forecast?=GUELPH"), ALL_FORECASTS);
        c.recorded("another parameter only: no filter",
                api.get("/api/forecast?year=2025"), ALL_FORECASTS);
        c.recorded("station given twice: the last one wins",
                api.get("/api/forecast?station=GUELPH&station=KITCHNER"), "forecast-kitchner.json");
        c.recorded("an extra parameter does not disturb the station filter",
                api.get("/api/forecast?station=GUELPH&year=2024"), "forecast-guelph.json");

        c.section("/api/forecast: wired by hand, so no method or path checks");
        c.recorded("POST is answered like GET",
                api.send("POST", "/api/forecast?station=GUELPH"), "forecast-guelph.json");
        c.recorded("OPTIONS is answered like GET",
                api.send("OPTIONS", "/api/forecast?station=GUELPH"), "forecast-guelph.json");
        c.recorded("a path that only starts with the route name reaches it",
                api.get("/api/forecastX?station=GUELPH"), "forecast-guelph.json");
    }

    private static void series(Checks c, Http api) throws IOException {
        c.section("/api/series");
        c.recorded("GUELPH 2025: three days, by day of year",
                api.get("/api/series?station=GUELPH&year=2025"), "series-guelph-2025.json");
        c.recorded("GUELPH 2024: the year filter separates the two years",
                api.get("/api/series?station=GUELPH&year=2024"), "series-guelph-2024.json");
        c.recorded("MILTON 2025: NULL values come back as null",
                api.get("/api/series?station=MILTON&year=2025"), "series-milton-2025.json");
        c.recorded("year=+2025 parses as 2025",
                api.get("/api/series?station=GUELPH&year=+2025"), "series-guelph-2025.json");
        c.recorded("parameters in the other order",
                api.get("/api/series?year=2025&station=GUELPH"), "series-guelph-2025.json");
        c.recorded("POST is answered like GET",
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
     * MRD-166 as it behaves today. A raw single quote in the station ends
     * the SQL string literal the handler pastes it into, and whatever
     * follows is run as SQL.
     */
    private static void injectionAsItStandsToday(Checks c, Http api, Store store) throws IOException {
        c.section("MRD-166 defect, pinned as it stands");

        c.error("forecast, unmatched quote GU'ELPH: the SQL no longer parses",
                api.get("/api/forecast?station=GU'ELPH"), 500, "sqlite3 exited 1");
        c.error("series, unmatched quote GU'ELPH: the SQL no longer parses",
                api.get("/api/series?station=GU'ELPH&year=2025"), 500, "sqlite3 exited 1");

        c.recorded("forecast, X'OR'1'='1: every station's runs come back",
                api.get("/api/forecast?station=X'OR'1'='1"), ALL_FORECASTS);
        c.recorded("series, X'OR'1'='1: every station's 2025 days come back",
                api.get("/api/series?station=X'OR'1'='1&year=2025"), "defect-series-or.json");

        c.recorded("forecast, UNION SELECT: rows of the reading table come back",
                api.get("/api/forecast?station=X'UNION/**/SELECT/**/stnid,year,tmax,doy,stnid,stnid"
                        + "/**/FROM/**/reading--"),
                "defect-forecast-union.json");

        // The sqlite3 tool also has readfile() and writefile(), so the same
        // quote reads and writes files on the server as the service's user.
        Path secret = Paths.get(store.path()).resolveSibling("secret.txt");
        Files.write(secret, "meridian-test-secret".getBytes(StandardCharsets.UTF_8));
        Http.Response read = api.get("/api/forecast?station=X'/**/UNION/**/SELECT/**/readfile('"
                + secret + "'),1,1,1,1,1--");
        c.isTrue("forecast, UNION SELECT readfile(..): a file on the server comes back in the body",
                read.status == 200 && read.body.contains("meridian-test-secret"),
                read.status + " " + read.body);

        Path planted = Paths.get(store.path()).resolveSibling("planted.txt");
        Http.Response write = api.get("/api/forecast?station=x';SELECT/**/writefile('"
                + planted + "','over-http');--");
        c.isTrue("forecast, ;SELECT writefile(..): a file is written on the server",
                write.status == 200 && Files.exists(planted)
                        && "over-http".equals(new String(Files.readAllBytes(planted), StandardCharsets.UTF_8)),
                write.status + " " + write.body + " file exists: " + Files.exists(planted));

        // sqlite3 runs every statement it is handed, so a second statement
        // after the quote is executed too. Done on a copy: it destroys data.
        Store victim = store.copy();
        try (Http onVictim = Http.serve(victim)) {
            c.same("a copy of the store starts with 4 forecast rows", 4, victim.count("forecast"));
            c.json("forecast, x';DELETE FROM forecast;-- answers 200 []",
                    onVictim.get("/api/forecast?station=x';DELETE/**/FROM/**/forecast;--"), "[]");
            c.same("and that GET deleted every forecast row", 0, victim.count("forecast"));

            c.same("the copy starts with 7 daily rows", 7, victim.count("forecast_daily"));
            c.json("series, x';DELETE FROM forecast_daily;-- answers 200 []",
                    onVictim.get("/api/series?station=x';DELETE/**/FROM/**/forecast_daily;--&year=2025"), "[]");
            c.same("and that GET deleted every daily row", 0, victim.count("forecast_daily"));
        }
        c.same("the store the other tests use is untouched", 4, store.count("forecast"));
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
            c.recorded("forecast answers again once the lock is released",
                    api.get("/api/forecast?station=GUELPH"), "forecast-guelph.json");
            c.recorded("series answers again once the lock is released",
                    api.get("/api/series?station=GUELPH&year=2025"), "series-guelph-2025.json");
        }
    }
}

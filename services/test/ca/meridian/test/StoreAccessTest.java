package ca.meridian.test;

import ca.meridian.db.Db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Characterization tests for MRD-77: everything that reaches the store.
 *
 * Every route and every admin command goes through one class, Db. It
 * used to start the sqlite3 program for each query and hand back what it
 * printed; it now reads the store in-process. That change touched all of
 * them at once, so these checks were written before it, against the
 * program, and passed through it unedited. They pin:
 *
 *   - the text Db returns, against what sqlite3 itself prints for the same
 *     query on the same store, byte for byte;
 *   - the answer of every route that reads the store and is not already
 *     pinned by the forecast, series and cache tests;
 *   - what each route answers when the store has no tables, is not a
 *     database, or is locked, including the retry the newer routes make.
 *
 * The last section came with the change: what only a Db that reads the
 * store itself can do.
 */
final class StoreAccessTest {

    private StoreAccessTest() {
    }

    static void run(Checks c, Path testDir) throws Exception {
        Store store = Store.fromFixture(testDir);
        theTextDbReturns(c, store);
        howDbReportsFailure(c, testDir);
        try (Http api = Http.serve(store)) {
            theOtherRoutes(c, api);
            manyAtOnce(c, api);
        }
        everyRouteOnABrokenStore(c);
        theRetryOnALockedStore(c, testDir);
        withoutTheProgram(c, testDir);
        whenTheDriverCannotUnpack(c, testDir);
    }

    /**
     * Db.queryJson must return exactly what "sqlite3 -json" prints (trimmed,
     * and [] for no rows). The old routes send that text straight to the
     * consoles, and the newer routes parse it.
     */
    private static void theTextDbReturns(Checks c, Store store) throws IOException {
        c.section("Db.queryJson returns sqlite3's own text");
        Db db = new Db(store.path());
        String[] queries = {
            // the service's own queries
            "SELECT stnid, COUNT(*) AS days, MIN(year) AS first_year, MAX(year) AS last_year"
                    + " FROM reading GROUP BY stnid ORDER BY stnid",
            "SELECT stnid, year, yield_t, ndays, model, run_at FROM forecast ORDER BY stnid, year",
            "SELECT doy, sw, et, drain, biom, lai FROM forecast_daily ORDER BY stnid, year, doy",
            "SELECT COUNT(*) AS runs, MAX(run_at) AS latest_run, AVG(yield_t) AS mean_yield,"
                    + " MAX(year) AS last_year FROM forecast WHERE stnid = 'GUELPH'",
            "SELECT * FROM reading ORDER BY stnid, year, doy",
            "SELECT * FROM forecast ORDER BY stnid, year",
            "SELECT * FROM forecast_daily ORDER BY stnid, year, doy",
            // no rows
            "SELECT stnid FROM forecast WHERE stnid = 'NOWHERE'",
            // numbers of every shape
            "SELECT 1.0 AS a, 0.0 AS b, -0.0 AS c, 174.0 AS d, 0.1 AS e, 1/3.0 AS f, 2/3.0 AS g",
            "SELECT 1e300 AS big, 1e-300 AS small, 1e15 AS e15, 1e16 AS e16, 123456.789 AS mid",
            "SELECT 5.951 AS oracle, 95.33 AS sw, 3.669 AS et, 1401.7 AS biom, 6.138 AS y",
            "SELECT 9e999 AS inf, -9e999 AS ninf",
            "SELECT 0 AS zero, -5 AS neg, 2147483648 AS past_int, 9223372036854775807 AS max_long,"
                    + " -9223372036854775808 AS min_long",
            "SELECT yield_t * 1.1 AS a, yield_t / 3 AS b, yield_t * yield_t AS c, ndays * 1.0 AS d"
                    + " FROM forecast ORDER BY stnid, year",
            "SELECT sw * 0.37 AS a, et / 7 AS b, biom * 1.0001 AS c, lai + 0.1 AS d"
                    + " FROM forecast_daily ORDER BY stnid, year, doy",
            "SELECT AVG(tmax) AS a, AVG(tmin) AS b, SUM(rain) AS c, MIN(srad) AS d, MAX(rh) AS e,"
                    + " AVG(wind) AS f FROM reading GROUP BY stnid ORDER BY stnid",
            // NULL, and text that needs escaping
            "SELECT NULL AS n, '' AS empty, 'plain' AS p, 'it''s' AS apostrophe",
            "SELECT 'q\"b\\s/' AS quote_backslash, char(9) AS tab, char(10) AS newline,"
                    + " char(13) AS cr, char(8) AS backspace, char(12) AS formfeed",
            "SELECT CAST(x'c3a974c3a920e697a5e69cac' AS TEXT) AS outside_ascii, char(127) AS del",
            // column names that need escaping
            "SELECT 1 AS \"a b\", 2 AS \"c\"\"d\", 3 AS \"e\\f\"",
        };
        for (String sql : queries) {
            c.same(shortened(sql), Reals.asTheServiceSpellsThem(store.json(sql)), db.queryJson(sql));
        }
    }

    /** A failed query surfaces as "sqlite3 exited N", N being SQLite's own result code. */
    private static void howDbReportsFailure(Checks c, Path testDir) throws IOException {
        c.section("Db.queryJson: how a failure is reported");
        String q = "SELECT stnid FROM forecast";
        c.same("a statement that does not parse", "sqlite3 exited 1",
                failure(new Db(Store.fromFixture(testDir).path()), "SELEC nonsense"));
        c.same("a column that does not exist", "sqlite3 exited 1",
                failure(new Db(Store.fromFixture(testDir).path()), "SELECT nope FROM forecast"));
        c.same("a store with no tables", "sqlite3 exited 1",
                failure(new Db(Store.withNoTables().path()), q));
        c.same("a file that is not a database", "sqlite3 exited 26",
                failure(new Db(Store.notADatabase().path()), q));
        c.same("a store in a directory that does not exist", "sqlite3 exited 1",
                failure(new Db(Store.withNoTables().path() + ".missing/meridian.db"), q));
        c.same("a store path that is a directory", "sqlite3 exited 1",
                failure(new Db(Store.aDirectory().path()), q));
        Store locked = Store.fromFixture(testDir);
        try (Store.Lock held = locked.lockExclusively()) {
            c.same("a store locked by another process", "sqlite3 exited 5",
                    failure(new Db(locked.path()), q));
        }
        c.same("and once the lock is released it answers",
                "[{\"n\":4}]", new Db(locked.path()).queryJson("SELECT COUNT(*) AS n FROM forecast"));

        // Nothing is kept open between calls: when the store file is
        // replaced, the very next call reads the new one.
        String count = "SELECT COUNT(*) AS n FROM forecast";
        Store first = Store.fromFixture(testDir);
        Db db = new Db(first.path());
        c.same("the store as first opened", "[{\"n\":4}]", db.queryJson(count));
        Store smaller = first.copy();
        smaller.sql("DELETE FROM forecast WHERE stnid <> 'GUELPH'");
        Files.move(Paths.get(smaller.path()), Paths.get(first.path()),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        c.same("the store file is replaced: the next call reads the new file",
                "[{\"n\":2}]", db.queryJson(count));
    }

    /** The routes that read the store and are not pinned elsewhere. */
    private static void theOtherRoutes(Checks c, Http api) throws IOException {
        c.section("the routes behind the Router and the v2 dispatcher");
        c.recorded("/api/summary: every station", api.get("/api/summary"), "summary-all.json");
        c.recorded("/api/summary?station=guelph: case is folded here",
                api.get("/api/summary?station=guelph"), "summary-guelph.json");
        c.recorded("/api/anomalies: every flagged reading",
                api.get("/api/anomalies"), "anomalies-all.json");
        c.recorded("/api/anomalies?station=GUELPH&year=2025",
                api.get("/api/anomalies?station=GUELPH&year=2025"), "anomalies-guelph-2025.json");
        c.recorded("/api/v2/stations: coverage and catalogue",
                api.get("/api/v2/stations"), "v2-stations.json");
        c.recorded("/api/v2/export?table=forecast&fmt=json",
                api.get("/api/v2/export?table=forecast&fmt=json"), "v2-export-forecast.json");

        c.section("CSV exports");
        c.csv("/api/export: the forecast table by default, with a quoted model name",
                api.get("/api/export"), "export-forecast.csv");
        c.csv("/api/export?table=series&station=GUELPH&year=2025",
                api.get("/api/export?table=series&station=GUELPH&year=2025"), "export-series-guelph-2025.csv");
        c.csv("/api/export?table=readings&station=MILTON&year=2025: a NULL is an empty field",
                api.get("/api/export?table=readings&station=MILTON&year=2025"), "export-readings-milton-2025.csv");
        c.csv("/api/v2/export?table=forecast&fmt=csv is the same file",
                api.get("/api/v2/export?table=forecast&fmt=csv"), "export-forecast.csv");
        c.error("/api/export?table=forecast_daily: not a table it knows",
                api.get("/api/export?table=forecast_daily"), 400, "unknown table: forecast_daily");
    }

    /** Forty requests, eight at a time: each route still gives its recorded answer. */
    private static void manyAtOnce(Checks c, Http api) throws Exception {
        c.section("forty requests, eight at a time");
        String[] targets = {
            "/api/forecast?station=GUELPH",
            "/api/series?station=GUELPH&year=2025",
            "/api/stations",
            "/api/summary?station=GUELPH",
            "/api/v2/forecast?station=GUELPH",
        };
        List<String> expected = new ArrayList<>();
        for (String t : targets) {
            Http.Response first = api.get(t);
            expected.add(first.status + "\n" + first.body);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String target = targets[i % targets.length];
                answers.add(pool.submit(() -> {
                    Http.Response r = api.get(target);
                    return r.status + "\n" + r.body;
                }));
            }
            int same = 0;
            for (int i = 0; i < answers.size(); i++) {
                if (answers.get(i).get().equals(expected.get(i % targets.length))) {
                    same++;
                }
            }
            c.same("answers identical to the one given alone", 40, same);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A3's T26: with no tables, health answers and every data route is a 500. */
    private static void everyRouteOnABrokenStore(Checks c) throws IOException {
        String[] dataRoutes = {
            "/api/stations",
            "/api/forecast",
            "/api/series?station=GUELPH&year=2025",
            "/api/summary",
            "/api/summary?station=GUELPH",
            "/api/anomalies",
            "/api/export",
            "/api/v2/forecast?station=GUELPH",
            "/api/v2/stations",
            "/api/v2/export?table=forecast&fmt=json",
        };
        c.section("store with no tables: every data route answers 500 sqlite3 exited 1");
        try (Http api = Http.serve(Store.withNoTables())) {
            for (String route : dataRoutes) {
                c.error(route, api.get(route), 500, "sqlite3 exited 1");
            }
            c.json("/api/health still answers", api.get("/api/health"),
                    "{\"status\":\"ok\",\"version\":\"4.2.1\"}");
        }
        c.section("store file that is not a database: every data route answers 500 sqlite3 exited 26");
        try (Http api = Http.serve(Store.notADatabase())) {
            for (String route : dataRoutes) {
                c.error(route, api.get(route), 500, "sqlite3 exited 26");
            }
        }
    }

    /**
     * A3's T27. The old routes give up on a locked store at once. The newer
     * ones go through DbClient, which recognises a lock by the text
     * "exited 5" and tries twice more, waiting 120 ms and then 240 ms.
     */
    private static void theRetryOnALockedStore(Checks c, Path testDir) throws IOException {
        c.section("store locked by another process: the newer routes retry, then 500 sqlite3 exited 5");
        Store locked = Store.fromFixture(testDir);
        try (Http api = Http.serve(locked)) {
            try (Store.Lock held = locked.lockExclusively()) {
                long started = System.nanoTime();
                c.error("/api/summary?station=GUELPH", api.get("/api/summary?station=GUELPH"),
                        500, "sqlite3 exited 5");
                long waitedMillis = (System.nanoTime() - started) / 1_000_000;
                c.isTrue("it waited out both retries first, and no more than that (120 ms + 240 ms)",
                        waitedMillis >= 360 && waitedMillis < 1_500, "answered after " + waitedMillis + " ms");
                c.error("/api/v2/forecast?station=GUELPH", api.get("/api/v2/forecast?station=GUELPH"),
                        500, "sqlite3 exited 5");
                long oldRouteStarted = System.nanoTime();
                c.error("/api/stations, an old route", api.get("/api/stations"), 500, "sqlite3 exited 5");
                long oldRouteMillis = (System.nanoTime() - oldRouteStarted) / 1_000_000;
                c.isTrue("and the old route does not wait for the lock: it answers at once",
                        oldRouteMillis < 2_000, "answered after " + oldRouteMillis + " ms");
            }
            c.recorded("/api/summary answers again once the lock is released",
                    api.get("/api/summary?station=GUELPH"), "summary-guelph.json");
        }
    }

    /**
     * MRD-77. What only a Db that reads the store itself can do. Each of
     * the first six failed while Db started the sqlite3 program.
     */
    private static void withoutTheProgram(Checks c, Path testDir) throws IOException {
        c.section("MRD-77: the store is read in-process");
        Store store = Store.fromFixture(testDir);
        Db db = new Db(store.path());

        Admin.Run noProgram = Admin.runWithNothingOnThePath(store, "stations");
        c.same("with no sqlite3 on the PATH the admin tool still works: exit status", 0, noProgram.exit);
        c.text("with no sqlite3 on the PATH the admin tool still works: what it prints",
                noProgram.out, "admin-stations.txt");

        c.same("the SQLite in use is the one inside services/lib",
                "[{\"v\":\"3.46.1\"}]", answer(db, "SELECT sqlite_version() AS v"));

        c.same("a bound value 200,000 characters long is looked up, not refused",
                "[{\"n\":0}]",
                answer(db, "SELECT COUNT(*) AS n FROM forecast WHERE stnid = ?1", "A".repeat(200_000)));

        c.same("a control character in text is written as a valid JSON escape",
                "[{\"c\":\"\\u0001\"}]", answer(db, "SELECT char(1) AS c"));

        c.same("a call holding two statements runs the first only",
                "[{\"a\":1}]", answer(db, "SELECT 1 AS a; SELECT 2 AS b"));

        c.same("values and placeholders must match in number",
                "java.lang.IllegalArgumentException: 2 value(s) for 1 placeholder(s)",
                answer(db, "SELECT ?1 AS v", "a", "b"));

        String big = "SELECT 9223372036854775808.0 AS two_to_63, -9223372036854775808.0 AS minus";
        c.same("a whole number too large for a long keeps SQLite's spelling",
                Reals.asTheServiceSpellsThem(store.json(big)), answer(db, big));

        // Reals adapts expected numbers to this machine's spelling. Where the
        // recordings were made, the spelling itself is held to what the
        // consoles have always been sent.
        String arch = System.getProperty("os.arch");
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            c.same("on x86-64 the oracle value 5.951 is spelled as it always was",
                    "[{\"v\":5.95099999999999962}]", answer(db, "SELECT 5.951 AS v"));
        } else {
            System.out.println("  note  this machine is " + arch
                    + ", not x86-64: numbers are compared in its own spelling");
        }
    }

        /**
     * MRD-77, row 9 of the changed-answers table. If the driver cannot
     * unpack its library, every request must still be answered: 500 with
     * "sqlite3 exited 1", and health 200. The driver loads once per Java
     * process and this one has already loaded it, so a second process is
     * started with a temporary folder that can never exist.
     */
    private static void whenTheDriverCannotUnpack(Checks c, Path testDir) throws IOException {
        c.section("MRD-77: the driver cannot unpack its library");
        // A path inside an ordinary file can never be a folder.
        String neverAFolder = Store.withNoTables().path() + "/unpack";

        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-Dorg.sqlite.tmpdir=" + neverAFolder);
        command.add("ca.meridian.test.StoreAccessTest$Child");
        command.add(testDir.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.getOutputStream().close();
                int exit;
        try {
            if (p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                exit = p.exitValue();
            } else {
                p.destroyForcibly();
                p.waitFor();
                exit = -1;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the second Java process", ie);
        }
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();

        String failed = "500 {\"error\":\"sqlite3 exited 1\"}";
        String expected = "exit 0\n"
                + "/api/stations -> " + failed + "\n"
                + "/api/stations -> " + failed + "\n"
                + "/api/v2/stations -> " + failed + "\n"
                + "/api/health -> 200 {\"status\":\"ok\",\"version\":\"4.2.1\"}";
        c.same("with a driver that cannot unpack, every request is still answered",
                expected, "exit " + exit + "\n" + out);
    }

    /** Runs in the second Java process: serve the fixture store, ask four things, print the answers. */
    static final class Child {
        public static void main(String[] args) throws Exception {
            Store store = Store.fromFixture(Paths.get(args[0]));
            try (Http api = Http.serve(store)) {
                System.out.println(ask(api, "/api/stations"));
                System.out.println(ask(api, "/api/stations"));
                System.out.println(ask(api, "/api/v2/stations"));
                System.out.println(ask(api, "/api/health"));
            }
            Store.deleteAll();
            System.exit(0);
        }

        private static String ask(Http api, String target) {
            try {
                Http.Response r = api.get(target);
                return target + " -> " + r.status + " " + r.body;
            } catch (IOException e) {
                return target + " -> no answer: " + e;
            }
        }
    }

    /** What Db returns, or how it failed. */
    private static String answer(Db db, String sql, Object... params) {
        try {
            return db.queryJson(sql, params);
        } catch (IOException | RuntimeException e) {
            return e.toString();
        }
    }

    private static String failure(Db db, String sql) {
        try {
            db.queryJson(sql);
            return "no failure";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static String shortened(String sql) {
        return sql.length() <= 72 ? sql : sql.substring(0, 69) + "...";
    }
}

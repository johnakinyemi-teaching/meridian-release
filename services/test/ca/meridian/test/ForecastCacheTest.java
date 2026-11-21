package ca.meridian.test;

import ca.meridian.db.Db;
import ca.meridian.db.DbClient;
import ca.meridian.db.ForecastCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Characterization tests for the v2 forecast-summary cache and the admin
 * tool's evict command, which MRD-247 is about.
 *
 * GET /api/v2/forecast?station=X returns the station's runs, read live,
 * and a summary block that is cached per station for a TTL. After a
 * backfill writes a new run, ops run "evict" so the summary catches up
 * without waiting the TTL out.
 *
 * Two things stopped that working, and both are fixed and checked here.
 * The cache was asked to remove a key it never stored. And the admin tool
 * is a separate process whose cache is not the server's, so evict now
 * also leaves a note beside the store that the running server reads.
 */
final class ForecastCacheTest {

    private static final String V2_GUELPH = "/api/v2/forecast?station=GUELPH";

    // A backfill: a third GUELPH run, for 2026.
    private static final String NEW_RUN = "INSERT INTO forecast VALUES"
            + " ('GUELPH', 2026, '2027-10-28T03:02:11', 6.2, 171, 'cropmod V2.3')";

    private ForecastCacheTest() {
    }

    static void run(Checks c, Path testDir) throws IOException {
        Store fixture = Store.fromFixture(testDir);
        v2Forecast(c, fixture);
        otherAdminCommands(c, fixture);
        staleAfterABackfill(c, fixture.copy());
        evictIsPerStation(c, fixture.copy());
        anUnreadableTokenFallsBackToTtl(c, fixture.copy());
        theMarksDirectory(c, fixture.copy());
        theCacheItself(c, fixture.copy());
        evictArguments(c, fixture.copy());
        ttlStillRefreshes(c, fixture.copy());
    }

    /** The v2 route's answers on an unchanged store. None of this moves. */
    private static void v2Forecast(Checks c, Store store) throws IOException {
        c.section("/api/v2/forecast");
        try (Http api = Http.serve(store)) {
            c.recorded("station=GUELPH: two rows and their summary",
                    api.get(V2_GUELPH), "v2-forecast-guelph.json");
            c.recorded("the same call again is identical",
                    api.get(V2_GUELPH), "v2-forecast-guelph.json");
            c.recorded("the v2 route folds case: station=guelph",
                    api.get("/api/v2/forecast?station=guelph"), "v2-forecast-guelph.json");
            c.recorded("page 2 of size 1 carries the same summary",
                    api.get(V2_GUELPH + "&page=2&size=1"), "v2-forecast-guelph-page2.json");
            c.recorded("a station with no runs: summary of zero runs",
                    api.get("/api/v2/forecast?station=NOWHERE"), "v2-forecast-nowhere.json");
            c.recorded("no station: every run, summary null",
                    api.get("/api/v2/forecast"), "v2-forecast-all.json");
            c.error("a quoted station is rejected",
                    api.get("/api/v2/forecast?station=GU'ELPH"), 400, "not a station id: GU'ELPH");
        }
    }

    /** The admin commands that are not being changed. */
    private static void otherAdminCommands(Checks c, Store store) throws IOException {
        c.section("admin tool: commands other than evict");
        c.text("stations", Admin.run(store, "stations").out, "admin-stations.txt");
        c.text("coverage GUELPH", Admin.run(store, "coverage", "GUELPH").out, "admin-coverage-guelph.txt");
        c.text("suspect GUELPH 2025", Admin.run(store, "suspect", "GUELPH", "2025").out, "admin-suspect-guelph-2025.txt");
        c.text("help", Admin.run(store, "help").out, "admin-help.txt");
    }

    /**
     * The scenario in the ticket, end to end: a running server, a new run
     * written to the store, then evict from the admin tool.
     */
    private static void staleAfterABackfill(Checks c, Store store) throws IOException {
        c.section("a backfill, then evict, against a running server");
        try (Http api = Http.serve(store)) {
            c.recorded("first read: two runs, summary of two",
                    api.get(V2_GUELPH), "v2-forecast-guelph.json");

            store.sql(NEW_RUN);
            c.passthrough("the old route has no cache and shows the third run at once",
                    api.get("/api/forecast?station=GUELPH"), "forecast-guelph-three.json");
            c.recorded("v2 shows three rows, but its cached summary still says two",
                    api.get(V2_GUELPH), "v2-forecast-guelph-stale.json");

            Admin.Run evict = Admin.run(store, "evict", "GUELPH", "2026");
            c.same("evict GUELPH 2026: exit status", 0, evict.exit);
            c.same("evict removes the entry from its own cache, and says so",
                    "evicted v2 summary for GUELPH 2026 (entries now 0)\n", evict.out);
            c.recorded("after evict the running server, a separate process, now shows three",
                    api.get(V2_GUELPH), "v2-forecast-guelph-fresh.json");
            c.recorded("and it stays fresh on the next read",
                    api.get(V2_GUELPH), "v2-forecast-guelph-fresh.json");
        }
    }

    /**
     * Eviction reaches only the station it names. Evicting one station must
     * not refresh another's cached summary.
     */
    private static void evictIsPerStation(Checks c, Store store) throws IOException {
        c.section("evict refreshes only the station it names");
        try (Http api = Http.serve(store)) {
            c.recorded("GUELPH cached at two runs", api.get(V2_GUELPH), "v2-forecast-guelph.json");
            c.recorded("KITCHNER cached at one run",
                    api.get("/api/v2/forecast?station=KITCHNER"), "v2-forecast-kitchner.json");

            store.sql(NEW_RUN); // GUELPH 2026
            store.sql("INSERT INTO forecast VALUES"
                    + " ('KITCHNER', 2026, '2027-10-28T03:02:11', 6.3, 170, 'cropmod V2.3')");

            Admin.Run evict = Admin.run(store, "evict", "GUELPH", "2026");
            c.same("evict GUELPH only: exit status", 0, evict.exit);

            c.recorded("GUELPH, evicted, now shows three",
                    api.get(V2_GUELPH), "v2-forecast-guelph-fresh.json");
            c.recorded("KITCHNER, not evicted: its new row is live but its summary stays cached at one",
                    api.get("/api/v2/forecast?station=KITCHNER"), "v2-forecast-kitchner-stale.json");
        }
    }

    /**
     * A token that cannot be read must not break the endpoint. If the mark
     * path is a directory, the read fails; the summary is served fresh and
     * uncached rather than turning into a 500.
     */
    private static void anUnreadableTokenFallsBackToTtl(Checks c, Store store) throws IOException {
        c.section("an unreadable eviction token falls back, it does not 500");
        Files.createDirectories(Paths.get(store.path() + ".evict", "GUELPH"));
        try (Http api = Http.serve(store)) {
            c.recorded("the token path is a directory, yet GUELPH still answers 200",
                    api.get(V2_GUELPH), "v2-forecast-guelph.json");
            store.sql(NEW_RUN);
            c.recorded("and with the read uncached, a new run shows up at once",
                    api.get(V2_GUELPH), "v2-forecast-guelph-fresh.json");
        }
    }

    /** The eviction directory keeps itself out of git, and rejects non-ids. */
    private static void theMarksDirectory(Checks c, Store store) throws IOException {
        c.section("the eviction directory");
        Admin.Run evict = Admin.run(store, "evict", "GUELPH", "2025");
        c.same("evict writes a note: exit status", 0, evict.exit);
        Path dir = Paths.get(store.path() + ".evict");
        c.isTrue("a note was written for GUELPH", Files.isRegularFile(dir.resolve("GUELPH")),
                "no file at " + dir.resolve("GUELPH"));
        Path ignore = dir.resolve(".gitignore");
        c.isTrue("the directory ignores itself in git: a .gitignore holding *",
                Files.isRegularFile(ignore)
                        && "*\n".equals(new String(Files.readAllBytes(ignore), StandardCharsets.UTF_8)),
                "no such .gitignore in " + dir);

        // The tool upper-cases its argument, so a note that escaped the
        // directory would be a file named X beside the store.
        Admin.Run notId = Admin.run(store, "evict", "../x", "2025");
        c.same("a non-station id still exits 0", 0, notId.exit);
        c.same("but nothing is written beside the store for ../x",
                "[meridian.db, meridian.db.evict]", names(dir.getParent()));
        c.same("and the directory holds GUELPH's note and nothing else",
                "[.gitignore, GUELPH]", names(dir));
    }

    /** The names in a directory, sorted. */
    private static String names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return "(no directory " + dir.getFileName() + ")";
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).sorted()
                    .collect(Collectors.toList()).toString();
        }
    }

    /** ForecastCache on its own, with no server and no second process. */
    private static void theCacheItself(Checks c, Store store) throws IOException {
        c.section("ForecastCache");
        ForecastCache cache = new ForecastCache(new DbClient(new Db(store.path())), 300_000L);

        ForecastCache.Summary guelph = cache.summary("GUELPH");
        c.same("GUELPH: runs", 2, guelph.runs);
        c.same("GUELPH: latest run", "2026-10-27T14:28:24", guelph.latestRun);
        c.same("GUELPH: last year", 2025, guelph.lastYear);
        c.same("GUELPH: mean yield, to 3 places", "5.732",
                String.format(java.util.Locale.ROOT, "%.3f", guelph.meanYield));
        c.same("one entry is held", 1, cache.size());
        c.isTrue("a second read inside the TTL is the cached object",
                cache.summary("GUELPH") == guelph, "a different object came back");
        c.isTrue("the key ignores case and surrounding spaces",
                cache.summary(" guelph ") == guelph, "a different object came back");

        ForecastCache.Summary kitchner = cache.summary("KITCHNER");
        c.same("KITCHNER: runs", 1, kitchner.runs);
        c.same("two entries are held", 2, cache.size());

        store.sql(NEW_RUN);
        cache.invalidate("GUELPH", 2026);
        c.same("invalidate(GUELPH, 2026) removes GUELPH's entry and no other", 1, cache.size());
        c.isTrue("KITCHNER's entry is still the cached object",
                cache.summary("KITCHNER") == kitchner, "a different object came back");
        ForecastCache.Summary reloaded = cache.summary("GUELPH");
        c.same("GUELPH's next read sees the new run: runs", 3, reloaded.runs);
        c.same("GUELPH's next read sees the new run: latest run", "2027-10-28T03:02:11", reloaded.latestRun);
        c.same("GUELPH's next read sees the new run: last year", 2026, reloaded.lastYear);
        c.same("both stations are held again", 2, cache.size());

        cache.invalidate(" guelph ", 2024);
        c.same("the year plays no part, nor case or spaces: GUELPH is evicted again", 1, cache.size());
        cache.invalidate("NOWHERE", 2026);
        c.same("evicting a station that is not held changes nothing", 1, cache.size());
        cache.summary("GUELPH");

        cache.clear();
        c.same("clear() drops every entry", 0, cache.size());
        c.same("and the read after clear() sees the new run", 3, cache.summary("GUELPH").runs);
    }

    /** What evict does with each kind of argument. */
    private static void evictArguments(Checks c, Store store) throws IOException {
        c.section("admin evict: arguments");

        Admin.Run lower = Admin.run(store, "evict", "guelph", "2025");
        c.same("the station is upper-cased",
                "evicted v2 summary for GUELPH 2025 (entries now 0)\n", lower.out);

        Admin.Run unknown = Admin.run(store, "evict", "NOWHERE", "2025");
        c.same("a station with no runs is accepted: exit status", 0, unknown.exit);
        c.same("a station with no runs is accepted: what it prints",
                "evicted v2 summary for NOWHERE 2025 (entries now 0)\n", unknown.out);

        Admin.Run notAnId = Admin.run(store, "evict", "../x", "2025");
        c.same("text that is not a station id is accepted too: exit status", 0, notAnId.exit);
        c.same("text that is not a station id is accepted too: what it prints",
                "evicted v2 summary for ../X 2025 (entries now 0)\n", notAnId.out);

        Admin.Run badYear = Admin.run(store, "evict", "GUELPH", "abc");
        c.same("a year that is not a number: exit status", 1, badYear.exit);
        c.isTrue("a year that is not a number: dies with an uncaught NumberFormatException",
                badYear.err.contains("java.lang.NumberFormatException") && badYear.out.isEmpty(),
                "stdout=[" + badYear.out + "] stderr=[" + badYear.err + "]");

        Admin.Run noYear = Admin.run(store, "evict", "GUELPH");
        c.same("no year: exit status", 2, noYear.exit);
        c.same("no year: usage on stderr", "usage: evict <station> <year>\n", noYear.err);
        c.same("no year: nothing on stdout", "", noYear.out);
    }

    /** Without any evict, the TTL alone refreshes the summary. */
    private static void ttlStillRefreshes(Checks c, Store store) throws IOException {
        c.section("the TTL is the fallback (meridian.v2.cache.ttl=2)");
        try (Http api = Http.serve(store, 2)) {
            c.recorded("first read: summary of two",
                    api.get(V2_GUELPH), "v2-forecast-guelph.json");
            store.sql(NEW_RUN);
            c.recorded("straight after the backfill the summary is the cached one",
                    api.get(V2_GUELPH), "v2-forecast-guelph-stale.json");
            Store.pause(2_500);
            c.recorded("once the TTL has passed the summary says three",
                    api.get(V2_GUELPH), "v2-forecast-guelph-fresh.json");
        }
    }
}

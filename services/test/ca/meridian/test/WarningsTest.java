package ca.meridian.test;

import ca.meridian.db.Db;
import ca.meridian.db.DbClient;
import ca.meridian.db.ForecastCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * What the service writes to standard error about a failure it handles on
 * purpose. Team Aquifer's review of pull request #24 asked for this: when
 * an eviction note cannot be read, the summary is still answered, so the
 * answer cannot show that the cache has stopped caching. It also asked for
 * the cause when the SQLite driver cannot load; that can only happen in a
 * fresh JVM, so here only the other side of it is held: a store that
 * SQLite itself refuses to open is not the driver's failure.
 *
 * The checks call ForecastCache directly, with no server in between, so
 * everything written to standard error during a call comes from that call.
 */
final class WarningsTest {

    private WarningsTest() {
    }

    static void run(Checks c, Path testDir) throws IOException {
        Store fixture = Store.fromFixture(testDir);
        anEvictionNoteThatCannotBeRead(c, fixture.copy());
        nothingIsSaidWhenNothingIsWrong(c, fixture.copy());
        aStoreThatCannotBeOpened(c);
    }

    /** A directory where the note should be: the read fails, the answer does not. */
    private static void anEvictionNoteThatCannotBeRead(Checks c, Store store) throws IOException {
        c.section("standard error: an eviction note that cannot be read");
        Files.createDirectories(Paths.get(store.path() + ".evict", "GUELPH"));
        ForecastCache cache = new ForecastCache(new DbClient(new Db(store.path())), 300_000L);

        int[] runs = new int[1];
        String said = standardErrorDuring(() -> runs[0] = cache.summary("GUELPH").runs);
        c.same("the summary is still answered: runs", 2, runs[0]);
        c.same("and nothing is written to standard error", "", said);
    }

    /** The ordinary cases must stay quiet, or the line means nothing. */
    private static void nothingIsSaidWhenNothingIsWrong(Checks c, Store store) throws IOException {
        c.section("standard error: nothing is written when nothing is wrong");
        ForecastCache cache = new ForecastCache(new DbClient(new Db(store.path())), 300_000L);

        c.same("no note for the station", "",
                standardErrorDuring(() -> cache.summary("GUELPH")));
        cache.invalidate("GUELPH", 2025);
        c.same("a note that can be read", "",
                standardErrorDuring(() -> cache.summary("GUELPH")));
    }

    /**
     * SQLite's own refusals are reported by their code, as before, and are
     * not the driver failing to load: no line is written for them.
     */
    private static void aStoreThatCannotBeOpened(Checks c) throws IOException {
        c.section("standard error: a store SQLite refuses to open is not a driver failure");
        String missing = Store.withNoTables().path() + ".missing/meridian.db";
        c.same("a store in a directory that does not exist: nothing is written", "",
                standardErrorDuring(() -> failure(new Db(missing))));
        String directory = Store.aDirectory().path();
        c.same("a store path that is a directory: nothing is written", "",
                standardErrorDuring(() -> failure(new Db(directory))));
    }

    /** Runs a query that is expected to fail, and returns how it failed. */
    private static String failure(Db db) {
        try {
            db.queryJson("SELECT COUNT(*) AS n FROM forecast");
            return "no failure";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private interface Call {
        void run() throws IOException;
    }

    /** Runs the call with standard error captured, and returns what was written to it. */
    private static String standardErrorDuring(Call call) throws IOException {
        PrintStream real = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, "UTF-8"));
        try {
            call.run();
        } finally {
            System.err.flush();
            System.setErr(real);
        }
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }
}

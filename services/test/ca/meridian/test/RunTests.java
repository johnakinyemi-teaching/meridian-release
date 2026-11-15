package ca.meridian.test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the services tests. Run it through services/test.sh.
 *
 * The argument is the directory holding fixture.sql and expected/.
 */
public final class RunTests {

    private RunTests() {
    }

    public static void main(String[] args) {
        Path testDir = Paths.get(args.length > 0 ? args[0] : "test");
        Checks checks = new Checks(testDir);
        int status;
        try {
            ForecastSeriesTest.run(checks, testDir);
            DbBindTest.run(checks, testDir);
            ForecastCacheTest.run(checks, testDir);
            status = checks.finish();
        } catch (Throwable broken) {
            // A test that cannot run is a failure, not a skip.
            broken.printStackTrace();
            status = 1;
        } finally {
            Store.deleteAll();
        }
        // ApiServer's worker threads are not daemons, so leave explicitly.
        System.exit(status);
    }
}

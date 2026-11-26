package ca.meridian.db;

import ca.meridian.util.Clock;

import java.sql.SQLException;

/**
 * One line on standard error for a failure this package handles on purpose
 * and would otherwise say nothing about.
 *
 * Two places need it. When the SQLite driver cannot load, every query is
 * reported as "sqlite3 exited 1", the text callers already know, so the
 * answer cannot say why. When an eviction note cannot be read, the summary
 * is loaded fresh on every request and the answer looks normal. In both the
 * service keeps answering as designed; this line is how an operator finds
 * out. Nothing about any response changes.
 *
 * There is no logging framework here (see api/RequestLog), so it is a plain
 * line in the request log's style: the time, then the text. One line each
 * time it happens, with no stack trace.
 */
final class DbLog {

    /**
     * Said when the driver cannot load SQLite itself. The first query after
     * start-up fails inside the driver (see warnIfTheDriverFailed); every
     * later one fails as a LinkageError in Db.queryJson. Both say this.
     */
    static final String DRIVER_NOT_LOADED =
            "the SQLite driver could not load, so queries fail with \"sqlite3 exited 1\"."
            + " It unpacks a native library into the temporary directory (java.io.tmpdir, or"
            + " -Dorg.sqlite.tmpdir), which must be writable and must allow programs to run";

    private DbLog() {
    }

    /** Said when a station's eviction note is there but cannot be read. */
    static String noteUnreadable(String stationId) {
        return "the eviction note for " + stationId + " cannot be read, so its summary is"
                + " loaded on every request and not cached";
    }

    /**
     * Says so when a failure to open the store is the driver's, not SQLite's.
     * SQLite's own refusals (no such directory, a directory in the file's
     * place) arrive with no cause and are reported by their code, as before.
     * When the driver could not load SQLite at all it wraps what went wrong,
     * and the innermost of that is the cause written here.
     */
    static void warnIfTheDriverFailed(SQLException cannotOpen) {
        Throwable cause = cannotOpen.getCause();
        if (cause == null) {
            return;
        }
        for (int depth = 0; cause.getCause() != null && depth < 16; depth++) {
            cause = cause.getCause();
        }
        warn(DRIVER_NOT_LOADED, cause);
    }

    /** Writes one line: the time, what is wrong, and the cause. Never throws. */
    static void warn(String what, Throwable cause) {
        try {
            System.err.println(Clock.isoUtc() + " meridian warning: " + what + ": " + onOneLine(cause));
        } catch (RuntimeException sayingSoFailed) {
            // Saying so must never be what fails the request.
        }
    }

    private static String onOneLine(Throwable cause) {
        return String.valueOf(cause).replace('\r', ' ').replace('\n', ' ');
    }
}

package ca.meridian.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Counts passes and failures and prints one line per check.
 *
 * A check compares text for exact equality. When a response is compared
 * with a recorded file and differs, what came back is written to
 * build/test-actual/ under the same file name.
 */
final class Checks {

    private static final Path ACTUAL_DIR = Paths.get("build", "test-actual");

    private final Path expectedDir;
    private int passed;
    private int failed;

    Checks(Path testDir) {
        this.expectedDir = testDir.resolve("expected");
    }

    void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    void same(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            pass(name);
        } else {
            fail(name, expected, actual);
        }
    }

    void same(String name, int expected, int actual) {
        same(name, String.valueOf(expected), String.valueOf(actual));
    }

    void isTrue(String name, boolean condition, String whatWasSeen) {
        if (condition) {
            pass(name);
        } else {
            fail(name, "true", "false: " + whatWasSeen);
        }
    }

    /** A 200 JSON response whose body must equal the given text exactly. */
    void json(String name, Http.Response r, String expectedBody) {
        same(name, describe(200, expectedBody), describe(r));
    }

    /** A 200 JSON response whose body must equal a recorded file exactly. */
    void recorded(String name, Http.Response r, String file) throws IOException {
        Path p = expectedDir.resolve(file);
        String expectedBody = Files.exists(p)
                ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                : "(no recorded file " + p + ")";
        String expected = describe(200, expectedBody);
        String actual = describe(r);
        if (expected.equals(actual)) {
            pass(name);
            return;
        }
        fail(name, expected, actual);
        Files.createDirectories(ACTUAL_DIR);
        Files.write(ACTUAL_DIR.resolve(file), r.body.getBytes(StandardCharsets.UTF_8));
    }

    /** An error response: status and the exact {"error":...} body. */
    void error(String name, Http.Response r, int status, String message) {
        same(name, describe(status, "{\"error\":\"" + message + "\"}"), describe(r));
    }

    /** Prints the totals and returns the process exit status. */
    int finish() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        return failed == 0 ? 0 : 1;
    }

    // Every route under test answers JSON with the open CORS header, so the
    // two headers are compared together with the status and the body.
    private static String describe(int status, String body) {
        return status + " application/json cors=*\n" + body;
    }

    private static String describe(Http.Response r) {
        return r.status + " " + r.header("content-type")
                + " cors=" + r.header("access-control-allow-origin") + "\n" + r.body;
    }

    private void pass(String name) {
        passed++;
        System.out.println("  ok    " + name);
    }

    private void fail(String name, String expected, String actual) {
        failed++;
        System.out.println("  FAIL  " + name);
        System.out.println("        expected: " + clip(expected));
        System.out.println("        actual:   " + clip(actual));
    }

    private static String clip(String s) {
        String oneLine = s.replace("\n", "\\n");
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "... (" + s.length() + " chars)";
    }
}

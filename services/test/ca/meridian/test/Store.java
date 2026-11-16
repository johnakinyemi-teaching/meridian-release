package ca.meridian.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A throwaway SQLite store in its own temporary directory.
 *
 * Every store is built with the sqlite3 command line tool, the same way
 * the service reaches it, so the tests add no driver of their own.
 */
final class Store {

    private static final List<Path> CREATED = Collections.synchronizedList(new ArrayList<>());

    private final Path file;

    private Store(Path file) {
        this.file = file;
    }

    /** A fresh store holding the rows in test/fixture.sql. */
    static Store fromFixture(Path testDir) throws IOException {
        Store s = new Store(newDir().resolve("meridian.db"));
        Result r = sqlite(testDir.resolve("fixture.sql").toFile(), s.path());
        if (r.exit != 0) {
            throw new IOException("could not build the fixture store: " + r.output);
        }
        return s;
    }

    /** A store file with no tables in it. */
    static Store withNoTables() throws IOException {
        Store s = new Store(newDir().resolve("meridian.db"));
        Files.createFile(s.file);
        return s;
    }

    /** A file at the store path that is not an SQLite database. */
    static Store notADatabase() throws IOException {
        Store s = new Store(newDir().resolve("meridian.db"));
        byte[] text = new byte[512];
        Arrays.fill(text, (byte) 'x');
        Files.write(s.file, text);
        return s;
    }

    /** A directory where the store file should be. */
    static Store aDirectory() throws IOException {
        Store s = new Store(newDir().resolve("meridian.db"));
        Files.createDirectory(s.file);
        return s;
    }

    /** An independent copy, for tests that are expected to damage it. */
    Store copy() throws IOException {
        Store s = new Store(newDir().resolve("meridian.db"));
        Files.copy(file, s.file);
        return s;
    }

    String path() {
        return file.toString();
    }

    /** Runs one statement and returns what sqlite3 printed, trimmed. */
    String sql(String statement) throws IOException {
        Result r = sqlite(null, path(), statement);
        if (r.exit != 0) {
            throw new IOException("sqlite3 exited " + r.exit + " for: " + statement + "\n" + r.output);
        }
        return r.output.trim();
    }

    /**
     * What "sqlite3 -json" prints for a query on this store, tidied the way
     * Db has always tidied it: trimmed, and [] when there are no rows.
     */
    String json(String statement) throws IOException {
        Result r = sqlite(null, "-json", path(), statement);
        if (r.exit != 0) {
            throw new IOException("sqlite3 exited " + r.exit + " for: " + statement + "\n" + r.output);
        }
        String printed = r.output.trim();
        return printed.isEmpty() ? "[]" : printed;
    }

    int count(String table) throws IOException {
        return Integer.parseInt(sql("SELECT COUNT(*) FROM " + table));
    }

    /**
     * Holds an exclusive lock on the store from a second sqlite3 process
     * until the returned handle is closed.
     */
    Lock lockExclusively() throws IOException {
        return new Lock(this);
    }

    static final class Lock implements AutoCloseable {
        private final Process holder;
        private final OutputStream toHolder;

        private Lock(Store store) throws IOException {
            ProcessBuilder pb = new ProcessBuilder("sqlite3", store.path());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            holder = pb.start();
            toHolder = holder.getOutputStream();
            // The timeout makes the holder wait out a reader that is mid-read
            // instead of failing its BEGIN, so the probe below, which is such
            // a reader, cannot knock the lock out before it is taken.
            toHolder.write(".timeout 10000\nBEGIN EXCLUSIVE;\n".getBytes(StandardCharsets.UTF_8));
            toHolder.flush();
            // Wait until a reader is really shut out (sqlite3 exits 5,
            // SQLITE_BUSY) instead of sleeping and hoping.
            for (int attempt = 0; attempt < 200; attempt++) {
                if (sqlite(null, store.path(), "SELECT 1 FROM sqlite_schema LIMIT 0").exit == 5) {
                    return;
                }
                pause(25);
            }
            close();
            throw new IOException("the exclusive lock was never taken");
        }

        @Override
        public void close() throws IOException {
            try {
                toHolder.write("ROLLBACK;\n".getBytes(StandardCharsets.UTF_8));
                toHolder.close();
            } catch (IOException alreadyGone) {
                // the holder has exited; nothing left to release
            }
            try {
                if (!holder.waitFor(10, TimeUnit.SECONDS)) {
                    holder.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Removes every directory the tests created. */
    static void deleteAll() {
        for (Path dir : new ArrayList<>(CREATED)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException leftBehind) {
                // a temp directory that will not delete is not a test failure
            }
        }
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path newDir() throws IOException {
        Path dir = Files.createTempDirectory("meridian-test");
        CREATED.add(dir);
        return dir;
    }

    private static final class Result {
        final int exit;
        final String output;

        Result(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    /** Runs sqlite3 with the given arguments, optionally feeding it a file. */
    private static Result sqlite(File stdin, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("sqlite3");
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (stdin != null) {
            pb.redirectInput(stdin);
        }
        Process p = pb.start();
        if (stdin == null) {
            p.getOutputStream().close();
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            return new Result(p.waitFor(), out.toString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running sqlite3", ie);
        }
    }
}

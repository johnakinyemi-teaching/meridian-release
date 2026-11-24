package ca.meridian.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the admin tool the way an operator does: as its own java process
 * against a store file. That it is a separate process from the service is
 * the point of half of MRD-247, so the tests must not call it in-process.
 */
final class Admin {

    /** What one run of the tool did. */
    static final class Run {
        final int exit;
        final String out;
        final String err;

        Run(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    private Admin() {
    }

    static Run run(Store store, String... args) throws IOException {
        return run(store, null, args);
    }

    /**
     * The same, with the PATH set to one empty directory, so that no
     * sqlite3 program (or any other) can be started by name. The java
     * binary itself is started by its full path.
     */
    static Run runWithNothingOnThePath(Store store, String... args) throws IOException {
        Path empty = Files.createTempDirectory("meridian-empty-path");
        try {
            return run(store, empty.toString(), args);
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    private static Run run(Store store, String path, String[] args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-Dmeridian.db=" + store.path());
        command.add("ca.meridian.admin.AdminCli");
        command.addAll(Arrays.asList(args));

        File errFile = File.createTempFile("meridian-admin", ".err");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // A JVM told to pick up options from the environment says so on
            // stderr, which the checks compare exactly.
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            pb.environment().remove("JDK_JAVA_OPTIONS");
            if (path != null) {
                pb.environment().put("PATH", path);
            }
            pb.redirectError(errFile);
            Process p = pb.start();
            p.getOutputStream().close();
            String out = readAll(p.getInputStream());
            int exit;
            try {
                exit = p.waitFor();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted running the admin tool", ie);
            }
            String err = new String(Files.readAllBytes(errFile.toPath()), StandardCharsets.UTF_8);
            return new Run(exit, out, err);
        } finally {
            errFile.delete();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}

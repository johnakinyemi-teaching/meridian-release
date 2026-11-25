package ca.meridian.db;

import ca.meridian.util.Strings;
import ca.meridian.util.Validate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * A cross-process "this station's summary is out of date" note.
 *
 * The admin tool and the running service are separate JVMs, each with its
 * own {@link ForecastCache} (AdminCli.evict, the ApiServer constructor), so
 * the admin tool cannot clear the server's in-memory cache directly. Both
 * reach the same store file, though, and can leave each other a note beside
 * it: one small file per station under {@code <dbPath>.evict/}, holding an
 * opaque token. {@link #mark} writes a fresh token; the server remembers the
 * token it saw when it loaded a summary, and reloads once the token on disk
 * is a different one. Tokens are compared only for equality, never by age,
 * so a leftover file after the store is regenerated does no harm.
 *
 * Anything that goes wrong reading a token is reported as {@link #UNKNOWN},
 * which the cache treats as "load fresh and do not trust the cache", so a
 * read here can never turn a 200 into a 500.
 *
 * Only ids that pass {@link Validate#isStationId} ever become a file name,
 * so a stray value from a caller cannot write or read outside this one
 * directory.
 */
final class EvictionMarks {

    /** The token when it cannot be read. The cache never caches against it. */
    static final String UNKNOWN = "\u0000unknown";

    /** The token when there is no note yet: the normal state before any evict. */
    static final String NONE = "";

    private final Path dir;

    EvictionMarks(String dbPath) {
        Path d;
        try {
            d = dbPath == null ? null : Paths.get(dbPath + ".evict");
        } catch (RuntimeException unusablePath) {
            d = null;
        }
        this.dir = d;
    }

    static boolean isUnknown(String token) {
        return UNKNOWN.equals(token);
    }

    /** The station's current token: NONE when there is no note, UNKNOWN on trouble. */
    String token(String station) {
        if (dir == null) {
            return UNKNOWN;
        }
        String id = asStationId(station);
        if (id == null) {
            // Not a station id: no note is ever written for it, so there is
            // nothing to be stale against.
            return NONE;
        }
        Path f = dir.resolve(id);
        if (Files.notExists(f)) {
            // The normal state: no note has been left for this station.
            // notExists, not !exists: when it cannot be told whether the
            // note is there (no permission to look), fall through and let
            // the read below fail into UNKNOWN.
            return NONE;
        }
        try {
            byte[] b = Files.readAllBytes(f);
            int n = Math.min(b.length, 64);
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException trouble) {
            // The note exists but cannot be read (a directory in its place,
            // a permission problem). Do not trust the cache; never fail.
            return UNKNOWN;
        }
    }

    /** Writes a fresh token for a station so other processes reload it. */
    void mark(String station) throws IOException {
        if (dir == null) {
            throw new IOException("no eviction directory for this store path");
        }
        String id = asStationId(station);
        if (id == null) {
            // Not a station id; no server could have it cached, so there is
            // nothing to signal. Leave the directory untouched.
            return;
        }
        Files.createDirectories(dir);
        makeReadableByOthers(dir, "rwxr-xr-x");
        keepOutOfGit();
        Path tmp = dir.resolve("." + id + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            makeReadableByOthers(tmp, "rw-r--r--");
            try {
                Files.move(tmp, dir.resolve(id),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | UnsupportedOperationException noAtomic) {
                Files.move(tmp, dir.resolve(id), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String asStationId(String station) {
        String s = Strings.normStation(station);
        return (s != null && Validate.isStationId(s)) ? s : null;
    }

    /**
     * A note written beside an in-tree store would otherwise show up as an
     * untracked file that a stray "git add -A" could commit. A .gitignore of
     * "*" in the directory keeps the whole directory out of every commit.
     */
    private void keepOutOfGit() {
        try {
            Path gi = dir.resolve(".gitignore");
            if (!Files.exists(gi)) {
                Files.write(gi, "*\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException bestEffort) {
            // A missing .gitignore is not a reason to fail an eviction.
        }
    }

    /**
     * The admin tool may run as a different user from the server, with a
     * stricter umask. The server only ever reads here, so both the
     * directory and the note are opened up for reading.
     */
    private static void makeReadableByOthers(Path p, String permissions) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows, a filesystem without POSIX permissions, or a
            // directory someone else owns: what is there stands. If the
            // server then cannot read the note, token() answers UNKNOWN
            // and the summary is loaded fresh.
        }
    }
}

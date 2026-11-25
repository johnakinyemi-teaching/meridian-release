# services/lib

Third-party code the service layer needs at run time. One file.

## sqlite-jdbc-3.46.1.3.jar

The SQLite JDBC driver, used by `ca.meridian.db.Db` to read the store in-process (MRD-77).
Before it, `Db` started the `sqlite3` command line program for every query.

| | |
|---|---|
| Project | https://github.com/xerial/sqlite-jdbc |
| Maven coordinates | `org.xerial:sqlite-jdbc:3.46.1.3` |
| Downloaded from | https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar |
| Size | 14,127,503 bytes |
| SHA-256 | `4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa` |
| SHA-1 (as published by Maven Central) | `6840edacf0868e0466a05bfbb29aaa4916f15869` |
| Licences | Apache License 2.0, and a BSD 2-clause licence for the parts that came from the Zentus driver. Both texts are inside the jar, under `META-INF/maven/org.xerial/sqlite-jdbc/` |
| SQLite inside | 3.46.1 |

The file is the published jar, unmodified. To check it:

```
sha256sum services/lib/sqlite-jdbc-3.46.1.3.jar
```

### Why it is committed, and why it is this big

The build here has no dependency tool and assumes no network: `make all` must work offline, in
the devcontainer and in CI, for all nine teams. So the jar is in the repository and not fetched
during the build. Most of its 14 MB is SQLite compiled for 24 platforms. Meridian uses the two
Linux ones (x86-64 and arm64); the jar is kept whole so that its checksum can be compared with
the published one.

### What it needs at run time

On its first query the driver unpacks SQLite's native library into the temporary directory
(`java.io.tmpdir`, or `-Dorg.sqlite.tmpdir=<dir>` to choose another) and loads it from there.
That directory must be writable and must allow execution (not mounted `noexec`). If it is not,
every query fails with `sqlite3 exited 1`. The file is removed when the JVM exits normally.

It needs no other jar: its logging dependency is optional from this version on.

### Why this version

- It needs nothing else on the class path. Versions 3.43 to 3.45 require `slf4j-api` as well.
- Its SQLite writes a REAL exactly as the devcontainer's `sqlite3` 3.45.1 does, which is what the
  oldest routes have always sent to the consoles. Newer SQLite versions spell the same number
  differently (5.951 is `5.95099999999999962` here and `5.950999999999999624` from 3.50), so
  replacing this file can change those routes' answers. A check in `services/test.sh` fails if
  the SQLite in use is not 3.46.1.

### How it gets on the class path

`services/build.sh` unpacks it into `services/build/classes`, beside the service's own classes.
Nothing that runs `java -cp services/build/classes ...` had to change.

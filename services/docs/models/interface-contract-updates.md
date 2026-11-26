# Interface contract: the entries the A4 change made wrong

Model: "Real Interface Contract of Services / API", section 3c of Team Relay's A2 report.
Author of the model and of this update: Jason Klutse.

The A4 handout asks each team to update "any interface-contract or dragons-register entry it
makes wrong", and not to redo the A2 model set. So only the entries the change affects are
listed here; the rest of the contract stands as it was. This file does not change
`docs/CONTRACTS.md`, which is shared between teams and is a different document.

The change is Relay's three A4 tickets: MRD-166 (the station and year are bound values),
MRD-247 (cache eviction works, also from the admin tool) and MRD-77, Java half (`Db` reads the
store in-process through the SQLite JDBC driver). Only MRD-166 and MRD-77 touch this contract.
Java paths are relative to `services/src/ca/meridian/`. Line numbers are for the branch
`team/relay` at `7dd5eae` (Nov 24, 2026); "before" means `main` before #24 was merged.

## Entries that changed

| # | Entry as A2 has it | What is true now | How I checked |
|---|---|---|---|
| 1 | Section 6: "Requires: a JDK and sqlite3 (with -json) on PATH (db/Db.java:30)." | **No longer true for the service.** It needs a JDK, the driver `services/lib/sqlite-jdbc-3.46.1.3.jar` (which `services/build.sh` unpacks into `services/build/classes`), and a temporary directory that is writable and allows execution: on its first query the driver unpacks SQLite's native library there (`java.io.tmpdir`, or `-Dorg.sqlite.tmpdir=<dir>`). The `sqlite3` program is no longer used by the service. It is still needed by the services tests and by the collector (`ingest/`). | Read `services/lib/README.md` ("What it needs at run time") and `services/build.sh`. Started the API from `team/relay` on the pipeline store with `PATH` set to an empty directory, so no `sqlite3` could be found: `/api/stations` and `/api/forecast?station=GUELPH` both answered 200 with their rows. The check "with no sqlite3 on the PATH the admin tool still works" (`StoreAccessTest.java:272`) passes. |
| 2 | Section 9, testing: `GET /api/forecast?station=O'X` → `500 {"error":"sqlite3 exited 1"}`. | **Now `200 []`** (MRD-166). The station is bound to `?1` (`api/ForecastHandler.java:55`, `:62`), so the quote is part of the station's name, and no station is called `O'X`. Still not validated or decoded, so the answer is an empty list, not a 400. | Requested it from the running API on `team/relay`: `200 []`. The checks "forecast, unmatched quote GU'ELPH: no such station" and "series, unmatched quote GU'ELPH: no such station" (`ForecastSeriesTest.java`) pin the same thing for another id and pass. |
| 3 | Section 4, the 500 row: when "sqlite3 fails", on all six data routes, message `sqlite3 exited <n>`, at `db/Db.java:50-52`. | **Same status and message, new cause and lines.** A failed query still gives `500 {"error":"sqlite3 exited <n>"}`, but no program exits any more: `<n>` is SQLite's own result code (`db/Db.java:101-102`, `resultCode` at `:133-136`), for example 1 for an error or a store that cannot be opened (`:113-126`), 5 for a locked store, 26 for a file that is not a database. A new cause gives the same 500: the driver cannot load SQLite because the temporary directory is not writable or is `noexec` (`:104-108`, `sqlite3 exited 1`). "When" should now read: the query fails, the store cannot be opened, or the driver cannot load. | Read `Db.java` at the lines given. In the test run, the sections "store with no tables: 500 sqlite3 exited 1", "store file that is not a database: 500 sqlite3 exited 26" and "store locked by another process: 500 sqlite3 exited 5, then recovers" all pass. Not checked by me: the driver-cannot-load case. On `team/relay` it has no test yet (Jenna's #49 adds one), and the `noexec` case is a hand check only. |
| 4 | Section 4 notes: "sqlite3's error text is not returned" (`db/Db.java:31`). | **Still true in effect; the reason moved.** There is no program output any more. The driver's own message is dropped where the failure is turned into an `IOException` (`db/Db.java:101-102`), so only the code reaches the caller. The retry still finds a lock by the text `exited 5` (`db/DbClient.java:75`). | Read `Db.java:101-102` and `DbClient.java:69-76`. |
| 5 | Section 9, found at runtime: "Legacy routes return sqlite3's full-precision floats (5.95099999999999962)". | **Same text, byte for byte, now written inside the service.** `Db` asks the SQLite inside the driver to spell each REAL with `printf('%!.20g', ...)` (`SPELL_REAL`, `db/Db.java:46`, used at `:182`), the format the program used; whole numbers are written as `174.0` (`:180`). | Requested `/api/forecast?station=GUELPH` from the running API on `team/relay`: `"yield_t":5.95099999999999962`. Also seen in `/api/series` (`"sw":95.3299999999999982`). The check "on x86-64 the oracle value 5.951 is spelled as it always was" (`StoreAccessTest.java:302`) is skipped on my machine, which runs the container as aarch64, so I relied on the live answer. |

## Other line references that moved

Of the files the change touched, section 3c cites five: `api/ForecastHandler.java`,
`api/SeriesHandler.java`, `db/Db.java`, `db/DbClient.java` and `util/Env.java`. In `util/Env.java`
only a comment changed, and no line moved. The lines that did move:

| A2 cites | Now | What is there |
|---|---|---|
| `api/ForecastHandler.java:46-54` (outputs) | `:46-58` | the two queries; the station's query now binds `?1` |
| `api/ForecastHandler.java:47, 54` (sorting) | `:48`, `:56` | `ORDER BY stnid, year` and `ORDER BY year` |
| `api/ForecastHandler.java:52-54` (mismatch table: "forecast rows only") | `:54-57` | the station's query |
| `api/SeriesHandler.java:40-42` (outputs, sorting) | `:41-43` | the query, now `WHERE stnid = ?1 AND year = ?2` |
| `api/SeriesHandler.java:40` (mismatch table) | `:41` | the `forecast_daily` query |
| `db/Db.java:30` (requires; the old 500 note) | gone | `Db` no longer starts a program; see entries 1 and 4 |
| `db/Db.java:31` (error text) | `:101-102` | see entry 4 |
| `db/Db.java:50-52` (500 row) | `:101-108` | see entry 3 |
| `db/Db.java:54-55` (empty result, stations output) | `:95-97`, `:169` | `[]` when there are no rows |
| `db/DbClient.java:28` (`meridian.db.retries` default) | `:30` | default retry count |
| `db/DbClient.java:50-74` (retry) | `:52-76` | the retry loop and `isTransient` |

Unchanged and still right: `api/ForecastHandler.java:15, 25-42, 42`, and
`api/SeriesHandler.java:23-38, 24, 27-30, 32-38`. Every other line reference in section 3c is to a
file the change did not touch.

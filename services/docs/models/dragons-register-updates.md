# Dragons register: the entries the A4 change made wrong

Model: "Dragons in Services / API (Risk Register)", section 6 of Team Relay's A2 report.
Author of the model and of this update: Gurmehar Dhingra.

The A4 handout asks each team to update "any interface-contract or dragons-register entry it
makes wrong", and not to redo the A2 model set. So only the entries the change affects are
listed. Entries 2, 4, 5 and 9 are as they were, apart from one line reference: entry 4 cites
`api/ForecastHandler.java:46`, and that line is now 47.

The change is Relay's three A4 tickets: MRD-166 (the station is a bound value), MRD-247 (cache
eviction works, also from the admin tool) and MRD-77, Java half (`Db` reads the store
in-process). Java paths are relative to `services/src/ca/meridian/`. Line numbers are for the
branch `team/relay` on Nov 21, 2026.

## Entries that changed

| # | What A2 said | What is true now | Is it still a hazard? |
|---|---|---|---|
| 1 | "The old routes put the station value into the query unchecked ... a bad value could change data, not only read it." | **No longer true.** The station, and the year on `/api/series`, are passed to the database apart from the SQL text (`api/ForecastHandler.java:55` and `:62`; `api/SeriesHandler.java:42` and `:47`). A quote in the station is looked up as part of the station's name. | Partly. The old routes still do not validate the station or fold its case; that is on purpose, because the ticket's rule was identical responses. The database is still opened read-write (`db/Db.java:113-119`). |
| 3 | The old routes "do not build their answer; they pass on the sqlite3 program's printout almost unchanged". | **The mechanism changed, the hazard did not.** `Db` now writes that text itself, in the exact form the program printed (`db/Db.java:138-170`). | Yes, and it moved. The console still depends on the exact shape. Anyone who edits that method can break the console. Twenty checks hold its text to what `sqlite3 -json` prints (`services/test/.../StoreAccessTest.java`, section "Db.queryJson returns sqlite3's own text"). |
| 6 | "Every data route depends on the sqlite3 program ... without it, every route that reads data fails." | **No longer true for the Java service.** It reads the store inside its own process through the driver in `services/lib/sqlite-jdbc-3.46.1.3.jar`. The collector (`ingest/`) still runs `sqlite3`, and so do the services tests. | Replaced by two new ones; see below. |
| 7 | "The retry works only by reading a number out of an error message ... Reword that message in Db and the retry silently stops working." | **Still true, and kept on purpose.** `Db` still reports a failure as `sqlite3 exited N` (`db/Db.java:101-102`); N is now SQLite's own result code. `DbClient` still looks for `exited 5` (`db/DbClient.java:75`). | Yes, but no longer silent: the checks on a locked store fail if the text or the code changes. |
| 8 | "When a query fails, the reason is thrown away. The sqlite3 program's error text is never read." | **The effect is the same, the reason is not.** There is no program output now. The driver's own message is dropped at `db/Db.java:101-102`, and only the code is reported. | Yes. A failed query still shows only `sqlite3 exited N`. |
| 10 | "There are no automated tests for this code ... no test files under services/." | **No longer true.** `services/test/` holds 248 checks (Nov 21), run by `services/test.sh` and by a CI step named "Services tests". | Less. Two limits to know: CI runs only for pull requests into `main` and pushes to `main`, and the test classes are listed by hand in `RunTests.java`. |

## New hazards the change adds

- **The driver file decides the number text.** The old routes send numbers in SQLite's own
  spelling (5.951 is `5.95099999999999962`). That now comes from the SQLite inside the driver
  file. A newer driver can spell the same number differently and change those routes' answers.
  A check fails if the SQLite in use is not 3.46.1 (`services/lib/README.md`).
- **The service needs a usable temporary folder.** On its first query the driver unpacks a
  native library into the temporary folder, which must be writable and must allow programs to
  run. If it cannot, every data route answers `500 sqlite3 exited 1`; `/api/health` still answers.
- **The admin tool now writes files.** `evict` leaves one small note per station in a folder
  beside the store, `<store path>.evict/`, and the server reads it (`db/EvictionMarks.java`).
  The folder ignores itself in git and is safe to delete. `evict` needs permission to write
  beside the store and exits 1 if it cannot.

## One more statement in my A2 section that is no longer true

Not a register entry, but written by me in the same report (section 5, open tickets, the
MRD-77 paragraph): "No database library has ever been added to the Java layer, the build
compiles the Java with nothing attached to it (services/build.sh:7)." The driver is now in
`services/lib/`, and `services/build.sh` unpacks it beside the service's classes.

That paragraph also listed what a fix would touch. `Db.java` and `build.sh`: yes. `run.sh`:
no, because the driver is unpacked into the folder already on the class path. `DbClient`'s
retry check: no, because `Db` still reports a locked store with the same text. And the old
routes' answers are produced in the exact format the console needs, as it said they must be.

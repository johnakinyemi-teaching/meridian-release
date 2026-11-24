# UML update for Relay's A4 change (task 4B)

Scope, as the handout asks: only the classes the change touches. The A2 model set is not redone.
Class names are relative to `services/src/ca/meridian/`. "Before" is `main` (the
`meridian-evolution` snapshot). "After" is `team/relay` on Nov 21, 2026.

## Diagrams

| File | Shows |
|---|---|
| `services-classes-before.puml` / `.png` | The data-access classes on `main`, with where each of the three defects sits. |
| `services-classes-after.puml` / `.png` | The same classes after the change. Green is new, yellow is changed. |
| `services-evict-sequence.puml` / `.png` | How an admin-tool eviction now reaches the running server (MRD-247). |

The `.puml` file is the editable source. The `.png` is rendered from it:

    java -jar plantuml.jar -tpng services/docs/models/*.puml

The class diagrams use `!pragma layout smetana`, so Graphviz is not needed.

## What changed in the model, by ticket

**MRD-166 (bound query values).** `ForecastHandler` and `SeriesHandler` no longer paste the
station (and year) into the SQL text. They call the new `Db.queryJson(sql, Object... params)`.
The class structure is the same; the dependency on `Db` now carries bound values.

**MRD-247 (cache eviction).**
- `ForecastCache` has one key, `key(station)`, for reads and evictions. The two-argument
  `key(station, year)` is gone. That was the bug: entries were stored under the station,
  but `invalidate` removed a station-and-year key that was never stored.
- New class `EvictionMarks` (package-private). `ForecastCache` owns one. It reads and writes one
  small note per station in `<store path>.evict/`, because the admin tool and the server are
  separate JVMs with separate caches.
- `ForecastCache.invalidate` now throws `IOException` if the note cannot be written.

**MRD-77, Java half (no `sqlite3` program).**
- `Db` opens the store in-process through the SQLite JDBC driver in `services/lib/`. The driver
  is a new external dependency; the `sqlite3` program is no longer one for the service.
- `Db` gains `path()` and the bound-values `queryJson`.
- `Db.queryColumn(sql)` is gone. It had no callers on either branch.
- `Db` still reports a failure as `sqlite3 exited N`, with N now SQLite's result code. This
  is deliberate: `DbClient.isTransient` matches `"exited 5"`.

Unchanged and left out of the diagrams: `DbClient`'s behaviour, `QueryBuilder` (comment only),
`StationCache`, `ResultSetLite`, `Row`, and the other handlers.

## Interface contracts (`docs/CONTRACTS.md`): checked, no edit needed for this change

The three tickets keep every response byte-identical for the requests callers send today,
except the changed answers listed in section 6 of the team's fact sheet and in team PR #24. The
tests hold `forecast`, `series`, `stations` and v2 answers to recorded text. None of the three
tickets changes the HTTP routes, the store schema, or the pipeline order. Examples of answers
that did change:

- A station value containing a quote, such as `'`, now answers 200 `[]` instead of a 500
  (MRD-166: the value is bound, not run as SQL).
- `AdminCli evict` now prints `(entries now 0)` (MRD-247: the entry is actually removed).
- With no `sqlite3` program installed, the routes now answer normally (MRD-77).
- With a temporary folder that is not writable or does not allow execution, every data route
  answers 500 `sqlite3 exited 1` (MRD-77: the driver cannot unpack its native library).

`CONTRACTS.md` does not mention `sqlite3`, the driver, temporary folders, error bodies or the
admin tool's output (searched for `sqlite3`, `jdbc`, `tmp`, `temp`, `500`, `error` and
`entries`: no matches). So none of those changes makes a line in it wrong, and no
`CONTRACTS.md` edit is needed for this change.

Two lines in the HTTP API section were already wrong before this change. They are listed here
so the team can decide, not changed here:

1. `GET /api/forecast?stn=<id>&year=<y>` and `GET /api/series?stn=<id>&year=<y>`: the code,
   and the legacy console (`dashboard/js/api.js`), use the parameter `station`, not `stn`.
   `/api/forecast` does not read `year` at all.
2. `GET /api/series -> reading series`: it returns the model's daily output
   (`doy, sw, et, drain, biom, lai` from `forecast_daily`), not the readings.

`CONTRACTS.md` is shared by every team, and the handout says the team PR's diff should be
the subsystem's change and its tests only. Fixing these two lines is outside that, so it needs the
team's agreement, and a note on the PR if it goes in.

## Dragons register

Already updated by Gurmehar Dhingra in `dragons-register-updates.md` (entries 1, 3, 6, 7, 8
and 10 changed, three new hazards). The line references in it match the code on `team/relay`.

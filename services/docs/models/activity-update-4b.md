# Activity diagram update for Relay's A4 change (task 4B)

Updates Team Relay's A2 activity diagram (Lane A `/api/forecast`, Lane B `/api/summary`, and the
shared steps that read the store). Only what the change affects is marked. Line numbers are for
`team/relay` (Nov 24, 2026), Java paths relative to `services/src/ca/meridian/`.

| File | What it is |
|---|---|
| `services-activity-after.puml` / `.png` | The updated diagram. Yellow marks a changed step. |

The A2 diagram was drawn in a different tool, and its source is not in the repo. This is the same
flow redrawn in PlantUML so the source can live beside the code, not an edit of the original image.
Render it with `plantuml -tpng services/docs/models/services-activity-after.puml`.

## What changed

| Step | Before (A2, main) | Now (team/relay) | Ticket | Where |
|---|---|---|---|---|
| Lane A: put the station into the query | Pasted into the SQL text, no check | Bound to `?1` as typed, no check. A value such as `'` is looked up as a name and the route answers 200 `[]` instead of a 500. | MRD-166 | `api/ForecastHandler.java:54-57` |
| Shared: run the query | Run the `sqlite3` program | Open the store in this process through the SQLite JDBC driver, run a prepared statement | MRD-77 (Java half) | `db/Db.java:73-95` |
| Shared: did it fail? | Program exited non-zero | A `SQLException`, still reported as `sqlite3 exited N` (N is SQLite's result code) | MRD-77 | `db/Db.java:101-102` |
| Shared: new failure path | None | If the driver cannot unpack its native library (temp folder not writable, or not allowed to run programs), every query fails with `sqlite3 exited 1` | MRD-77 | `db/Db.java:104-108` |
| Shared: was the result empty? | Checked after the program ran | No result set, or no rows, gives `[]` | MRD-77 | `db/Db.java:95-96, 169` |

## Same steps, new line numbers (no behaviour change)

| Step | Was | Now |
|---|---|---|
| Lane A: did the request include a station? | `ForecastHandler.java:45` | `:46` |
| Lane A: ask for every station | `:46-47` | `:47-49` |
| Lane A: reply 500 | `:60-61` | `:63-65` |
| Lane A: reply 200, result unchanged | `:65-68` | `:68-74` |
| Lane B: send the query through the retry helper | `DbClient.java:52-54` | `:54-56` |
| Lane B: worth retrying, and tries left? | `DbClient.java:57, 73` | `:59, 75` |
| Lane B: wait, then retry | `DbClient.java:60` | `:62` |

## Unchanged

- Lane A's "read the query without decoding it" and Lane B's whole path up to the retry helper
  (`Cors`, `SummaryHandler`, `Http`, `Validate`): no change to these files, so their line numbers
  are as in A2.
- Lane B does not change behaviour. It still builds its SQL through `QueryBuilder`'s quoting. It
  reaches the same new store step through the unbound `Db.queryJson(sql)`, which calls
  `queryJson(sql, NO_PARAMS)` (`db/Db.java:60-61`).
- The retry still keys off the text `exited 5`. A driver failure (`exited 1`) is not retried,
  so Lane B answers 500 at once.
- MRD-247 (cache eviction) is not on either route. It is on the `/api/v2` path and the admin tool,
  covered in `services-evict-sequence.puml`.

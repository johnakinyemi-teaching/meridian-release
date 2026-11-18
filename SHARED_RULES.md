## Shared Rule(Frontier and Watchtower)
Both consoles show the same "current" forecast for a station.nThey pick it like this:
 ## 1. Greatest year wins. 
 The current run is the row with the highest year. It doesn't matter what order the API returns the rows in, so a backfilled older run that arrives last can't take over.
## 2.Ties go to the latest run. 
If several rows share that year, the one with the latest run_at wins. run_at is parsed as a real timestamp, so 2023-07-01T02:00:00 and 2023-07-01 09:00:00 compare correctly.

## 3.Last returned row is the final fallback.
 If run_at is also equal or can't be parsed, take the last row the API returned.

## 4. Yield shows to 3 decimal places. 
GUELPH 2025 shows as 5.951, matching the oracle.

## What each side changes:
- Frontier (ui-next): 
The greatest-year selection is already in place. We change formatYield from 2 dp to 3 dp. We also change the tie-break from text comparison to timestamp comparison, with the last-row fallback. We update the tests deliberately in the same PR.
- Watchtower (legacy console):
 Compare's "Latest" currently takes the last returned row. It has to move to this rule. Their 3 dp formatting already matches.


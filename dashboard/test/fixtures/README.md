# Console fixtures (Watchtower, MRD-253)

The legacy console's oracle is its own behaviour on real API responses, so the
files in `api/` are what the services actually returned, byte for byte.

## Recorded (do not hand-edit)

`stations.json`, `forecast.<STN>.json`, `series.<STN>.2025.json` for the six
default stations. Recorded on 2026-11-16 from `main` at `230440d` with the
default pipeline, which reported the oracle values unchanged:

- ingest: `accepted=1044 rejected=0 suspect=10`
- forecast: `GUELPH 2025 yield= 5.951 t/ha model=cropmod V2.3`

To re-record (inside the devcontainer, from the repository root):

    sh dashboard/test/fixtures/record.sh

`run_at` is stamped when the forecast runs, so a re-record changes only that
field. No test asserts on `run_at` for the recorded files.

## Crafted (labelled, and why)

The default data never trips a QC rule and has one run per station, so two
cases the ticket is about cannot be reached from recorded data alone:

- `series.EDGE.2025.json` (with a one-run `forecast.EDGE.json` so the Data Quality year picker has a year): seven rows placed exactly on, and just past, each
  bound in `Meridian.Quality.RULES`, plus the biomass-drop edges (exactly 15%,
  just over 15%, a drop to zero, and a rise from zero).
- `forecast.GUELPH.multi-year.json`: the recorded GUELPH 2025 run followed by
  a 2023 run, so the service returns runs out of ascending-year order. It uses
  the same 2023 values as Frontier's `forecast.GUELPH.out-of-order.json`, so
  both consoles are pinned on the same case for MRD-252/253.

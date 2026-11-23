# Archive characterization tests (A4: MRD-246, MRD-143)

Safety net for team Archive's A4 change, recorded on `main` (meridian-evolution) before any Archive code changes.

## Run them

From the repository root, inside the devcontainer:

```
make all
python -m pip install pytest
python -m pytest analytics/tests -q
```

Expected on unchanged `main`: **56 passed**. Without `make all`, the six tests in `test_pipeline_baseline.py` skip and say why.

## What is pinned

| File | Ticket | What it pins |
|---|---|---|
| `test_mrd143_parser_characterization.py` | MRD-143 | The column spans of FORMAT 912. Exact parse of the six real cropmod V2.3 outputs, every GUELPH row included. Overflow handling: an asterisk field drops the day. A field that runs into its neighbour is **misread, not dropped**. Banner, header, blank line and YIELD rules, and the errors raised. |
| `test_mrd246_output_qc_characterization.py` | MRD-246 | What anomaly2 says about each 2025 forecast: all six are "not clean", 0 errors, warnings 4/3/6/5/80/3. The nightly and parallel paths persist a forecast that anomaly2 rates ERROR, never load anomaly2, and return no QC verdict. A suspect rerun overwrites a good stored forecast. The forecast table columns from CONTRACTS.md. |
| `test_pipeline_baseline.py` | both | The real pipeline: ingest `accepted=1044 rejected=0 suspect=10`, run_forecast stdout byte identical, GUELPH 5.951, 6 forecast rows and 1044 daily rows persisted, the 10 suspect readings spread across all six stations, and the committed fixtures still equal to live cropmod output. |

Tests named `test_BUG_...` pin the MRD-246 defect. The MRD-246 change is expected to turn them red. The change commit replaces each one with the new behaviour and says so in the PR.

## How the baselines were recorded

`record_baseline.py` produced the `fixtures/baseline_*` files from the code on `main`. The `cropmod_*_2025.OUT` files were copied out of the scratch directories of a default pipeline run. Do not rerun the script after the change, because that would overwrite the evidence.

## Behaviour found while pinning

1. **No 2025 forecast is "clean" under anomaly2.** A gate on `AnomalyReport.clean` would withhold all six forecasts. Gate on ERROR findings, or the oracle disappears from the store.
2. **CI's oracle check reads stdout, not the store.** A change that stops persisting GUELPH still prints 5.951 and passes CI. `test_what_archive_persisted` is what catches it.
3. **Run-together fields are misread, not dropped** (MRD-143). The parser never checks that the 1X separator columns are blank. The current gfortran writes asterisks instead, so the default path does not reach this. It belongs in the CONTRACTS.md entry.
4. **Line 2 is skipped unchecked.** If the header line is missing, the first day is silently lost and is not counted in `dropped_days`.

# Archive Characterization and Evolution Tests (A4: MRD-143, MRD-246)

This test suite provides the safety net for Team Archive's Assignment 4 changes. The original characterization fixtures were recorded on main before Archive's implementation changes.

- **MRD-143:** Document and test the column-exact cropmod output parser contract without changing production parser behaviour.
- **MRD-246:** Integrate advisory output quality control into the forecast orchestration paths.

## Running the Tests

Run from the repository root inside the course devcontainer:

```
make all
python3 -m venv /tmp/archive-venv
/tmp/archive-venv/bin/python -m pip install pytest
/tmp/archive-venv/bin/python -m pytest analytics/tests -q
```

If virtual environment support is missing in the course container, install it using:

```
sudo apt-get update
sudo apt-get install -y python3-venv
```

The MRD-143 test suite contains 54 tests, all of which passed following the integration and fixture conflict resolution. The combined test count includes the restored MRD-246 tests and may increase as additional changes are integrated.

The live pipeline tests require the compiled model and ingest components. Some tests may skip if their required executables are unavailable.

The complete suite should be run in the Linux devcontainer. Native Windows execution may fail when launching the fake cropmod executable used by the MRD-246 tests.

## Test Coverage

| File | Ticket | Coverage |
|---|---|---|
| `test_mrd143_parser_characterization.py` | MRD-143 | Column spans of FORMAT 912, exact parsing of six recorded cropmod V2.3 outputs, every GUELPH daily row, overflow handling, banner, header, blank line and YIELD rules. |
| `test_mrd143_parser_contract.py` | MRD-143 | Partial numeric fields, malformed records, duplicate and out-of-order days, and YIELD edge cases. |
| `test_mrd143_evolution.py` | MRD-143 | Evolution tests linking the documented parser contract to existing parser behaviour. |
| `test_mrd246_output_qc_characterization.py` | MRD-246 | Historical anomaly2 behaviour, forecast persistence, suspect forecast replacement and output-QC behaviour. |
| `test_mrd246_evolution.py` | MRD-246 | Advisory output-QC integration, serial and parallel execution, configuration options, persistence and failure handling. |
| `test_pipeline_baseline.py` | Both | Live pipeline verification, ingest statistics, forecast oracle, database persistence and comparison against committed fixtures. |

Tests named `test_BUG_...` identify the historical MRD-246 defect. Their assertions and expected outcomes must be interpreted according to the implementation revision being tested.

## Recorded Baselines

The original pipeline characterization recorded:

- Ingest: accepted=1044, rejected=0, suspect=10.
- Default GUELPH 2025 yield: 5.951 t/ha.
- Six persisted forecast rows.
- 1044 persisted daily rows.
- Ten suspect readings distributed across all six stations.

The `record_baseline.py` script produced the `fixtures/baseline_*` files from the original main branch.

The `cropmod_*_2025.OUT` fixtures were captured from the scratch directories of a default pipeline run.

These fixtures are historical characterization evidence. Do not regenerate them merely to make a failing test pass. Any intentional change to the shared numerical oracle must be coordinated, justified and documented.

## Behaviour Identified During Characterization

1. **No recorded 2025 forecast was clean under anomaly2.** All six forecasts contained warnings, with warning counts of 4/3/6/5/80/3 and no errors. Rejecting forecasts solely because `AnomalyReport.clean` is false would withhold all six forecasts. MRD-246 introduces advisory quality control rather than automatically withholding forecasts.

2. **The default CI oracle checks stdout, not database persistence.** A change that prevents GUELPH from being persisted could still print 5.951 and pass the stdout oracle. The live pipeline persistence tests provide additional protection against this failure.

3. **Run-together fields are misread, not dropped.** The parser does not verify that the 1X separator columns are blank. The characterized gfortran behaviour writes asterisks on overflow, so the default execution path does not normally encounter this limitation.

4. **Line 2 is skipped unchecked.** If the header line is missing, the first daily record is silently lost and is not included in dropped_days.

5. **A suspect rerun historically overwrote a good stored forecast.** This behaviour is captured by the original MRD-246 characterization tests.

## Continuous Integration

The dedicated `.github/workflows/archive-parser-contract.yml` workflow provides automated analytics test coverage.

The default Meridian workflow provides the full build and live forecast oracle check.

Fixture-based parser tests do not independently execute the live model, ingest or nightly pipeline. Both fixture-based and live pipeline verification are necessary for release confidence.

## Parser Contract

Known parser behaviours and limitations are documented in `docs/CONTRACTS.md`.

MRD-143 documents and characterizes existing behaviour without modifying the production parser.

## Integration and Recovery

Following the accidental direct merge of MRD-246 through PR #30, the change was reverted through corrective PR #61.

The latest main was subsequently integrated into team/archive while preserving the MRD-143 characterization fixtures and shared test dependencies.

MRD-246 was then reapplied through the archive/MRD-246-restore feature branch to restore its implementation and regression coverage for internal review before the final team release.

The shared README and GUELPH CSV fixture conflicts were resolved while preserving MRD-143 compatibility.
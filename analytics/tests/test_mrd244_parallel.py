"""Characterize parallel forecast persistence at the batch entry point."""

from contextlib import closing
from unittest import TestCase
from unittest.mock import patch

from meridian.aggregate import summarize_result
from meridian.parallel import (
    Modelled,
    ParallelOrchestrator,
    run_targets_parallel,
)
from meridian.results import DailyRow, ForecastResult, StationYear
from meridian.store import Store


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def modelled_forecast(station, year, yield_t):
    """Construct a synthetic Modelled result for persistence tests."""
    result = ForecastResult(
        stnid=station,
        year=year,
        model="cropmod",
        ndays=1,
        yield_t=yield_t,
        rows=[
            DailyRow(
                doy=100,
                sw=yield_t * 10,
                et=2.0,
                drain=0.0,
                biom=20.0,
                lai=1.0,
            )
        ],
    )
    return Modelled(
        target=StationYear(station, year),
        result=result,
        summary=summarize_result(result),
        elapsed_s=0.1,
        deck_days=1,
    )


def _snapshot_store(store, targets):
    """Snapshot the full contract state of the store for Golden Master use.

    Captures all columns mandated by CONTRACTS.md (stnid, year, model,
    ndays, yield_t) from the forecast table, plus the full daily-row
    sequence from forecast_daily for every queried target.  run_at is
    intentionally excluded: the parallel path uses datetime.now() and
    cannot reproduce a fixed timestamp, so it is not part of the Golden
    Master contract snapshot.

    Returns a dict with two keys:
      'summaries' : list of (stnid, year, model, ndays, yield_t) tuples
                    ordered by (stnid, year).
      'daily_rows': dict mapping StationYear -> List[DailyRow].
    """
    summaries = [
        (r.stnid, r.year, r.model, r.ndays, r.yield_t)
        for r in store.list_forecasts()
    ]
    daily_rows = {
        target: store.daily_rows(target.stnid, target.year)
        for target in targets
    }
    return {"summaries": summaries, "daily_rows": daily_rows}


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class ParallelForecastPersistenceTests(TestCase):
    """Characterization and Golden Master tests for parallel persistence."""

    maxDiff = None

    def assert_forecasts_saved_and_reported(
        self, completed, expected, targets=None
    ):
        """Execute parallel runs and assert stored and reported state.

        run_targets_parallel sorts its return value by (stnid, year), so
        expected_summaries is sorted by the same key before comparison to
        avoid order-dependent false failures.
        """
        if targets is None:
            targets = [run.target for run in expected]

        with closing(Store(":memory:")) as store:
            # Supply completed runs in a fixed order without launching cropmod.
            with patch.object(
                ParallelOrchestrator, "prepare", return_value=[]
            ):
                with patch.object(
                    ParallelOrchestrator,
                    "run",
                    return_value=(completed, []),
                ):
                    reported = run_targets_parallel(store, targets)

            actual = {
                "reported": [
                    (r["stnid"], r["year"], r["yield_t"])
                    for r in reported
                ],
                "stored summaries": [
                    (r.stnid, r.year, r.yield_t)
                    for r in store.list_forecasts()
                ],
                "stored daily rows": {
                    target: store.daily_rows(target.stnid, target.year)
                    for target in targets
                },
            }

        # Sort expected to match the (stnid, year) sort applied by
        # run_targets_parallel before building the comparison dicts.
        expected_sorted = sorted(
            expected,
            key=lambda m: (m.target.stnid, m.target.year),
        )
        expected_summaries = [
            (m.target.stnid, m.target.year, m.result.yield_t)
            for m in expected_sorted
        ]
        expected_rows = {target: [] for target in targets}
        for run in expected:
            expected_rows[run.target] = run.result.rows

        self.assertEqual(
            actual,
            {
                "reported": expected_summaries,
                "stored summaries": expected_summaries,
                "stored daily rows": expected_rows,
            },
        )

    # -- Characterization tests --------------------------------------------

    def test_one_station_one_year(self):
        """Single station-year: correctly persists and reports."""
        guelph = modelled_forecast("GUELPH", 2025, 5.75)
        self.assert_forecasts_saved_and_reported([guelph], [guelph])

    def test_two_stations_same_year(self):
        """Different stations: distinct IDs do not collide."""
        guelph = modelled_forecast("GUELPH", 2025, 5.75)
        waterloo = modelled_forecast("WATERLOO", 2025, 6.25)
        self.assert_forecasts_saved_and_reported(
            [waterloo, guelph], [guelph, waterloo]
        )

    def test_one_station_two_years(self):
        """Pin legacy behaviour: last completed year clobbers earlier years.

        The persist() dict keyed only by stnid means whichever year for
        GUELPH completes last overwrites the other in collected{}, so only
        one year reaches save_result.  Both completion orders are tested to
        pin this race-dependent behaviour.  The dropped year must have no
        daily rows in forecast_daily.
        """
        older = modelled_forecast("GUELPH", 2024, 5.25)
        newer = modelled_forecast("GUELPH", 2025, 5.75)
        both_targets = [older.target, newer.target]

        # Case 1: older completed first, newer last -> newer clobbers older.
        # Only newer should be in the store; older must have no daily rows.
        self.assert_forecasts_saved_and_reported(
            completed=[older, newer],
            expected=[newer],
            targets=both_targets,
        )

        # Case 2: newer completed first, older last -> older clobbers newer.
        # Only older should be in the store; newer must have no daily rows.
        self.assert_forecasts_saved_and_reported(
            completed=[newer, older],
            expected=[older],
            targets=both_targets,
        )

    # -- Golden Master test ------------------------------------------------

    def test_golden_master_parity_with_serial(self):
        """Golden Master: parallel output matches serial on all contract fields.

        The serial orchestrator (store.save_result -> save_forecast) is the
        canonical Oracle.  This test captures a full snapshot of the store
        after a serial run -- forecast summary rows (stnid, year, model,
        ndays, yield_t) and forecast_daily rows for every target -- then
        asserts exact parity against the same snapshot taken after the
        parallel path.

        Three contract surfaces are verified:
          1. Stored summaries  -- the forecast table (all CONTRACTS columns).
          2. Stored daily rows -- the forecast_daily table (full series).
          3. Reported output   -- the list of dicts returned to run_forecast.py.

        run_at is excluded from the snapshot: the parallel path uses
        datetime.now() and cannot reproduce the fixed serial timestamp; its
        format is verified separately to confirm it is at least non-empty.
        """
        runs = [
            modelled_forecast("GUELPH", 2025, 5.75),
            modelled_forecast("WATERLOO", 2025, 6.25),
        ]
        targets = [m.target for m in runs]

        # 1. Build the serial Golden Master baseline.
        with closing(Store(":memory:")) as serial_store:
            for m in runs:
                serial_store.save_result(
                    m.result, run_at="2026-11-22T12:00:00"
                )
            golden = _snapshot_store(serial_store, targets)

        # 2. Run the parallel path with the same completed-run set.
        with closing(Store(":memory:")) as parallel_store:
            with patch.object(
                ParallelOrchestrator, "prepare", return_value=[]
            ):
                with patch.object(
                    ParallelOrchestrator,
                    "run",
                    return_value=(runs, []),
                ):
                    reported = run_targets_parallel(
                        parallel_store, targets
                    )
            parallel = _snapshot_store(parallel_store, targets)

        # 3a. Stored summaries must match the Golden Master exactly.
        self.assertEqual(
            parallel["summaries"],
            golden["summaries"],
            "Parallel forecast-table rows differ from serial Golden Master",
        )

        # 3b. Stored daily rows must match the Golden Master exactly.
        self.assertEqual(
            parallel["daily_rows"],
            golden["daily_rows"],
            "Parallel forecast_daily rows differ from serial Golden Master",
        )

        # 3c. Reported list (returned to run_forecast.py) must match the
        # Golden Master summaries on all CONTRACTS columns.
        reported_summaries = [
            (r["stnid"], r["year"], r["model"], r["ndays"], r["yield_t"])
            for r in reported
        ]
        self.assertEqual(
            reported_summaries,
            golden["summaries"],
            "Reported output differs from serial Golden Master",
        )

        # 3d. run_at field must be a non-empty ISO-format string.
        for r in reported:
            self.assertIsInstance(r["run_at"], str)
            self.assertTrue(
                r["run_at"],
                "run_at must be non-empty for %s %s" % (
                    r["stnid"], r["year"]
                ),
            )

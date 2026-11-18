"""Characterize parallel forecast persistence at the batch entry point."""

from contextlib import closing
from unittest import TestCase
from unittest.mock import patch

from meridian.aggregate import summarize_result
from meridian.parallel import Modelled, ParallelOrchestrator, run_targets_parallel
from meridian.results import DailyRow, ForecastResult, StationYear
from meridian.store import Store


def modelled_forecast(station, year, yield_t):
    result = ForecastResult(
        stnid=station,
        year=year,
        model="cropmod",
        ndays=1,
        yield_t=yield_t,
        rows=[DailyRow(doy=100, sw=yield_t * 10, et=2.0, drain=0.0,
                       biom=20.0, lai=1.0)],
    )
    return Modelled(
        target=StationYear(station, year),
        result=result,
        summary=summarize_result(result),
        elapsed_s=0.1,
        deck_days=1,
    )


class ParallelForecastPersistenceTests(TestCase):
    maxDiff = None

    def assert_forecasts_saved_and_reported(self, completed, expected):
        targets = [run.target for run in expected]
        with closing(Store(":memory:")) as store:
            # Supply completed runs in a fixed order without launching cropmod.
            with patch.object(ParallelOrchestrator, "prepare", return_value=[]), patch.object(
                ParallelOrchestrator, "run", return_value=(completed, [])
            ):
                reported = run_targets_parallel(store, targets)

            actual = {
                "reported": [(r["stnid"], r["year"], r["yield_t"]) for r in reported],
                "stored summaries": [(r.stnid, r.year, r.yield_t)
                                     for r in store.list_forecasts()],
                "stored daily rows": {
                    target: store.daily_rows(target.stnid, target.year)
                    for target in targets
                },
            }

        expected_summaries = [(run.target.stnid, run.target.year, run.result.yield_t)
                              for run in expected]
        self.assertEqual(actual, {
            "reported": expected_summaries,
            "stored summaries": expected_summaries,
            "stored daily rows": {run.target: run.result.rows for run in expected},
        })

    def test_one_station_one_year(self):
        guelph = modelled_forecast("GUELPH", 2025, 5.75)
        self.assert_forecasts_saved_and_reported([guelph], [guelph])

    def test_two_stations_same_year(self):
        guelph = modelled_forecast("GUELPH", 2025, 5.75)
        waterloo = modelled_forecast("WATERLOO", 2025, 6.25)
        self.assert_forecasts_saved_and_reported([waterloo, guelph], [guelph, waterloo])

    def test_one_station_two_years(self):
        older = modelled_forecast("GUELPH", 2024, 5.25)
        newer = modelled_forecast("GUELPH", 2025, 5.75)
        for completed in ([older, newer], [newer, older]):
            with self.subTest(last_completed_year=completed[-1].target.year):
                self.assert_forecasts_saved_and_reported(completed, [older, newer])

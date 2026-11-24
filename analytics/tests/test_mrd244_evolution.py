"""Evolution tests for parallel forecast persistence (MRD-244).

The characterization tests in test_mrd244_parallel.py pinned behaviour at
the moment of the fix.  These tests describe the *intended* behaviour
going forward, so the fix keeps holding as parallel.py evolves:

  * every (station, year) a parallel run completes is persisted -- no
    year of a station may overwrite another;
  * what is stored does not depend on the order workers finish in;
  * a failed station-year does not cost the others their forecast, and
    the failure is still raised;
  * the parallel path stores exactly what the serial path stores, for any
    worker count, when driven by the real cropmod binary.
"""

import os
import random
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from contextlib import closing
from unittest import TestCase, skipUnless
from unittest.mock import patch

from meridian import orchestrator
from meridian.aggregate import summarize_result
from meridian.errors import ModelRunError
from meridian.parallel import (
    Modelled,
    ParallelOrchestrator,
    PreparedRun,
    run_all_parallel,
    run_targets_parallel,
)
from meridian.results import DailyRow, ForecastResult, StationYear
from meridian.store import Store


REPO_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..")
)
CROPMOD = os.path.join(REPO_ROOT, "model", "cropmod")
MRD_INGEST = os.path.join(REPO_ROOT, "ingest", "mrd_ingest")
GEN_PACKETS = os.path.join(REPO_ROOT, "tools", "gen_packets.py")

BINARIES_BUILT = os.access(CROPMOD, os.X_OK) and os.access(MRD_INGEST, os.X_OK)


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


def stored_rows(store):
    """All persisted forecast state, excluding run_at.

    run_at is excluded because the serial and parallel paths each stamp
    datetime.now(); it is not part of what must match.
    """
    forecast = [
        tuple(r)
        for r in store.conn.execute(
            "SELECT stnid, year, yield_t, ndays, model FROM forecast"
            " ORDER BY stnid, year"
        )
    ]
    daily = [
        tuple(r)
        for r in store.conn.execute(
            "SELECT stnid, year, doy, sw, et, drain, biom, lai"
            " FROM forecast_daily ORDER BY stnid, year, doy"
        )
    ]
    return {"forecast": forecast, "forecast_daily": daily}


def run_with_completed(completed, targets):
    """Drive run_targets_parallel with a fixed set of completed runs.

    Returns (reported, stored) where stored excludes run_at.
    """
    with closing(Store(":memory:")) as store:
        with patch.object(ParallelOrchestrator, "prepare", return_value=[]):
            with patch.object(
                ParallelOrchestrator, "run", return_value=(completed, [])
            ):
                reported = run_targets_parallel(store, targets)
        return reported, stored_rows(store)


# ---------------------------------------------------------------------------
# Persistence contract (synthetic results, no model binary needed)
# ---------------------------------------------------------------------------

class ParallelPersistenceEvolutionTests(TestCase):
    """Intended persistence behaviour of the parallel path after MRD-244."""

    maxDiff = None

    def test_three_or_more_years_of_one_station_are_all_persisted(self):
        runs = [
            modelled_forecast("GUELPH", 2022, 5.10),
            modelled_forecast("GUELPH", 2023, 5.40),
            modelled_forecast("GUELPH", 2024, 5.25),
            modelled_forecast("GUELPH", 2025, 5.75),
        ]
        reported, stored = run_with_completed(runs, [m.target for m in runs])

        self.assertEqual(
            [(s, y, yt) for (s, y, yt, _n, _m) in stored["forecast"]],
            [
                ("GUELPH", 2022, 5.10),
                ("GUELPH", 2023, 5.40),
                ("GUELPH", 2024, 5.25),
                ("GUELPH", 2025, 5.75),
            ],
        )
        # Each year keeps its own daily series, not another year's.
        self.assertEqual(
            [(s, y, sw) for (s, y, _doy, sw, *_rest) in stored["forecast_daily"]],
            [
                ("GUELPH", 2022, 51.0),
                ("GUELPH", 2023, 54.0),
                ("GUELPH", 2024, 52.5),
                ("GUELPH", 2025, 57.5),
            ],
        )
        self.assertEqual(
            [(r["stnid"], r["year"], r["yield_t"]) for r in reported],
            [(s, y, yt) for (s, y, yt, _n, _m) in stored["forecast"]],
        )

    def test_completion_order_does_not_change_stored_rows(self):
        runs = [
            modelled_forecast(stn, year, 5.0 + i * 0.1 + (year - 2023) * 0.01)
            for i, stn in enumerate(["GUELPH", "WATERLOO", "MILTON"])
            for year in (2023, 2024, 2025)
        ]
        targets = [m.target for m in runs]

        baseline_reported, baseline_stored = run_with_completed(runs, targets)
        self.assertEqual(len(baseline_stored["forecast"]), len(runs))

        shuffled = list(runs)
        random.Random(244).shuffle(shuffled)
        orders = {
            "reversed": list(reversed(runs)),
            "shuffled": shuffled,
            "interleaved by year": sorted(
                runs, key=lambda m: (m.target.year, m.target.stnid)
            ),
        }
        for label, completed in orders.items():
            with self.subTest(order=label):
                reported, stored = run_with_completed(completed, targets)
                self.assertEqual(stored, baseline_stored)
                self.assertEqual(
                    [(r["stnid"], r["year"], r["yield_t"]) for r in reported],
                    [
                        (r["stnid"], r["year"], r["yield_t"])
                        for r in baseline_reported
                    ],
                )

    def test_one_failed_station_year_does_not_drop_the_others(self):
        """Successful runs are persisted, then ModelRunError is raised.

        run_targets_parallel persists every successful Modelled before it
        checks for failures, so a single bad station-year must not cost the
        rest -- including other years of the same station -- their forecast.
        The real thread pool in ParallelOrchestrator.run is exercised; only
        the per-run worker body is replaced.
        """
        good = {
            StationYear("GUELPH", 2023): 5.40,
            StationYear("GUELPH", 2025): 5.75,
            StationYear("WATERLOO", 2024): 6.25,
        }
        bad = StationYear("GUELPH", 2024)
        targets = sorted(list(good) + [bad], key=lambda t: (t.stnid, t.year))

        def fake_run_one(prepared, config, run_at):
            if prepared.target == bad:
                raise ModelRunError("cropmod exited 3: boom",
                                    stnid=bad.stnid, year=bad.year)
            t = prepared.target
            return modelled_forecast(t.stnid, t.year, good[t])

        prepared = [PreparedRun(target=t, spec=None) for t in targets]

        with closing(Store(":memory:")) as store:
            with patch.object(
                ParallelOrchestrator, "prepare", return_value=prepared
            ), patch("meridian.parallel._run_one", side_effect=fake_run_one):
                with self.assertRaises(ModelRunError) as caught:
                    run_targets_parallel(store, targets, max_workers=4)
            stored = stored_rows(store)

        self.assertEqual((caught.exception.stnid, caught.exception.year),
                         (bad.stnid, bad.year))
        self.assertIn("1 failure", str(caught.exception))
        self.assertEqual(
            [(s, y, yt) for (s, y, yt, _n, _m) in stored["forecast"]],
            [
                ("GUELPH", 2023, 5.40),
                ("GUELPH", 2025, 5.75),
                ("WATERLOO", 2024, 6.25),
            ],
        )
        self.assertNotIn(
            ("GUELPH", 2024),
            {(s, y) for (s, y, *_rest) in stored["forecast_daily"]},
        )


# ---------------------------------------------------------------------------
# Real end-to-end: real cropmod, real ingest, real SQLite, no mocks
# ---------------------------------------------------------------------------

@skipUnless(BINARIES_BUILT,
            "model/cropmod and ingest/mrd_ingest must be built (make all)")
class RealParallelMatchesSerialTests(TestCase):
    """Parallel stores exactly what serial stores, on real model runs."""

    maxDiff = None
    EXPECTED_ROWS = 12  # 6 stations x 2 years

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="mrd244-evo-")
        tmp = cls._tmp.name
        spool_2024 = os.path.join(tmp, "spool_2024.bin")
        spool_2025 = os.path.join(tmp, "spool_2025.bin")

        # gen_packets seeds each station by its position in --stations, not
        # by year.  Reversing the 2024 order gives every station different
        # weather (and yield) in each year, so a year mix-up is detectable.
        subprocess.run(
            [sys.executable, GEN_PACKETS, "--year", "2024",
             "--stations", "BRANTFRD,MILTON,CAMBRIDG,WATERLOO,KITCHNER,GUELPH",
             spool_2024],
            check=True, stdout=subprocess.DEVNULL)
        subprocess.run(
            [sys.executable, GEN_PACKETS, "--year", "2025", spool_2025],
            check=True, stdout=subprocess.DEVNULL)

        # One ingested base DB; each run gets its own fresh copy of it.
        cls.base_db = os.path.join(tmp, "base.db")
        for spool in (spool_2024, spool_2025):
            subprocess.run([MRD_INGEST, spool, cls.base_db], check=True,
                           stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)

        serial_db = cls._fresh_db("serial")
        with closing(Store(serial_db)) as store:
            orchestrator.run_all(store)
            cls.serial = stored_rows(store)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    @classmethod
    def _fresh_db(cls, label):
        path = os.path.join(cls._tmp.name, "%s.db" % label)
        shutil.copyfile(cls.base_db, path)
        return path

    def _run_parallel(self, workers):
        db = self._fresh_db("parallel_w%d" % workers)
        with closing(Store(db)) as store:
            run_all_parallel(store, max_workers=workers)
            return stored_rows(store)

    def test_serial_reference_is_meaningful(self):
        """Guard: the reference covers 12 station-years with per-year yields."""
        forecast = self.serial["forecast"]
        self.assertEqual(len(forecast), self.EXPECTED_ROWS)
        by_station = {}
        for stnid, year, yield_t, _n, _m in forecast:
            by_station.setdefault(stnid, {})[year] = yield_t
        for stnid, years in by_station.items():
            with self.subTest(station=stnid):
                self.assertEqual(sorted(years), [2024, 2025])
                self.assertNotEqual(years[2024], years[2025])

    def test_parallel_four_workers_matches_serial(self):
        parallel = self._run_parallel(4)
        self.assertEqual(len(parallel["forecast"]), self.EXPECTED_ROWS)
        self.assertEqual(parallel, self.serial)

    def test_parallel_one_worker_matches_serial(self):
        parallel = self._run_parallel(1)
        self.assertEqual(len(parallel["forecast"]), self.EXPECTED_ROWS)
        self.assertEqual(parallel, self.serial)

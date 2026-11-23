"""Archive's view of the shared A4 oracle, end to end on the real binaries.

Runs the default pipeline exactly as .github/workflows/meridian.yml does
(gen_packets -> mrd_ingest -> run_forecast.py, serial, no flags) in a
temp directory and pins what Archive persists from it:

    ingest:   accepted=1044 rejected=0 suspect=10
    forecast: six 2025 forecasts, GUELPH = 5.951 t/ha (cropmod V2.3)
    store:    6 forecast rows, 1044 forecast_daily rows

The ticket set says: "suspect=10 today. If your change alters what is
persisted, the ticket must say so and your tests must show the new
count."  These are the counts to compare against.

Needs ``make all`` first (model/cropmod and ingest/mrd_ingest).  Skips,
with the reason, when either binary is missing, so it never fakes a pass.
"""
from __future__ import annotations

import os
import re
import sqlite3
import subprocess
import sys

import pytest

from conftest import REPO_ROOT, fixture_path

CROPMOD = os.path.join(REPO_ROOT, "model", "cropmod")
INGEST = os.path.join(REPO_ROOT, "ingest", "mrd_ingest")

pytestmark = pytest.mark.skipif(
    not (os.path.exists(CROPMOD) and os.path.exists(INGEST)),
    reason="needs model/cropmod and ingest/mrd_ingest: run `make all` first")


@pytest.fixture(scope="module")
def pipeline(tmp_path_factory):
    work = tmp_path_factory.mktemp("pipeline")
    spool = str(work / "spool_2025.bin")
    db = str(work / "meridian.db")
    subprocess.run([sys.executable, os.path.join(REPO_ROOT, "tools",
                                                 "gen_packets.py"), spool],
                   check=True, capture_output=True, text=True)
    ingest = subprocess.run([INGEST, spool, db], check=True,
                            capture_output=True, text=True)
    forecast = subprocess.run(
        [sys.executable, os.path.join(REPO_ROOT, "analytics",
                                      "run_forecast.py"), "--db", db],
        check=True, capture_output=True, text=True, cwd=REPO_ROOT)
    return {"db": db, "ingest": ingest.stdout + ingest.stderr,
            "forecast": forecast.stdout}


def test_ingest_counts_match_the_oracle(pipeline):
    m = re.search(r"accepted=(\d+) rejected=(\d+) suspect=(\d+)",
                  pipeline["ingest"])
    assert m, pipeline["ingest"][-400:]
    assert m.groups() == ("1044", "0", "10")


def test_run_forecast_stdout_is_byte_identical_to_baseline(pipeline):
    with open(fixture_path("baseline_run_forecast_2025.txt")) as fh:
        assert pipeline["forecast"] == fh.read()


def test_guelph_oracle_line(pipeline):
    assert re.search(r"GUELPH +2025 +yield= *5\.951", pipeline["forecast"])


def test_what_archive_persisted(pipeline):
    conn = sqlite3.connect(pipeline["db"])
    try:
        forecasts = conn.execute(
            "SELECT stnid, year, yield_t, ndays, model FROM forecast "
            "ORDER BY stnid").fetchall()
        n_daily = conn.execute(
            "SELECT COUNT(*) FROM forecast_daily").fetchone()[0]
        run_ats = [r[0] for r in conn.execute("SELECT run_at FROM forecast")]
    finally:
        conn.close()
    assert forecasts == [
        ("BRANTFRD", 2025, 5.841, 174, "cropmod V2.3"),
        ("CAMBRIDG", 2025, 6.014, 174, "cropmod V2.3"),
        ("GUELPH", 2025, 5.951, 174, "cropmod V2.3"),
        ("KITCHNER", 2025, 6.138, 174, "cropmod V2.3"),
        ("MILTON", 2025, 5.928, 174, "cropmod V2.3"),
        ("WATERLOO", 2025, 5.737, 174, "cropmod V2.3"),
    ]
    assert n_daily == 1044
    # run_at is the local wall clock, ISO 8601 to the second.
    assert all(re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d", t)
               for t in run_ats)


def test_suspect_readings_still_produce_persisted_forecasts(pipeline):
    """The 10 suspect-flagged readings are spread over all six stations,
    and every one of those stations still has a forecast in force."""
    conn = sqlite3.connect(pipeline["db"])
    try:
        suspect = dict(conn.execute(
            "SELECT stnid, COUNT(*) FROM reading WHERE flags & 1 "
            "GROUP BY stnid").fetchall())
        with_forecast = {r[0] for r in conn.execute(
            "SELECT stnid FROM forecast")}
    finally:
        conn.close()
    assert suspect == {"BRANTFRD": 2, "CAMBRIDG": 1, "GUELPH": 1,
                       "KITCHNER": 1, "MILTON": 2, "WATERLOO": 3}
    assert sum(suspect.values()) == 10
    assert set(suspect) <= with_forecast


def test_the_real_model_output_matches_the_committed_fixtures(pipeline):
    """Guards the fixtures: what cropmod writes today is what the parser
    tests were recorded from."""
    sys.path.insert(0, os.path.join(REPO_ROOT, "analytics"))
    from meridian.config import Config
    from meridian.orchestrator import NightshiftRun, build_spec
    from meridian.store import Store

    store = Store(pipeline["db"])
    try:
        for stn in ("GUELPH", "MILTON"):
            with NightshiftRun(build_spec(store, stn, 2025),
                               Config()) as art:
                with open(art.out_path) as fh:
                    live = fh.read()
            with open(fixture_path("cropmod_%s_2025.OUT" % stn)) as fh:
                assert live == fh.read(), stn
    finally:
        store.close()

"""Shared fixtures for the Archive (analytics output) characterization tests.

These tests pin what Meridian's output side does TODAY, on the
meridian-evolution snapshot, before Archive changes anything for A4
(MRD-246 and MRD-143).  A characterization test records current
behaviour, including behaviour we think is wrong; where a test pins a
known defect its docstring says so and names the ticket that will
change it.

Run from the repository root:

    python -m pip install pytest
    python -m pytest analytics/tests -q

Most tests need nothing but Python.  The pipeline baseline tests in
test_pipeline_baseline.py also need the built model and collector
(``make all``); they skip, and say why, when those binaries are missing.
"""
from __future__ import annotations

import os
import sqlite3
import stat
import sys
import textwrap

import pytest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ANALYTICS_DIR = os.path.dirname(TESTS_DIR)
REPO_ROOT = os.path.dirname(ANALYTICS_DIR)
FIXTURES = os.path.join(TESTS_DIR, "fixtures")

# Import the analytics package the same way run_forecast.py does.
if ANALYTICS_DIR not in sys.path:
    sys.path.insert(0, ANALYTICS_DIR)

STATIONS = ["BRANTFRD", "CAMBRIDG", "GUELPH", "KITCHNER", "MILTON",
            "WATERLOO"]

# The collector-owned reading table, as ingest/tsstore.c creates it.
# Archive does not own this schema; it is copied here only so the tests
# can seed a store without running the C collector.
READING_DDL = """
CREATE TABLE IF NOT EXISTS reading (
    stnid TEXT NOT NULL, year INTEGER NOT NULL, doy INTEGER NOT NULL,
    tmax REAL, tmin REAL, rain REAL, srad REAL, rh REAL, wind REAL,
    flags INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (stnid, year, doy))
"""


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


def cropmod_out(stnid: str, year: int = 2025) -> str:
    """Path of a real MERIDIAN.OUT captured from cropmod V2.3 on main."""
    return fixture_path("cropmod_%s_%d.OUT" % (stnid, year))


def read_lines(path: str):
    with open(path, "r") as fh:
        return [ln.rstrip("\n") for ln in fh]


@pytest.fixture
def db_path(tmp_path):
    return str(tmp_path / "meridian.db")


def seed_readings(path: str, stnid: str, year: int,
                  first_doy: int = 100, ndays: int = 174) -> None:
    """Write a plain, QC-clean season of readings for one station-year."""
    conn = sqlite3.connect(path)
    with conn:
        conn.execute(READING_DDL)
        conn.executemany(
            "INSERT INTO reading (stnid, year, doy, tmax, tmin, rain, srad,"
            " rh, wind, flags) VALUES (?,?,?,?,?,?,?,?,?,0)",
            [(stnid, year, d, 24.0, 14.0, 1.0, 18.0, 65.0, 3.0)
             for d in range(first_doy, first_doy + ndays)])
    conn.close()


def write_fake_cropmod(directory, out_text: str) -> str:
    """Create a stand-in for the cropmod binary.

    It ignores MERIDIAN.DAT and writes ``out_text`` to MERIDIAN.OUT in its
    working directory, exactly where the real model writes (MRD-201).
    Lets a test feed the nightly path a suspect output that the real
    model would never produce on the default spool.
    """
    out_file = os.path.join(str(directory), "canned.OUT")
    with open(out_file, "w") as fh:
        fh.write(out_text)
    script = os.path.join(str(directory), "fake_cropmod")
    with open(script, "w") as fh:
        fh.write(textwrap.dedent("""\
            #!%s
            import shutil
            shutil.copy(%r, "MERIDIAN.OUT")
            print(" CROPMOD: ok, (fake)")
            """ % (sys.executable, out_file)))
    os.chmod(script, os.stat(script).st_mode | stat.S_IEXEC)
    return script

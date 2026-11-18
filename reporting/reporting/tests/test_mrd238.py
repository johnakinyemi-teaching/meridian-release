from __future__ import annotations
import sqlite3
from ..db import ReportDB


def _make_db(tmp_path, rows):
    """Create a temporary database and populate it with forecast rows."""
    path = str(tmp_path / "store.db")
    con = sqlite3.connect(path)
    con.execute(
        "CREATE TABLE forecast (stnid TEXT, year INTEGER, run_at TEXT, "
        "yield_t REAL, ndays INTEGER, model TEXT)"
    )
    con.executemany("INSERT INTO forecast VALUES (?,?,?,?,?,?)", rows)
    con.commit()
    con.close()
    return path

ISO_ROWS = [
    ("GUELPH", 2020, "2020-08-15T03:00:00", 7.0, 200, "cropmod-4.2"),
    ("GUELPH", 2021, "2021-08-16T03:00:00", 8.2, 200, "cropmod-4.2"),
]

# The 09:00 timestamp is actually later than 03:00 but the text comparison of the two strings will select the 03:00 timestamp as the MAX(run_at).
MIXED_ROWS = [
    ("GUELPH", 2021, "2021-08-16T03:00:00", 8.2, 200, "cropmod-4.2"),
    ("GUELPH", 2020, "2021-08-16 09:00:00", 7.5, 200, "cropmod-4.3"),
]

# Check that the latest forecast is selected with consistent timestamps.
def test_latest_forecast_iso_only(tmp_path):
    with ReportDB(_make_db(tmp_path, ISO_ROWS)) as db:
        assert db.latest_forecast_for_station("GUELPH").year == 2021

# Check that the latest timestamp is returned with consistent formats.
def test_latest_run_at_iso_only(tmp_path):
    with ReportDB(_make_db(tmp_path, ISO_ROWS)) as db:
        assert db.latest_run_at() == "2021-08-16T03:00:00"

# An unknown station should not have a matching forecast.
def test_latest_forecast_unknown_station_is_none(tmp_path):
    with ReportDB(_make_db(tmp_path, ISO_ROWS)) as db:
        assert db.latest_forecast_for_station("NOWHERE") is None


def test_latest_run_at_empty_store_is_none(tmp_path):
    with ReportDB(_make_db(tmp_path, [])) as db:
        assert db.latest_run_at() is None

# current behaviour, the bug: run_at is compared as text, so the older run is picked because of the mixed format. This test is flipped in the fix commit.
def test_mixed_format_current_behaviour_picks_older_run(tmp_path):
    with ReportDB(_make_db(tmp_path, MIXED_ROWS)) as db:
        assert db.latest_forecast_for_station("GUELPH").model == "cropmod-4.2"
        assert db.latest_run_at() == "2021-08-16T03:00:00"
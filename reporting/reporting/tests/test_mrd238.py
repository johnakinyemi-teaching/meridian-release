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

# MRD-238 fixed: the chronologically newest run gets chosen, no matter the format
def test_mixed_format_picks_chronologically_newest(tmp_path):
    with ReportDB(_make_db(tmp_path, MIXED_ROWS)) as db:
        assert db.latest_forecast_for_station("GUELPH").model == "cropmod-4.3"
        assert db.latest_run_at() == "2021-08-16 09:00:00"

def test_utc_offset_is_respected(tmp_path):
    rows = [
        # 08:00Z. text sorts higher but it is the earlier
        ("GUELPH", 2021, "2021-08-16T08:00:00Z", 8.2, 200, "earlier"),
        # 09:00Z (04:00 at -05:00). text sorts lower but it is the later 
        ("GUELPH", 2020, "2021-08-16T04:00:00-05:00", 7.5, 200, "later"),
    ]
    with ReportDB(_make_db(tmp_path, rows)) as db:
        assert db.latest_forecast_for_station("GUELPH").model == "later"


def test_unparseable_run_at_never_wins(tmp_path):
    rows = [
        ("GUELPH", 2021, "2021-08-16T03:00:00", 8.2, 200, "good"),
        ("GUELPH", 2020, "zzz-garbage", 7.5, 200, "bad"),
    ]
    with ReportDB(_make_db(tmp_path, rows)) as db:
        assert db.latest_forecast_for_station("GUELPH").model == "good"
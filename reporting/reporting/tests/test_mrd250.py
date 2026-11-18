
from __future__ import annotations

from ..db import Forecast
from ..reports.selection import current_forecast, current_run_at


def _forecast(year, run_at, model):
    return Forecast("GUELPH", year, run_at, 7.0, 200, model)


ISO_ROWS = [
    _forecast(2020, "2020-08-15T03:00:00", "cropmod-4.1"),
    _forecast(2021, "2021-08-16T03:00:00", "cropmod-4.2"),
]

MIXED_ROWS = [
    _forecast(2021, "2021-08-16T03:00:00", "cropmod-4.2"),
    _forecast(2020, "2021-08-16 09:00:00", "cropmod-4.3"),
]


# Check that the latest forecast is selected with ISO timestamps.
def test_current_forecast_iso_only():
    assert current_forecast(ISO_ROWS).year == 2021


# Check that the latest timestamp is returned.
def test_current_run_at_iso_only():
    assert current_run_at(ISO_ROWS) == "2021-08-16T03:00:00"


# An empty list should return None.
def test_current_forecast_empty():
    assert current_forecast([]) is None


# When timestamps match, the newer crop year is selected.
def test_current_forecast_same_timestamp():
    rows = [
        _forecast(2020, "2021-08-16T03:00:00", "cropmod-4.1"),
        _forecast(2021, "2021-08-16T03:00:00", "cropmod-4.2"),
    ]
    assert current_forecast(rows).year == 2021


# Current bug: text comparison picks the older run.
def test_mixed_format_current_behaviour():
    assert current_forecast(MIXED_ROWS).model == 'cropmod-4.2'
    assert current_run_at(MIXED_ROWS) == "2021-08-16T03:00:00"


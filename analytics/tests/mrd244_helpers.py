"""Shared helpers for the MRD-244 tests."""

from meridian.aggregate import summarize_result
from meridian.parallel import Modelled
from meridian.results import DailyRow, ForecastResult, StationYear


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
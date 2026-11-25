"""Run-selection helpers: which forecast run is "current".

Several reports have to answer the same small question in the same small
way: given the forecast rows on file for a station (at most one per crop
year, per the store schema), which one is the *current* run -- the most
recently computed, the figure "in force"?  The station report has asked
this since 2021 via ``db.latest_forecast_for_station``; the trend report
added in this cycle asks it too.  Rather than let each report grow its
own copy of the ordering rule -- which is how the writer accumulated
subtly different answers in the past -- the rule lives here, once, and
the reports call it.

The ordering key is a forecast's ``run_at``: the timestamp the analytics
job stamps when it writes the row.  The newest ``run_at`` is the current
run.  Ties (two rows stamped at the same instant, which a same-batch
backfill can produce) are broken by crop year, newest year first, so the
selection is deterministic run to run.

    New in this cycle.  Centralises the per-report run selection that had
    been duplicated into station_report and (via SQL) into db.py.

MRD-250: Chronological comparison
---------------------------------
``run_at`` timestamps are normalised to canonical ISO-8601 text representation
(see ``_recency_key``) so that time order and comparison key order are
identical across both standard analytics output and off-format manual backfills
(e.g. ``08/16/2021 06:00``).
"""
from __future__ import annotations

import datetime as _dt
from typing import Dict, List, Optional, Sequence

from ..db import Forecast

_COMMON_FORMATS = (
    # Month-first slashes (common North American / backfill, e.g. 08/16/2021 06:00)
    "%m/%d/%Y %H:%M:%S",
    "%m/%d/%Y %H:%M",
    "%m/%d/%Y",
    # Day-first slashes (UK / Canadian / international, e.g. 16/08/2021 06:00)
    "%d/%m/%Y %H:%M:%S",
    "%d/%m/%Y %H:%M",
    "%d/%m/%Y",
    # Year-first slashes
    "%Y/%m/%d %H:%M:%S",
    "%Y/%m/%d %H:%M",
    "%Y/%m/%d",
    # Hyphenated non-ISO variants
    "%d-%m-%Y %H:%M:%S",
    "%d-%m-%Y %H:%M",
    "%d-%m-%Y",
    "%m-%d-%Y %H:%M:%S",
    "%m-%d-%Y %H:%M",
    "%m-%d-%Y",
    # Textual month names
    "%d-%b-%Y %H:%M:%S",
    "%d-%b-%Y %H:%M",
    "%d-%b-%Y",
    "%b %d, %Y %H:%M:%S",
    "%b %d, %Y %H:%M",
    "%b %d, %Y",
    "%d %b %Y %H:%M:%S",
    "%d %b %Y %H:%M",
    "%d %b %Y",
    # Dot-separated
    "%Y.%m.%d %H:%M:%S",
    "%Y.%m.%d %H:%M",
    "%Y.%m.%d",
    "%d.%m.%Y %H:%M:%S",
    "%d.%m.%Y %H:%M",
    "%d.%m.%Y",
    # 2-digit year variants
    "%m/%d/%y %H:%M:%S",
    "%m/%d/%y %H:%M",
    "%m/%d/%y",
    "%d/%m/%y %H:%M:%S",
    "%d/%m/%y %H:%M",
    "%d/%m/%y",
)


def _parse_timestamp(s: str) -> Optional[_dt.datetime]:
    """Parse a timestamp string into a datetime object."""
    candidate = s
    if candidate.endswith(("Z", "z")):
        candidate = candidate[:-1] + "+00:00"
    try:
        return _dt.datetime.fromisoformat(candidate)
    except ValueError:
        pass

    for fmt in _COMMON_FORMATS:
        try:
            return _dt.datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def _recency_key(run_at: Optional[str]) -> str:
    """Comparison key for ordering runs by recency.

    Normalises ``run_at`` into a canonical ISO-8601 string so that text
    ordering matches chronological time ordering, resolving the MRD-250
    hazard where off-format backfills (e.g. '08/16/2021 06:00') sorted
    before ISO stamps ('2021-08-16T06:00:00').
    """
    if not run_at:
        return ""
    s = run_at.strip()
    if not s:
        return ""

    dt = _parse_timestamp(s)
    if dt is None:
        return s

    if dt.tzinfo is not None:
        dt = dt.astimezone(_dt.timezone.utc).replace(tzinfo=None)

    if dt.microsecond:
        return dt.strftime("%Y-%m-%dT%H:%M:%S.%f")
    return dt.strftime("%Y-%m-%dT%H:%M:%S")


def current_forecast(forecasts: Sequence[Forecast]) -> Optional[Forecast]:
    """The current (most recently run) forecast from a set of rows.

    ``forecasts`` is typically every year on file for one station.  The
    row with the newest ``run_at`` wins; on a tie the newest crop year
    wins so the choice is deterministic.  Returns None for an empty set.
    """
    chosen: Optional[Forecast] = None
    chosen_key = None
    for f in forecasts:
        key = (_recency_key(f.run_at), f.year)
        if chosen is None or key > chosen_key:
            chosen = f
            chosen_key = key
    return chosen


def current_run_at(forecasts: Sequence[Forecast]) -> Optional[str]:
    """The ``run_at`` of the current forecast, or None for an empty set."""
    cur = current_forecast(forecasts)
    return cur.run_at if cur is not None else None


def order_by_recency(forecasts: Sequence[Forecast]) -> List[Forecast]:
    """All rows ordered newest run first (ties: newest crop year first)."""
    return sorted(
        forecasts,
        key=lambda f: (_recency_key(f.run_at), f.year),
        reverse=True,
    )


def current_by_station(
    forecasts: Sequence[Forecast],
) -> Dict[str, Forecast]:
    """Map each station id to its current forecast.

    Groups a flat list of forecast rows by station and applies
    :func:`current_forecast` within each group.  Handy when a report has
    pulled a wide slice of the store and wants the in-force row per site
    without a query per station.
    """
    groups: Dict[str, List[Forecast]] = {}
    for f in forecasts:
        groups.setdefault(f.stnid, []).append(f)
    return {stn: current_forecast(rows) for stn, rows in groups.items()
            if current_forecast(rows) is not None}

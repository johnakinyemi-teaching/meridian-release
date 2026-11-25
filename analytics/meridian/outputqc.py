"""Advisory output QC for the nightly run (MRD-246).

    -- Archive (output) side --

anomaly2 knows how to spot a suspect forecast but, until MRD-246, nothing
in the nightly path ever called it.  This module is the thin bit of
wiring that does: after a forecast has been persisted, scan it with
anomaly2 and LOG what was found.

It is ADVISORY ONLY, by design:

  * It runs after ``store.save_result``, so what is persisted is exactly
    what was persisted before MRD-246.
  * It never raises.  A finding, or a bug inside anomaly2 itself, becomes
    a log record; it cannot stop the run or cost another station its
    forecast.
  * It has no blocking mode.  On the 2025 data every station carries
    WARNINGs (MILTON alone has 80), so a gate on ``report.clean`` would
    withhold every forecast.  Gating is a separate decision for later.

Logging goes through the ``meridian.outputqc`` logger, never stdout, so
run_forecast.py's stdout (which CI compares byte for byte) is unchanged.
Volume is kept down on purpose: each ERROR finding is logged on its own,
a station-year with only WARNINGs gets a single headline line, and the
individual WARNING/INFO findings go to DEBUG.

The cross-station cohort check (YIELDOUT) is not run here; it needs the
whole year's batch, not one forecast.  ``anomaly2.analyse_all`` still
covers it when run by hand.
"""
from __future__ import annotations

import logging
from typing import Optional

from . import anomaly2
from .anomaly2 import AnomalyReport, AnomalySeverity
from .results import ForecastResult

log = logging.getLogger("meridian.outputqc")


def scan(result: ForecastResult) -> Optional[AnomalyReport]:
    """Scan one just-persisted forecast and log the findings.

    Returns the :class:`AnomalyReport` so a caller can surface it, or
    ``None`` if the scan itself failed.  Never raises.
    """
    try:
        report = anomaly2.analyse_result(result)
    except Exception:  # noqa: BLE001 - advisory: record, never raise
        log.exception("output QC could not scan %s %d; forecast kept, "
                      "run continues", result.stnid, result.year)
        return None

    try:
        _log_report(report)
    except Exception:  # noqa: BLE001 - a logging problem must not stop a run
        pass
    return report


def _log_report(report: AnomalyReport) -> None:
    for a in report.anomalies:
        if a.severity is AnomalySeverity.ERROR:
            log.error("%s", a)
        else:
            log.debug("%s", a)

    if report.errors():
        log.error("output QC: %s -- suspect forecast persisted (advisory)",
                  report.headline())
    elif report.warnings():
        log.warning("output QC: %s", report.headline())
    else:
        log.info("output QC: %s", report.headline())

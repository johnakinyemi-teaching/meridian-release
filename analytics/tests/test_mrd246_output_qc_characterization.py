"""MRD-246 characterization: output QC (anomaly2) is advisory and not run
by the nightly batch, so a suspect forecast is persisted.

Pins, BEFORE the MRD-246 change:
  A. What anomaly2 says about each real 2025 forecast on the default
     data (fixtures/baseline_anomaly2_2025.json).  Today every station
     has WARNINGs and none has an ERROR, so all six are "not clean".
     A change that gates persistence on ``report.clean`` would withhold
     all six forecasts, the GUELPH oracle included.
  B. What anomaly2 flags on a clearly suspect forecast.
  C. The defect itself: the nightly path (orchestrator.run_station_year,
     reached by run_forecast.py without --parallel) persists a forecast
     that anomaly2 rates ERROR, never runs anomaly2, and returns no QC
     verdict to its caller.  The parallel path does the same.

Tests in group C pin the BUG.  The MRD-246 change is expected to turn
them red on purpose; the change commit should replace each one with the
new behaviour the ticket asks for, and say so in the PR.
"""
from __future__ import annotations

import json
import logging
import sqlite3
import subprocess
import sys

import pytest

from conftest import (ANALYTICS_DIR, STATIONS, cropmod_out, fixture_path,
                      seed_readings, write_fake_cropmod)
from meridian import anomaly2, orchestrator, parse
from meridian.config import Config
from meridian.store import Store

RUN_AT = "2025-10-01T00:00:00"

# A forecast cropmod could plausibly emit that anomaly2 rates ERROR:
# the banner promises 5 days, one SW field overflowed (MRD-143 drop),
# and one day carries a negative ET.
SUSPECT_OUT = "\n".join([
    "MERIDIAN CROPMOD V2.3  STN=GUELPH    YEAR=2025  NDAYS=   5",
    " DOY      SW      ET   DRAIN    BIOM     LAI",
    " 100   95.33   3.669   0.000     0.0   0.000",
    " 101   93.28  -3.851   0.000     0.0   0.000",
    " 102 *******   4.230   0.000     0.0   0.000",
    " 103   87.10   4.100   0.000     2.0   0.010",
    " 104   85.00   4.000   0.000     4.0   0.020",
    "YIELD      9.999",
    "",
])


def _baseline_anomaly2():
    with open(fixture_path("baseline_anomaly2_2025.json")) as fh:
        return json.load(fh)


def _store_with_real_2025(db_path):
    store = Store(db_path)
    for stn in STATIONS:
        store.save_result(parse.parse_file(cropmod_out(stn)), run_at=RUN_AT)
    return store


# ==========================================================================
# A. anomaly2 on the real 2025 forecasts
# ==========================================================================
def test_anomaly2_on_real_2025_matches_recorded_baseline(db_path):
    expected = _baseline_anomaly2()
    store = _store_with_real_2025(db_path)
    try:
        reports = {r.stnid: r for r in anomaly2.analyse_all(store)}
    finally:
        store.close()

    assert sorted(reports) == STATIONS
    for stn in STATIONS:
        rep, exp = reports[stn], expected[stn]
        counts = {}
        for a in rep.anomalies:
            key = "%s:%s" % (a.severity.value, a.code)
            counts[key] = counts.get(key, 0) + 1
        assert rep.clean is exp["clean"], stn
        assert len(rep.errors()) == exp["errors"], stn
        assert len(rep.warnings()) == exp["warnings"], stn
        assert counts == exp["counts"], stn
        assert rep.as_rows() == exp["table"], stn


def test_no_real_2025_forecast_is_clean_and_none_has_an_error(db_path):
    """The fact any MRD-246 gate has to be designed around."""
    store = _store_with_real_2025(db_path)
    try:
        reports = anomaly2.analyse_all(store)
    finally:
        store.close()
    assert [r.clean for r in reports] == [False] * 6
    assert sum(len(r.errors()) for r in reports) == 0
    assert {r.stnid: len(r.warnings()) for r in reports} == {
        "BRANTFRD": 4, "CAMBRIDG": 3, "GUELPH": 6, "KITCHNER": 5,
        "MILTON": 80, "WATERLOO": 3}


def test_guelph_oracle_forecast_is_rated_warning_only(db_path):
    store = _store_with_real_2025(db_path)
    try:
        rep = anomaly2.analyse_stored(store, "GUELPH", 2025)
    finally:
        store.close()
    assert rep.headline() == "GUELPH 2025: 0 error(s), 6 warning(s)"
    assert {a.code for a in rep.warnings()} == {"STUCKSW"}
    assert rep.summary.yield_t == 5.951


def test_cohort_outlier_check_does_not_fire_on_2025(db_path):
    store = _store_with_real_2025(db_path)
    try:
        codes = {a.code for r in anomaly2.analyse_all(store)
                 for a in r.anomalies}
    finally:
        store.close()
    assert "YIELDOUT" not in codes


# ==========================================================================
# B. anomaly2 on a suspect forecast (pure, no store)
# ==========================================================================
def test_anomaly2_rates_the_suspect_output_as_error():
    result = parse.parse_lines(SUSPECT_OUT.splitlines())
    assert result.dropped_days == 1 and result.yield_t == 9.999
    rep = anomaly2.analyse_result(result)
    assert rep.clean is False
    assert sorted(a.code for a in rep.errors()) == ["DROPPED", "NEGET"]
    assert [a.doy for a in rep.errors() if a.code == "NEGET"] == [101]


def test_anomaly2_has_no_side_effects_on_the_store(db_path):
    store = _store_with_real_2025(db_path)
    try:
        before = store.list_forecasts()
        anomaly2.analyse_all(store)
        after = store.list_forecasts()
    finally:
        store.close()
    assert before == after
    tables = {r[0] for r in sqlite3.connect(db_path).execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    assert tables == {"forecast", "forecast_daily"}


# ==========================================================================
# C. The defect: the nightly path persists a suspect forecast
# ==========================================================================
@pytest.fixture
def suspect_run(tmp_path, db_path):
    """Run the real nightly path against a cropmod that emits SUSPECT_OUT."""
    seed_readings(db_path, "GUELPH", 2025)
    fake = write_fake_cropmod(tmp_path, SUSPECT_OUT)
    config = Config(model_binary=fake, scratch_root=str(tmp_path))
    store = Store(db_path)
    try:
        legacy = orchestrator.run_station_year(store, "GUELPH", 2025,
                                               config=config)
        stored = store.get_forecast("GUELPH", 2025)
        daily = store.daily_rows("GUELPH", 2025)
    finally:
        store.close()
    return legacy, stored, daily


def test_BUG_nightly_path_persists_a_forecast_anomaly2_rates_error(
        suspect_run):
    """MRD-246 pinned: an ERROR-rated forecast is stored as the one in
    force, with its dropped day missing and its negative ET intact."""
    legacy, stored, daily = suspect_run
    assert stored is not None
    assert stored.yield_t == 9.999
    assert stored.ndays == 5
    assert [r.doy for r in daily] == [100, 101, 103, 104]
    assert daily[1].et == -3.851


def test_nightly_result_carries_an_advisory_output_qc_verdict(suspect_run):
    """MRD-246: replaces test_BUG_nightly_result_carries_no_output_qc_verdict.
    The caller now also gets anomaly2's report under 'output_qc'."""
    legacy, _, _ = suspect_run
    assert sorted(legacy) == sorted([
        "model", "stnid", "year", "ndays", "rows", "yield_t", "deck_days",
        "run_at", "result", "summary", "dropped_days", "output_qc"])
    assert legacy["dropped_days"] == 1
    assert legacy["yield_t"] == 9.999
    rep = legacy["output_qc"]
    assert isinstance(rep, anomaly2.AnomalyReport)
    assert sorted(a.code for a in rep.errors()) == ["DROPPED", "NEGET"]


def test_nightly_path_runs_anomaly2_and_logs_its_errors(tmp_path, db_path,
                                                        caplog):
    """MRD-246: replaces test_BUG_nightly_path_never_imports_anomaly2.
    The nightly path now runs anomaly2 on the forecast it persists and
    logs the ERROR findings (advisory: the run is not stopped)."""
    seed_readings(db_path, "GUELPH", 2025)
    fake = write_fake_cropmod(tmp_path, SUSPECT_OUT)
    config = Config(model_binary=fake, scratch_root=str(tmp_path))
    store = Store(db_path)
    try:
        orchestrator.run_station_year(store, "GUELPH", 2025, config=config)
    finally:
        store.close()
    errors = [r.getMessage() for r in caplog.records
              if r.name == "meridian.outputqc" and r.levelno == logging.ERROR]
    assert any("DROPPED [GUELPH 2025]" in m for m in errors)
    assert any("NEGET [GUELPH 2025]" in m and "doy 101" in m
               for m in errors)


def test_BUG_parallel_path_also_persists_the_suspect_forecast(tmp_path,
                                                              db_path):
    from meridian.parallel import run_targets_parallel
    from meridian.results import StationYear

    seed_readings(db_path, "GUELPH", 2025)
    fake = write_fake_cropmod(tmp_path, SUSPECT_OUT)
    config = Config(model_binary=fake, scratch_root=str(tmp_path))
    store = Store(db_path)
    try:
        out = run_targets_parallel(store, [StationYear("GUELPH", 2025)],
                                   config=config)
        stored = store.get_forecast("GUELPH", 2025)
    finally:
        store.close()
    assert [d["yield_t"] for d in out] == [9.999]
    assert stored.yield_t == 9.999


def test_BUG_a_suspect_rerun_replaces_a_good_stored_forecast(tmp_path,
                                                             db_path):
    """INSERT OR REPLACE on (stnid, year): the suspect run overwrites the
    good 5.951 forecast that was in force, and its daily rows replace the
    old ones only where the DOYs overlap."""
    seed_readings(db_path, "GUELPH", 2025)
    store = Store(db_path)
    store.save_result(parse.parse_file(cropmod_out("GUELPH")), run_at=RUN_AT)
    store.close()

    fake = write_fake_cropmod(tmp_path, SUSPECT_OUT)
    config = Config(model_binary=fake, scratch_root=str(tmp_path))
    store = Store(db_path)
    try:
        orchestrator.run_station_year(store, "GUELPH", 2025, config=config)
        stored = store.get_forecast("GUELPH", 2025)
        daily = store.daily_rows("GUELPH", 2025)
    finally:
        store.close()
    assert stored.yield_t == 9.999 and stored.ndays == 5
    # 174 old rows; the suspect run rewrote 100,101,103,104 and left the
    # other 170 (including old doy 102) in place.
    assert len(daily) == 174
    assert daily[1].et == -3.851      # doy 101 now from the suspect run
    assert daily[2].doy == 102 and daily[2].sw == 89.05   # stale, old run


# ==========================================================================
# The store contract MRD-246 must not break (CONTRACTS.md)
# ==========================================================================
def test_forecast_tables_have_exactly_the_contract_columns(db_path):
    Store(db_path).close()
    conn = sqlite3.connect(db_path)
    cols = {t: [r[1] for r in conn.execute("PRAGMA table_info(%s)" % t)]
            for t in ("forecast", "forecast_daily")}
    conn.close()
    assert cols == {
        "forecast": ["stnid", "year", "run_at", "yield_t", "ndays",
                     "model"],
        "forecast_daily": ["stnid", "year", "doy", "sw", "et", "drain",
                           "biom", "lai"],
    }

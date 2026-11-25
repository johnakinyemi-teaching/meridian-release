"""MRD-246 evolution tests: advisory output QC on the nightly path.

Independent verification of the change (Alen T).  Dinil's commit replaced
two of Jasmine's test_BUG_ tests with tests of his own, these cover what
his tests do not, and each one fails if the matching behaviour regresses:

  1. the --parallel path runs and returns output QC too
  2. QC is advisory: persisted rows are identical with QC on and off
  3. the off switch works (Config field and MERIDIAN_OUTPUT_QC)
  4. QC never raises: an anomaly2 crash costs no forecast and no run
  5. logging stays quiet: a warning-only forecast logs ONE warning line
  6. an ERROR forecast is logged but still persisted (advisory, no gate)

Run:  python3 -m pytest analytics/tests/test_mrd246_evolution.py -q
"""
from __future__ import annotations

import logging
import sqlite3

import pytest

from conftest import cropmod_out, seed_readings, write_fake_cropmod
from meridian import anomaly2, orchestrator, outputqc, parse
from meridian.config import Config
from meridian.results import StationYear
from meridian.store import Store
from test_mrd246_output_qc_characterization import SUSPECT_OUT

QC_LOG = "meridian.outputqc"


def _run(tmp_path, db_path, **config_kw):
    #Run the real nightly path against the suspect cropmod output.
    seed_readings(db_path, "GUELPH", 2025)
    fake = write_fake_cropmod(tmp_path, SUSPECT_OUT)
    config = Config(model_binary=fake, scratch_root=str(tmp_path), **config_kw)
    store = Store(db_path)
    try:
        return orchestrator.run_station_year(store, "GUELPH", 2025,
                                             config=config)
    finally:
        store.close()


def _persisted(db_path):
    con = sqlite3.connect(db_path)
    try:
        fc = con.execute("SELECT stnid, year, yield_t, ndays, model " "FROM forecast ORDER BY stnid, year").fetchall()
        daily = con.execute("SELECT stnid, year, doy, sw, et, drain, biom, lai " "FROM forecast_daily ORDER BY stnid, year, doy").fetchall()
    finally:
        con.close()
    return fc, daily


def _qc_records(caplog, level=None):
    return [r for r in caplog.records if r.name == QC_LOG and (level is None or r.levelno == level)]


#1 -- the parallel path
def test_parallel_path_runs_output_qc_and_returns_the_report(tmp_path, db_path, caplog):
    from meridian.parallel import run_targets_parallel
    seed_readings(db_path, "GUELPH", 2025)
    fake = write_fake_cropmod(tmp_path, SUSPECT_OUT)
    config = Config(model_binary=fake, scratch_root=str(tmp_path))
    store = Store(db_path)
    try:
        out = run_targets_parallel(store, [StationYear("GUELPH", 2025)], config=config)
    finally:
        store.close()
    rep = out[0]["output_qc"]
    assert isinstance(rep, anomaly2.AnomalyReport)
    assert sorted(a.code for a in rep.errors()) == ["DROPPED", "NEGET"]
    assert any("NEGET [GUELPH 2025]" in r.getMessage()
               for r in _qc_records(caplog, logging.ERROR))


#2 advisory: nothing persisted changes
def test_persisted_rows_identical_with_output_qc_on_and_off(tmp_path):
    on_dir, off_dir = tmp_path / "on", tmp_path / "off"
    on_dir.mkdir(), off_dir.mkdir()
    on_db, off_db = str(on_dir / "m.db"), str(off_dir / "m.db")
    _run(on_dir, on_db, output_qc_enabled=True)
    _run(off_dir, off_db, output_qc_enabled=False)
    on_fc, on_daily = _persisted(on_db)
    off_fc, off_daily = _persisted(off_db)
    assert on_fc and on_daily
    assert (on_fc, on_daily) == (off_fc, off_daily)


#3 the off switch
def test_disabled_output_qc_returns_none_logs_nothing_and_still_persists(
        tmp_path, db_path, caplog):
    legacy = _run(tmp_path, db_path, output_qc_enabled=False)
    assert legacy["output_qc"] is None
    assert _qc_records(caplog) == []
    fc, _ = _persisted(db_path)
    assert [(r[0], r[1]) for r in fc] == [("GUELPH", 2025)]


def test_output_qc_is_on_by_default_and_env_switch_turns_it_off():
    assert Config().output_qc_enabled is True
    assert Config.from_env({}).output_qc_enabled is True
    assert Config.from_env({"MERIDIAN_OUTPUT_QC": "0"}).output_qc_enabled \
is False


#4 QC never raises
def test_anomaly2_crash_costs_no_forecast_and_does_not_stop_the_run(tmp_path, db_path, caplog, monkeypatch):
    def boom(result):
        raise RuntimeError("anomaly2 exploded")
    monkeypatch.setattr(anomaly2, "analyse_result", boom)
     #Do not raise
    legacy = _run(tmp_path, db_path)
    assert legacy["output_qc"] is None
    fc, _ = _persisted(db_path)
    assert [(r[0], r[1]) for r in fc] == [("GUELPH", 2025)]
    assert any("could not scan GUELPH 2025" in r.getMessage() for r in _qc_records(caplog, logging.ERROR))


#5 logging stays quiet
def test_warning_only_forecast_logs_one_warning_headline(caplog):
    result = parse.parse_file(cropmod_out("GUELPH"))
    caplog.set_level(logging.INFO, logger=QC_LOG)
    report = outputqc.scan(result)
    #Real 2025 GUELPH
    assert not report.errors() and report.warnings()
    assert len(_qc_records(caplog, logging.WARNING)) == 1
    assert _qc_records(caplog, logging.ERROR) == []
    #There are no per warning lines
    assert len(_qc_records(caplog)) == 1


#6 an ERROR forecast is logged, and still persisted (no gate)
def test_error_forecast_is_logged_with_headline_and_still_persisted(tmp_path, db_path, caplog):
    _run(tmp_path, db_path)
    msgs = [r.getMessage() for r in _qc_records(caplog, logging.ERROR)]
    assert any("suspect forecast persisted (advisory)" in m for m in msgs)
    fc, _ = _persisted(db_path)
    assert [(r[0], r[1]) for r in fc] == [("GUELPH", 2025)]
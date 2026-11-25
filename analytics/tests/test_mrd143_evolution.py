"""MRD-143 evolution tests: the written contract must match the parser.

MRD-143 adds no parser code, its deliverable is the contract in
docs/CONTRACTS.md plus tests. Jasmine's and Karthik's tests pin the
parser.  These tests pin the DOCUMENT to the parser (Alen T), so a change
to either one, without the other, turns CI red:

  1. the doc's column table equals parse.COLS
  2. every worked example in the doc behaves exactly as the doc says
  3. the missing header limitation is real
  4. CI really runs every MRD-143 test file

Run:  python3 -m pytest analytics/tests/test_mrd143_evolution.py -q
"""
from __future__ import annotations

import glob
import os
import re

from meridian import parse
from meridian.results import DailyRow

HERE = os.path.dirname(__file__)
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DOC = open(os.path.join(REPO, "docs", "CONTRACTS.md"), encoding="utf-8").read()

BANNER = "MERIDIAN CROPMOD V2.3  STN=GUELPH    YEAR=2025  NDAYS=   2"
ROW = " 150   95.33   3.669   0.000     0.0   0.000"


def _doc_table():
    """Rows of the MRD-143 column table: (field, first, last, a, b)."""
    out = []
    for m in re.finditer(r"^\|\s*(\w+)\s*\|\s*(\d+)\s*[\u2013-]\s*(\d+)\s*\|\s*" r"`\[(\d+):(\d+)\]`", DOC, re.M):
        out.append((m.group(1), int(m.group(2)), int(m.group(3)),
                    int(m.group(4)), int(m.group(5))))
    return out


#1 the column table
def test_contract_column_table_matches_the_parser_spans():
    rows = _doc_table()
    assert [r[0] for r in rows] == ["doy", "sw", "et", "drain", "biom", "lai"]
    assert [(r[3], r[4]) for r in rows] == list(parse.COLS)


def test_contract_one_based_columns_agree_with_its_python_slices():
    for field, first, last, a, b in _doc_table():
        assert (first - 1, last) == (a, b), field


#2 every worked example in the doc is true
def test_contract_example_record_is_44_chars_and_decodes():
    example = re.search(r"```text\n(.*)\n```", DOC).group(1)
    assert example == ROW and len(example) == 44
    assert parse._daily(example) == DailyRow(150, 95.33, 3.669, 0.0, 0.0, 0.0)


def test_contract_truncation_example_still_parses():
    #last 0 of the final 0.000 will be removed
    line = ROW[:-1]
    assert len(line) == 43
    assert parse._daily(line) == DailyRow(150, 95.33, 3.669, 0.0, 0.0, 0.0)


def test_contract_run_together_example_is_misread_as_sw_zero():
    line = " 15010000.00   3.669   0.000     0.0   0.000"
    row = parse._daily(line)
    #Not 10000.00
    assert row is not None and row.sw == 0.0


def test_contract_asterisk_field_is_dropped_and_counted():
    bad = " 150 *******   3.669   0.000     0.0   0.000"
    r = parse.parse_lines([BANNER, "hdr", bad, ROW, "YIELD      5.951"])
    assert r.dropped_days == 1 and r.parsed_days == 1


#3 the missing-header limitation
def test_missing_header_loses_first_day_without_counting_it_as_dropped():
    r = parse.parse_lines([BANNER, ROW, ROW.replace("150", "151"), "YIELD      5.951"])
    #The lost day is not counted
    assert r.dropped_days == 0
    #Day 150 is gone
    assert r.parsed_days == 1
    assert r.ndays == 2 and r.days_match is False


#4 CI really runs these tests
def test_ci_workflow_runs_every_mrd143_test_file():
    wf = open(os.path.join(REPO, ".github", "workflows", "archive-parser-contract.yml")).read()
    #CI must run the whole folder, or name every MRD-143 test file.
    if re.search(r"pytest\s+analytics/tests/?(\s|$)", wf):
        return
    for path in glob.glob(os.path.join(HERE, "test_mrd143_*.py")):
        assert os.path.basename(path) in wf, os.path.basename(path)
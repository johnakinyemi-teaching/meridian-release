"""MRD-143 characterization: the column-exact cropmod output parser.

Pins what analytics/meridian/parse.py does TODAY with MERIDIAN.OUT, so
that writing the contract down in docs/CONTRACTS.md (the MRD-143 change)
describes the real parser and any later edit that breaks it goes red.

Three groups:
  1. Golden: the six real cropmod V2.3 outputs from the default pipeline
     parse to exactly the recorded values (fixtures/baseline_*).
  2. The column contract itself: the exact spans, and what a line does
     when a field overflows (asterisks), runs into its neighbour, is
     truncated, or carries trailing text.
  3. The file-level rules: banner, header line, blank lines, YIELD, and
     the errors raised when any of those are missing.

Where the current behaviour is surprising, the test says so; those
surprises are recorded behaviour, not endorsements.
"""
from __future__ import annotations

import csv
import json

import pytest

from conftest import STATIONS, cropmod_out, fixture_path, read_lines
from meridian import parse
from meridian.errors import ParseError
from meridian.results import DailyRow

BANNER = "MERIDIAN CROPMOD V2.3  STN=GUELPH    YEAR=2025  NDAYS=   3"
HEADER = " DOY      SW      ET   DRAIN    BIOM     LAI"


def _out(*daily, yield_line="YIELD      5.951", banner=BANNER):
    return [banner, HEADER, *daily, yield_line]


def _baseline_parse():
    with open(fixture_path("baseline_parse_2025.json")) as fh:
        return json.load(fh)


# ==========================================================================
# 1. Golden: real cropmod output parses to the recorded values
# ==========================================================================
@pytest.mark.parametrize("stn", STATIONS)
def test_real_output_parses_to_recorded_baseline(stn):
    expected = _baseline_parse()[stn]
    r = parse.parse_file(cropmod_out(stn))
    assert r.stnid == expected["stnid"]
    assert r.year == expected["year"]
    assert r.model == expected["model"]
    assert r.ndays == expected["ndays"]
    assert r.yield_t == expected["yield_t"]
    assert r.parsed_days == expected["parsed_days"]
    assert r.dropped_days == expected["dropped_days"]
    assert r.days_match is expected["days_match"]
    assert list(vars(r.rows[0]).values()) == expected["first_row"]
    assert list(vars(r.rows[-1]).values()) == expected["last_row"]


def test_guelph_2025_oracle_value_and_shape():
    """The shared A4 oracle, as the parser sees it."""
    r = parse.parse_file(cropmod_out("GUELPH"))
    assert r.yield_t == 5.951
    assert r.model == "cropmod V2.3"
    assert (r.ndays, r.parsed_days, r.dropped_days) == (174, 174, 0)
    assert [row.doy for row in r.rows] == list(range(100, 274))


def test_guelph_every_daily_row_is_exact():
    """Every parsed value, field by field, against the recorded CSV."""
    with open(fixture_path("baseline_rows_GUELPH_2025.csv")) as fh:
        expected = [
            DailyRow(doy=int(d["doy"]), sw=float(d["sw"]), et=float(d["et"]),
                     drain=float(d["drain"]), biom=float(d["biom"]),
                     lai=float(d["lai"]))
            for d in csv.DictReader(fh)
        ]
    assert list(parse.parse_file(cropmod_out("GUELPH")).rows) == expected


def test_real_output_is_column_exact_44_chars_per_daily_line():
    """The fixture really is FORMAT 912: every daily record is 44 columns."""
    lines = read_lines(cropmod_out("GUELPH"))
    daily = lines[2:-1]
    assert len(daily) == 174
    assert {len(ln) for ln in daily} == {44}
    assert lines[-1] == "YIELD      5.951"


def test_legacy_dict_shape_used_by_run_forecast():
    d = parse.parse_output(cropmod_out("GUELPH"))
    assert sorted(d) == ["model", "ndays", "rows", "stnid", "year",
                         "yield_t"]
    assert d["yield_t"] == 5.951 and len(d["rows"]) == 174


# ==========================================================================
# 2. The column contract
# ==========================================================================
def test_column_spans_match_format_912():
    # I4,1X,F7.2,1X,F7.3,1X,F7.3,1X,F7.1,1X,F7.3 as 0-based half-open spans
    assert parse.COLS == [(0, 4), (5, 12), (13, 20), (21, 28), (29, 36),
                          (37, 44)]


def test_normal_line_decodes_every_field():
    row = parse._daily(" 150   95.33   3.669   0.000     0.0   0.000")
    assert row == DailyRow(doy=150, sw=95.33, et=3.669, drain=0.0,
                           biom=0.0, lai=0.0)


@pytest.mark.parametrize("line", [
    " 150 *******   3.669   0.000     0.0   0.000",   # SW overflow
    " 150   95.33 *******   0.000     0.0   0.000",   # ET overflow
    " 150   95.33   3.669 *******     0.0   0.000",   # DRAIN overflow
    " 150   95.33   3.669   0.000 *******   0.000",   # BIOM overflow
    " 150   95.33   3.669   0.000     0.0 *******",   # LAI overflow
    "****   95.33   3.669   0.000     0.0   0.000",   # DOY overflow
])
def test_asterisk_overflow_in_any_field_drops_the_day(line):
    """gfortran's real overflow output (checked: F7.2 of 12345.67 prints
    '*******'): the day does not decode and is dropped, not misread."""
    assert parse._daily(line) is None


def test_overflow_drops_one_day_but_still_returns_a_forecast():
    r = parse.parse_lines(_out(
        " 150   95.33   3.669   0.000     0.0   0.000",
        " 151 *******   3.669   0.000     0.0   0.000",
        " 152   95.10   3.600   0.000     1.0   0.010"))
    assert [row.doy for row in r.rows] == [150, 152]
    assert r.dropped_days == 1
    assert r.ndays == 3 and r.parsed_days == 2
    assert r.days_match is False          # the MRD-143 fingerprint
    assert r.yield_t == 5.951             # not fatal: a forecast is produced


def test_field_running_into_its_neighbour_is_MISREAD_not_dropped():
    """SURPRISING current behaviour, recorded for the MRD-143 contract.

    OUTFMT.txt says an overflowing field can run into its neighbour with
    no separating space.  When that happens the slice for each span still
    holds a number, so the day is NOT dropped (as parse.py's comments
    suggest) but decoded to a wrong value.  The parser never checks that
    the 1X separator columns are blank.  The gfortran used today writes
    asterisks instead, so the default pipeline does not reach this.
    """
    # SW 10000.00 is 8 wide and has eaten the separator after DOY.
    row = parse._daily(" 15010000.00   3.669   0.000     0.0   0.000")
    assert row is not None
    assert row.doy == 150
    assert row.sw == 0.0                  # really 10000.00
    # BIOM 12345.6 has eaten the separator after DRAIN.
    row = parse._daily(" 150   95.33   3.669   0.00012345.6   0.000")
    assert row is not None
    assert row.biom == 2345.6             # really 12345.6


def test_whitespace_split_would_misalign_the_same_line():
    """Why the parser slices columns (MRD-143): split() sees 5 tokens."""
    assert len(" 15010000.00   3.669   0.000     0.0   0.000".split()) == 5


def test_text_after_column_44_is_ignored():
    row = parse._daily(" 150   95.33   3.669   0.000     0.0   0.000XYZ")
    assert row == DailyRow(150, 95.33, 3.669, 0.0, 0.0, 0.0)


def test_truncated_line_is_dropped():
    assert parse._daily(" 150   95.33   3.669") is None


def test_separator_columns_are_not_checked():
    """Non-blank text in a 1X column is ignored, not rejected."""
    row = parse._daily(" 150X  95.33X  3.669X  0.000X    0.0X  0.000")
    assert row == DailyRow(150, 95.33, 3.669, 0.0, 0.0, 0.0)


def test_negative_values_are_accepted_by_the_parser():
    """The parser does no plausibility checks; that is anomaly2's job."""
    row = parse._daily(" 150  -12.50  -1.000   0.000    -5.0  -0.100")
    assert row == DailyRow(150, -12.5, -1.0, 0.0, -5.0, -0.1)


# ==========================================================================
# 3. File-level rules
# ==========================================================================
def test_banner_fields_are_read_from_line_one():
    r = parse.parse_lines(_out(" 150   95.33   3.669   0.000     0.0   0.000"))
    assert (r.stnid, r.year, r.ndays, r.model) == ("GUELPH", 2025, 3,
                                                   "cropmod V2.3")


def test_ndays_comes_from_the_banner_not_from_counting_rows():
    r = parse.parse_lines(_out(" 150   95.33   3.669   0.000     0.0   0.000"))
    assert r.ndays == 3 and r.parsed_days == 1 and r.dropped_days == 0


def test_line_two_is_skipped_whatever_it_holds():
    """SURPRISING: line 2 is never checked to be the header.  If the
    header is missing, the first daily record is silently skipped."""
    lines = [BANNER,
             " 150   95.33   3.669   0.000     0.0   0.000",
             " 151   95.10   3.600   0.000     1.0   0.010",
             "YIELD      5.951"]
    r = parse.parse_lines(lines)
    assert [row.doy for row in r.rows] == [151]
    assert r.dropped_days == 0


def test_blank_lines_are_skipped_and_not_counted_as_dropped():
    r = parse.parse_lines(_out(
        " 150   95.33   3.669   0.000     0.0   0.000", "", "   ",
        " 151   95.10   3.600   0.000     1.0   0.010"))
    assert [row.doy for row in r.rows] == [150, 151]
    assert r.dropped_days == 0


def test_lines_after_yield_are_still_parsed_as_days():
    r = parse.parse_lines([BANNER, HEADER, "YIELD      5.951",
                           " 150   95.33   3.669   0.000     0.0   0.000"])
    assert r.yield_t == 5.951 and [row.doy for row in r.rows] == [150]


def test_yield_regex_reads_after_whitespace_not_by_column():
    r = parse.parse_lines(_out(yield_line="YIELD 5.951"))
    assert r.yield_t == 5.951


def test_negative_yield_is_accepted():
    assert parse.parse_lines(_out(yield_line="YIELD     -1.250")).yield_t \
        == -1.25


def test_yield_overflow_raises():
    with pytest.raises(ParseError, match="YIELD field overflowed"):
        parse.parse_lines(_out(yield_line="YIELD **********"))


def test_missing_yield_raises():
    with pytest.raises(ParseError, match="no YIELD record"):
        parse.parse_lines([BANNER, HEADER,
                           " 150   95.33   3.669   0.000     0.0   0.000"])


def test_bad_banner_raises():
    with pytest.raises(ParseError, match="unrecognised cropmod banner"):
        parse.parse_lines(["NOT A BANNER", HEADER, "YIELD      5.951"])


def test_empty_output_raises():
    with pytest.raises(ParseError, match="no output"):
        parse.parse_lines([])


def test_missing_file_raises(tmp_path):
    with pytest.raises(ParseError, match="not found"):
        parse.parse_file(str(tmp_path / "MERIDIAN.OUT"))


def test_run_at_is_passed_through_untouched():
    r = parse.parse_file(cropmod_out("GUELPH"), run_at="not-a-timestamp")
    assert r.run_at == "not-a-timestamp"

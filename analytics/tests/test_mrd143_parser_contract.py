"""Additional MRD-143 contract coverage through the public parser API."""
import pytest

from meridian.errors import ParseError
from meridian.parse import parse_lines
from meridian.results import DailyRow

BANNER = "MERIDIAN CROPMOD V2.3 STN=GUELPH YEAR=2025 NDAYS=1"
ROW = " 150   95.33   3.669   0.000     0.0   0.000"
EXPECTED = DailyRow(150, 95.33, 3.669, 0.0, 0.0, 0.0)


def output(*records):
    return [BANNER, "header", *records]


def test_numeric_partial_final_field_is_accepted():
    # No strict 44-character validation: the last slice is still numeric.
    result = parse_lines(output(ROW[:-1], "YIELD 5.951"))
    assert result.rows == [EXPECTED]
    assert result.dropped_days == 0 and result.days_match


def test_unparseable_record_is_counted_without_losing_valid_neighbours():
    result = parse_lines(output(ROW, "garbage", ROW, "YIELD 5.951"))
    assert result.rows == [EXPECTED, EXPECTED]
    assert result.dropped_days == 1
    assert result.parsed_days == 2 and not result.days_match


def test_duplicate_and_out_of_order_days_are_preserved():
    earlier = " 149" + ROW[4:]
    result = parse_lines(output(ROW, earlier, ROW, "YIELD 5.951"))
    assert [row.doy for row in result.rows] == [150, 149, 150]
    assert result.dropped_days == 0


def test_last_numeric_yield_wins_and_suffix_is_ignored():
    result = parse_lines(output(ROW, "YIELD 1.250", "YIELD 5.951 trailing"))
    assert result.yield_t == 5.951
    assert result.rows == [EXPECTED]
    assert result.dropped_days == 0


@pytest.mark.parametrize("yield_line", [" YIELD 5.951", "YIELD 5", "YIELD +5.951"])
def test_unrecognized_yield_is_not_a_valid_final_record(yield_line):
    with pytest.raises(ParseError, match="no YIELD record"):
        parse_lines(output(ROW, yield_line))


def test_overflow_yield_after_valid_yield_still_fails():
    with pytest.raises(ParseError, match="YIELD field overflowed"):
        parse_lines(output(ROW, "YIELD 5.951", "YIELD **********"))

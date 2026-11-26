#!/bin/sh
# MRD-118 tests: cropmod deck ordering.
# Run after `make all`:  sh model/tests/mrd118/run_mrd118.sh
# ooo_before_fix.golden.OUT records what cropmod printed for the
# out-of-order deck BEFORE the fix (kept as evidence of the defect).
HERE=$(cd "$(dirname "$0")" && pwd)
CROPMOD="$HERE/../../cropmod"
FAIL=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAIL=1; }

# Runs the deck file $1 in a clean temp dir; sets WORK, RC, MSG.
run_deck() {
  WORK=$(mktemp -d)
  cp "$1" "$WORK/MERIDIAN.DAT"
  MSG=$(cd "$WORK" && "$CROPMOD" 2>&1)
  RC=$?
}

# 1. In-order deck: behaviour must be unchanged and byte-identical.
run_deck "$HERE/in-order.dat"
[ "$RC" -eq 0 ] && pass "in-order deck exits 0" \
  || fail "in-order deck exit code $RC"
cmp -s "$WORK/MERIDIAN.OUT" "$HERE/inorder.golden.OUT" \
  && pass "in-order MERIDIAN.OUT identical to golden" \
  || fail "in-order MERIDIAN.OUT differs from golden"
rm -rf "$WORK"

# 2. Out-of-order deck (DOY 10,12,11): now rejected.
run_deck "$HERE/out-of-order.dat"
[ "$RC" -eq 5 ] && pass "out-of-order deck exits 5" \
  || fail "out-of-order deck exit code $RC (expected 5)"
echo "$MSG" | grep -q "deck out of order" \
  && pass "out-of-order deck prints the error message" \
  || fail "no 'deck out of order' message"
[ ! -e "$WORK/MERIDIAN.OUT" ] \
  && pass "out-of-order deck writes no MERIDIAN.OUT" \
  || fail "MERIDIAN.OUT was written for a bad deck"
rm -rf "$WORK"

# 3. Duplicate DOY (10,10): also rejected.
DUP=$(mktemp)
{ head -2 "$HERE/in-order.dat"; sed -n 3p "$HERE/in-order.dat"
  sed -n 3p "$HERE/in-order.dat"; printf '%3d\n' -1; } > "$DUP"
run_deck "$DUP"
[ "$RC" -eq 5 ] && pass "duplicate-DOY deck exits 5" \
  || fail "duplicate-DOY deck exit code $RC (expected 5)"
rm -rf "$WORK" "$DUP"

exit $FAIL
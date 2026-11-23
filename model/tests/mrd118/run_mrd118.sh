#!/bin/sh
# MRD-118 tests: cropmod deck ordering.
# Run after `make all`:  sh model/tests/mrd118/run_mrd118.sh
HERE=$(cd "$(dirname "$0")" && pwd)
CROPMOD="$HERE/../../cropmod"
FAIL=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAIL=1; }

# Runs a deck in a clean temp dir; sets WORK, RC, MSG.
run_deck() {
  WORK=$(mktemp -d)
  cp "$HERE/$1" "$WORK/MERIDIAN.DAT"
  MSG=$(cd "$WORK" && "$CROPMOD" 2>&1)
  RC=$?
}

# In-order deck: output must stay byte-identical.
run_deck in-order.dat
[ "$RC" -eq 0 ] && pass "in-order deck exits 0" || fail "in-order deck exit code $RC"
cmp -s "$WORK/MERIDIAN.OUT" "$HERE/inorder.golden.OUT" \
  && pass "in-order MERIDIAN.OUT identical to golden" \
  || fail "in-order MERIDIAN.OUT differs from golden"
rm -rf "$WORK"

# Out-of-order deck: CURRENT behaviour (the MRD-118 defect), pinned.
run_deck out-of-order.dat
[ "$RC" -eq 0 ] && pass "out-of-order deck currently accepted (exit 0)" \
  || fail "out-of-order deck exit code $RC"
cmp -s "$WORK/MERIDIAN.OUT" "$HERE/ooo_before_fix.golden.OUT" \
  && pass "out-of-order output matches pinned current behaviour" \
  || fail "out-of-order output changed"
rm -rf "$WORK"

exit $FAIL

#!/usr/bin/env bash
# MRD-244 end-to-end check: real cropmod runs, real SQLite store, no mocks.
#
# Builds the binaries, ingests two seasons (2024 + 2025) for six stations,
# runs the serial orchestrator once as the reference, then runs the
# parallel orchestrator with 1..5 workers on fresh copies of the same data
# and diffs every stored forecast / forecast_daily row against serial.
#
# Must be run from the repo root, inside the meridian-dev container (the
# binaries are Linux builds):
#   docker run --rm -v "$PWD:/workspace" -w /workspace meridian-dev \
#       bash -lc 'analytics/tests/check_mrd244_real_parallel.sh'
#
# All generated files go in data/ (gitignored).
set -euo pipefail

EXPECTED_ROWS=12   # 6 stations x 2 years
WORKER_COUNTS=(1 2 3 4 5)

SPOOL_2024=data/mrd244_spool_2024.bin
SPOOL_2025=data/mrd244_spool_2025.bin
SERIAL_DB=data/mrd244_serial.db
SERIAL_DUMP=data/mrd244_serial_dump.txt

DUMP_SQL="SELECT stnid,year,yield_t,ndays,model FROM forecast ORDER BY 1,2; SELECT * FROM forecast_daily ORDER BY 1,2,3;"

# -- 1. build ---------------------------------------------------------------
make all

# -- 2. generate two seasons -----------------------------------------------
# gen_packets seeds each station by its position in --stations (seed =
# 1000 + index), not by year.  If both years used the default station
# order, every station would get identical weather -- and therefore an
# identical yield -- in 2024 and 2025, so a year mix-up (e.g. 2025's
# result stored under 2024) would be invisible.  Reversing the 2024 order
# gives each station different data in each year.
python3 tools/gen_packets.py --year 2024 \
    --stations BRANTFRD,MILTON,CAMBRIDG,WATERLOO,KITCHNER,GUELPH "$SPOOL_2024"
python3 tools/gen_packets.py --year 2025 "$SPOOL_2025"

# Ingest both spools into a brand-new DB.  mrd_ingest's stderr is noisy.
build_db() {
    local db=$1
    rm -f "$db"
    ./ingest/mrd_ingest "$SPOOL_2024" "$db" 2>/dev/null
    ./ingest/mrd_ingest "$SPOOL_2025" "$db" 2>/dev/null
}

# Dump stored results, excluding run_at (it differs per run by design).
dump_db() {
    sqlite3 "$1" "$DUMP_SQL"
}

count_rows() {
    sqlite3 "$1" "SELECT COUNT(*) FROM forecast;"
}

count_station_years() {
    sqlite3 "$1" "SELECT COUNT(*) FROM (SELECT DISTINCT stnid, year FROM forecast);"
}

# -- 3/4. serial reference --------------------------------------------------
rm -f "$SERIAL_DUMP"
build_db "$SERIAL_DB"
python3 analytics/run_forecast.py --db "$SERIAL_DB" >/dev/null
dump_db "$SERIAL_DB" > "$SERIAL_DUMP"

failed=0
report() {
    # report <label> <db> <match|MISMATCH>
    local label=$1 db=$2 verdict=$3
    local rows sy
    rows=$(count_rows "$db")
    sy=$(count_station_years "$db")
    printf '%-12s rows=%-3s station-years=%-3s %s\n' "$label" "$rows" "$sy" "$verdict"
    if [[ "$rows" -ne "$EXPECTED_ROWS" ]]; then
        failed=1
    fi
}

report "serial" "$SERIAL_DB" "reference"

# -- 5. parallel runs, one fresh DB per worker count ------------------------
for n in "${WORKER_COUNTS[@]}"; do
    db=data/mrd244_parallel_w${n}.db
    dump=data/mrd244_parallel_w${n}_dump.txt
    rm -f "$dump"
    build_db "$db"
    python3 analytics/run_forecast.py --db "$db" --parallel --workers "$n" >/dev/null
    dump_db "$db" > "$dump"

    if diff -q "$SERIAL_DUMP" "$dump" >/dev/null; then
        verdict=match
    else
        verdict=MISMATCH
        failed=1
    fi
    report "parallel w=$n" "$db" "$verdict"
done

# -- 6. verdict -------------------------------------------------------------
if [[ "$failed" -ne 0 ]]; then
    echo "FAIL: expected $EXPECTED_ROWS forecast rows per run and parallel == serial"
    exit 1
fi
echo "PASS: all runs stored $EXPECTED_ROWS rows and parallel matches serial"

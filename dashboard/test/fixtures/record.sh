#!/bin/sh
# Re-record the console fixtures from a live local Meridian (Watchtower, MRD-253).
# Run from the repository root inside the devcontainer, after `make all`.
set -e
OUT=dashboard/test/fixtures/api
TMPDIR=$(mktemp -d)
DB="$TMPDIR/meridian.db"
SPOOL="$TMPDIR/spool_2025.bin"
PORT=${MERIDIAN_FIXTURE_PORT:-8081}
API=

cleanup() {
    [ -n "$API" ] && kill "$API" 2>/dev/null || true
    rm -rf "$TMPDIR"
}
trap cleanup EXIT

mkdir -p "$OUT"
python3 tools/gen_packets.py "$SPOOL"
./ingest/mrd_ingest "$SPOOL" "$DB"
python3 analytics/run_forecast.py --db "$DB"

java -cp services/build/classes -Dmeridian.db="$DB" -Dmeridian.port="$PORT" \
    ca.meridian.api.ApiServer &
API=$!
sleep 3

BASE="http://localhost:$PORT"
curl -sf "$BASE/api/stations" > "$OUT/stations.json"
for s in BRANTFRD CAMBRIDG GUELPH KITCHNER MILTON WATERLOO; do
    curl -sf "$BASE/api/forecast?station=$s"       > "$OUT/forecast.$s.json"
    curl -sf "$BASE/api/series?station=$s&year=2025" > "$OUT/series.$s.2025.json"
done
echo "recorded into $OUT"

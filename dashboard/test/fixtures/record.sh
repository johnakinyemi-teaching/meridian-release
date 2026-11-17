#!/bin/sh
# Re-record the console fixtures from a live local Meridian (Watchtower, MRD-253).
# Run from the repository root inside the devcontainer, after `make all`.
set -e
OUT=dashboard/test/fixtures/api
DB=data/meridian.db
PORT=${MERIDIAN_FIXTURE_PORT:-8081}

mkdir -p data "$OUT"
rm -f "$DB"
python3 tools/gen_packets.py data/spool_2025.bin
./ingest/mrd_ingest data/spool_2025.bin "$DB"
python3 analytics/run_forecast.py --db "$DB"

java -cp services/build/classes -Dmeridian.db="$DB" -Dmeridian.port="$PORT" \
     ca.meridian.api.ApiServer &
API=$!
trap 'kill $API 2>/dev/null' EXIT
sleep 3

BASE="http://localhost:$PORT"
curl -sf "$BASE/api/stations" > "$OUT/stations.json"
for s in BRANTFRD CAMBRIDG GUELPH KITCHNER MILTON WATERLOO; do
  curl -sf "$BASE/api/forecast?station=$s"            > "$OUT/forecast.$s.json"
  curl -sf "$BASE/api/series?station=$s&year=2025"    > "$OUT/series.$s.2025.json"
done
echo "recorded into $OUT"

# Meridian inter-subsystem contracts (IMMUTABLE — do not change)

These interfaces are shared across subsystems. Any subsystem may grow internally
but MUST NOT change these, or the pipeline breaks.

## Pipeline
frame(binary) -> ingest(C) -> SQLite reading table -> analytics(Python) writes
deck -> model(cropmod, FORTRAN) -> MERIDIAN.OUT -> analytics parses -> forecast
tables -> services(Java API :8081) -> console(jQuery :8080) & ui-next(React).
reporting(Python) reads forecast tables -> fixed-format reports.

## Binary telemetry frame — 44 bytes, BIG-ENDIAN (packet.h)
offset 0  u32 magic  = 0x4D524431 ("MRD1")
offset 4  char stnid[8]  (space-padded, NOT nul-terminated)
offset 12 u16 year
offset 14 u16 doy
offset 16 i32 value[6]  scaled x100, channels: 0=TMAX 1=TMIN 2=RAIN 3=SRAD 4=RH 5=WIND
offset 40 u16 flags     (0x0001 suspect, 0x0002 manual)
offset 42 u16 crc16     CCITT (poly 0x1021, init 0xFFFF) over bytes 0..41
Stations: GUELPH,KITCHNER,WATERLOO,CAMBRIDG,MILTON,BRANTFRD (all <=8 chars).

## SQLite store schema
reading(stnid TEXT, year INT, doy INT, tmax REAL, tmin REAL, rain REAL,
        srad REAL, rh REAL, wind REAL, flags INT, PRIMARY KEY(stnid,year,doy))
    -- created by the C collector (ingest, tsstore.c)
forecast(stnid TEXT, year INT, run_at TEXT, yield_t REAL, ndays INT, model TEXT,
         PRIMARY KEY(stnid,year))
forecast_daily(stnid,year,doy, sw,et,drain,biom,lai, PRIMARY KEY(stnid,year,doy))
    -- created by analytics (store.py)

## Model input deck MERIDIAN.DAT — column-exact (docs/DECKFMT.txt)
REC1  A8,I4,F8.3     stnid, year, latitude
REC2  F8.2,F8.2      awc(frac), rooting depth(mm)
daily I3,F6.1,F6.1,F6.1,F6.2   doy, tmax, tmin, rain, srad
term  " -1"

## Model output MERIDIAN.OUT — column-exact (docs/OUTFMT.txt)
L1 banner: MERIDIAN CROPMOD <ver>  STN=<id>  YEAR=<yyyy>  NDAYS=<n>
L2 header line
daily I4,1X,F7.2,1X,F7.3,1X,F7.3,1X,F7.1,1X,F7.3  doy,sw,et,drain,biom,lai
last: YIELD <F10.3>


### MRD-143: existing parser behaviour

This records the current contract of `analytics/meridian/parse.py`; it does
not change the producer format, parser, DTOs, or persistence policy.
The producer's daily FORMAT 912 in `model/cropmod.f` has this layout:

| Field | Columns (1-based, inclusive) | Python slice | Format | Unit |
| --- | --- | --- | --- | --- |
| doy | 1–4 | `[0:4]` | I4 | day of year |
| sw | 6–12 | `[5:12]` | F7.2 | mm |
| et | 14–20 | `[13:20]` | F7.3 | mm |
| drain | 22–28 | `[21:28]` | F7.3 | mm |
| biom | 30–36 | `[29:36]` | F7.1 | g m-2 |
| lai | 38–44 | `[37:44]` | F7.3 | leaf area index |

Columns 5, 13, 21, 29 and 37 are the producer's single-space separators.
A normal daily record is 44 characters (excluding its newline), for example:

```text
 150   95.33   3.669   0.000     0.0   0.000
```

**Daily decoding.** Read the fixed slices above, never whitespace-split the
record. The parser converts DOY with `int` and the five other fields with
`float`. It preserves input order and does not validate ranges, sort days,
or reject duplicates. A nonnumeric or asterisk-filled slice drops that
record and increments `dropped_days`; parsing continues. Blank lines do not
increment that count. `ndays` comes from the banner, `parsed_days` is the
number of returned rows, and `days_match` compares those counts. A mismatch
is not fatal. Missing rows need not increment `dropped_days`.

**Producer format versus consumer tolerance.** Separator characters are not
validated; text after column 44 is ignored. There is no exact-length check:
a truncated record can still parse if every slice remains numeric. For
example, removing the last zero from a final `0.000` leaves a valid `0.00`.
A short record with an empty required slice is dropped. Negative values
are accepted; plausibility checks belong outside this parser.

Do not interpret column slicing as recovery of arbitrary overflowed values.
An asterisk field is dropped, but a malformed run-together record such as
` 15010000.00   3.669   0.000     0.0   0.000` is currently accepted with
SW **0.0**, not 10000.00: the first digit occupies an ignored separator.
This known limitation is characterized, not fixed by MRD-143. Consumers
must not infer validity solely from `days_match`.

**File-level rules.** The first line must contain a matching banner; its
version, station, year and NDAYS populate the result. Line two is skipped
without validating its header text. Omitting that header therefore loses
the first daily record without counting it as dropped. Subsequent blank
lines are ignored. YIELD is recognized by `^YIELD\s+(-?\d+\.\d+|\*+)`,
not by the daily column slices. Its number must include digits on both
sides of a decimal point; a negative value is allowed. The match is not
end-anchored: suffix text is ignored. With multiple numeric YIELD records,
the last wins; daily records after YIELD are still read. Any matching
asterisk YIELD raises `ParseError`. Empty output, an invalid banner, a
missing numeric YIELD, and a nonexistent output file also raise `ParseError`.
An unrecognized nonblank line after the header is attempted as a daily
record and counts as dropped if its slices cannot be converted.

**API and migration.** `parse_lines`, `parse_file`, and `parse_artifacts`
return `ForecastResult`; `run_at` is passed through. `parse_output` remains
the legacy dictionary API with `model`, `stnid`, `year`, `ndays`, `rows`,
and `yield_t`. No migration or format change is required. Coordinate future
format changes with model producers and analytics consumers before merging.

**Evidence.** `analytics/tests/test_mrd143_parser_characterization.py` pins
the existing golden outputs and malformed-input behaviour;
`test_mrd143_parser_contract.py` covers additional public-API cases.
Run `python -m pytest analytics/tests -q` inside the devcontainer.
The dedicated `archive-parser-contract.yml` workflow runs these tests on
PRs to `team/archive` and `main`, and pushes to `main`, `team/archive` and
Archive feature branches. This is a parser check, not a replacement for
the full build and pipeline oracle. The recorded GUELPH fixture yields
5.951 t/ha with 174 parsed days; rerun the live pipeline separately.

## HTTP API (Java, :8081) — routes the front-ends depend on
GET /api/health   -> {"status":"ok","version":"4.2.1"}
GET /api/stations -> list of station ids
GET /api/forecast?stn=<id>&year=<y>  -> forecast summary + daily
GET /api/series?stn=<id>&year=<y>    -> reading series
All responses JSON, header Access-Control-Allow-Origin: *. Console :8080, ui-next both call these.

## Determinism
tools/gen_packets.py is seeded (seed 1000+i per station). The whole pipeline
must be deterministic given a fixed spool.

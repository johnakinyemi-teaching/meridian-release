-- The store the services tests run against.
--
-- The schema is the one the pipeline creates (docs/CONTRACTS.md): reading by
-- the C collector, forecast and forecast_daily by analytics. The GUELPH 2025
-- and KITCHNER 2025 numbers are copied from a default pipeline run, so the
-- yield 5.951 is the shared oracle value and comes back from the old routes
-- as 5.95099999999999962, the way sqlite3 prints it. The GUELPH 2024 rows
-- are made up, to give one station two years. MILTON carries the awkward
-- cases: a model name with a double quote, a comma and a backslash, and
-- NULL values.

CREATE TABLE reading ( stnid TEXT NOT NULL, year INTEGER NOT NULL, doy INTEGER NOT NULL, tmax REAL, tmin REAL, rain REAL, srad REAL, rh REAL, wind REAL, flags INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (stnid, year, doy));
CREATE TABLE forecast (
    stnid   TEXT NOT NULL,
    year    INTEGER NOT NULL,
    run_at  TEXT NOT NULL,
    yield_t REAL NOT NULL,
    ndays   INTEGER NOT NULL,
    model   TEXT NOT NULL,
    PRIMARY KEY (stnid, year)
);
CREATE TABLE forecast_daily (
    stnid TEXT NOT NULL,
    year  INTEGER NOT NULL,
    doy   INTEGER NOT NULL,
    sw    REAL, et REAL, drain REAL, biom REAL, lai REAL,
    PRIMARY KEY (stnid, year, doy)
);

INSERT INTO reading VALUES ('GUELPH',   2025, 100, 15.66,  5.02, 0.0,  13.28, 78.76, 3.73, 0);
INSERT INTO reading VALUES ('GUELPH',   2025, 101, 13.51,  4.26, 1.81, 14.49, 69.86, 4.53, 0);
INSERT INTO reading VALUES ('GUELPH',   2025, 224, 26.95, 20.06, 0.0,  23.77, 80.88, 3.79, 1);
INSERT INTO reading VALUES ('GUELPH',   2024, 100, 14.2,   4.9,  0.0,  12.75, 71.3,  2.91, 0);
INSERT INTO reading VALUES ('KITCHNER', 2025, 100, 16.02,  5.44, 0.0,  13.61, 74.12, 3.05, 0);
INSERT INTO reading VALUES ('KITCHNER', 2025, 271, 13.43,  3.41, 0.0,  13.19, 65.65, 0.0,  1);
INSERT INTO reading VALUES ('MILTON',   2025, 100, 15.1,   5.3,  0.0,  13.4,  70.2,  NULL, 0);

INSERT INTO forecast VALUES ('GUELPH',   2024, '2025-10-30T02:14:09', 5.512, 172, 'cropmod V2.2');
INSERT INTO forecast VALUES ('GUELPH',   2025, '2026-10-27T14:28:24', 5.951, 174, 'cropmod V2.3');
INSERT INTO forecast VALUES ('KITCHNER', 2025, '2026-10-27T14:28:24', 6.138, 174, 'cropmod V2.3');
INSERT INTO forecast VALUES ('MILTON',   2025, '2026-10-27T14:28:24', 5.928, 174, 'cropmod "V2,3" \ test');

INSERT INTO forecast_daily VALUES ('GUELPH',   2025, 100, 95.33, 3.669, 0.0, 0.0,    0.0);
INSERT INTO forecast_daily VALUES ('GUELPH',   2025, 101, 93.28, 3.851, 0.0, 0.0,    0.0);
INSERT INTO forecast_daily VALUES ('GUELPH',   2025, 180, 24.75, 0.0,   0.0, 1401.7, 3.948);
INSERT INTO forecast_daily VALUES ('GUELPH',   2024, 100, 91.12, 3.402, 0.0, 0.0,    0.0);
INSERT INTO forecast_daily VALUES ('KITCHNER', 2025, 100, 84.01, 4.191, 0.0, 0.0,    0.0);
INSERT INTO forecast_daily VALUES ('KITCHNER', 2025, 101, 80.74, 3.269, 0.0, 0.0,    0.0);
INSERT INTO forecast_daily VALUES ('MILTON',   2025, 100, NULL,  NULL,  0.0, 0.0,    0.0);

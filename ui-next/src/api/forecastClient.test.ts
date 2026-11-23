// src/api/forecastClient.test.ts
// 2026-02 — specs for the forecast roll-up helpers.
// MRD-252 — the old "drift pins" block (which called the divergence from the
// legacy console intentional) is replaced by specs of the SHARED RULE both
// consoles now apply; see the header of forecastClient.ts. Run by `npm test`.

import { describe, it, expect } from 'vitest';
import {
  currentForecastRun,
  defaultSeriesYear,
  yieldSpread,
  formatYield,
} from './forecastClient';
import type { ForecastRow } from './types';

function run(year: number, yield_t: number, run_at: string): ForecastRow {
  return { stnid: 'GUELPH', year, yield_t, ndays: 120, model: 'cropmod-1.4', run_at };
}

describe('currentForecastRun', () => {
  it('returns null for empty / null input', () => {
    expect(currentForecastRun(null)).toBeNull();
    expect(currentForecastRun([])).toBeNull();
  });

  it('picks the greatest numeric year', () => {
    const rows = [run(2021, 4.1, '2021-07-01T02:00:00'), run(2023, 4.4, '2023-07-01T02:00:00')];
    expect(currentForecastRun(rows)?.year).toBe(2023);
  });

  it('breaks a year tie on the later run_at', () => {
    const rows = [run(2023, 4.4, '2023-07-01T02:00:00'), run(2023, 4.6, '2023-07-02T02:00:00')];
    expect(currentForecastRun(rows)?.yield_t).toBe(4.6);
  });
});

describe('yieldSpread', () => {
  it('reports min / max / current and a year-ordered history', () => {
    const rows = [run(2022, 5.0, '2022-07-01T02:00:00'), run(2021, 3.0, '2021-07-01T02:00:00')];
    const s = yieldSpread(rows);
    expect(s.min).toBe(3.0);
    expect(s.max).toBe(5.0);
    expect(s.current).toBe(5.0);
    expect(s.currentYear).toBe(2022);
    expect(s.history).toEqual([3.0, 5.0]); // oldest-year first
    expect(s.runs).toBe(2);
  });
});

// --- SHARED RULE (MRD-252 / MRD-253): both consoles must agree -------------
describe('shared rule: current run', () => {
  it('a backfilled older run returned last does not become current (greatest year wins)', () => {
    const rows = [
      run(2023, 4.4, '2023-07-01T02:00:00'),
      run(2021, 3.1, '2026-01-15T09:00:00'), // backfill: returned last, newest run_at
    ];
    expect(currentForecastRun(rows)?.year).toBe(2023);
    expect(defaultSeriesYear(rows)).toBe(2023);
    expect(yieldSpread(rows).current).toBe(4.4);
  });

  it('same year: the later run_at wins regardless of row order', () => {
    const early = run(2023, 4.4, '2023-07-01T02:00:00');
    const late = run(2023, 4.6, '2023-07-02T02:00:00');
    expect(currentForecastRun([early, late])?.yield_t).toBe(4.6);
    expect(currentForecastRun([late, early])?.yield_t).toBe(4.6);
  });

  it('same year: run_at is compared as a timestamp across "T" and space separators', () => {
    const tForm = run(2023, 4.1, '2023-07-01T02:00:00');
    const spaceForm = run(2023, 4.9, '2023-07-01 09:00:00'); // later, but sorts first as text
    expect(currentForecastRun([tForm, spaceForm])?.yield_t).toBe(4.9);
    expect(currentForecastRun([spaceForm, tForm])?.yield_t).toBe(4.9);
    // and the other way round: a later T-form beats an earlier space-form
    const lateT = run(2023, 5.2, '2023-07-01T10:00:00');
    expect(currentForecastRun([lateT, spaceForm])?.yield_t).toBe(5.2);
  });

  it('same year, same instant written two ways: the row returned last wins', () => {
    const a = run(2023, 4.1, '2023-07-01T02:00:00');
    const b = run(2023, 4.2, '2023-07-01 02:00:00');
    expect(currentForecastRun([a, b])?.yield_t).toBe(4.2);
    expect(currentForecastRun([b, a])?.yield_t).toBe(4.1);
  });

  it('same year, unparseable run_at: the row returned last wins', () => {
    const good = run(2023, 4.1, '2023-07-01T02:00:00');
    const bad = run(2023, 4.7, 'not a timestamp');
    expect(currentForecastRun([good, bad])?.yield_t).toBe(4.7);
    expect(currentForecastRun([bad, good])?.yield_t).toBe(4.1);
  });

  it('same year, three rows with an unparseable one between: the fallback is sequential', () => {
    // Watchtower pins the same case in dashboard/test/forecast-rule.test.js.
    // 09:00 is the latest timestamp, but 'invalid' displaces it (unparseable ->
    // later row wins), then 02:00 displaces 'invalid' the same way.
    const nine = run(2025, 5.1, '2025-08-01T09:00:00');
    const bad = run(2025, 5.2, 'invalid');
    const two = run(2025, 5.3, '2025-08-01T02:00:00');
    expect(currentForecastRun([nine, bad, two])).toBe(two);
  });

  it('an unparseable run_at never outranks a greater year', () => {
    const rows = [run(2024, 5.0, 'garbage'), run(2023, 4.1, '2023-07-01T02:00:00')];
    expect(currentForecastRun(rows)?.year).toBe(2024);
  });
});

describe('shared rule: yield formatting', () => {
  it('shows three decimals with the unit on every Compare cell (min, max, current)', () => {
    const rows = [run(2022, 5.0, '2022-07-01T02:00:00'), run(2021, 4.4021, '2021-07-01T02:00:00')];
    const s = yieldSpread(rows);
    expect(formatYield(s.min)).toBe('4.402 t/ha');
    expect(formatYield(s.max)).toBe('5.000 t/ha');
    expect(formatYield(s.current)).toBe('5.000 t/ha');
  });

  it('formats a missing or non-finite yield as an em dash', () => {
    expect(formatYield(null)).toBe('—');
    expect(formatYield(NaN)).toBe('—');
    expect(formatYield(Infinity)).toBe('—');
    expect(yieldSpread([]).current).toBeNull();
    expect(formatYield(yieldSpread([]).current)).toBe('—');
  });
});

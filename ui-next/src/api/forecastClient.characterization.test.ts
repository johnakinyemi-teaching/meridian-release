// src/api/forecastClient.characterization.test.ts
// MRD-252 safety net. CHARACTERIZATION tests: they record what forecastClient.ts
// does TODAY, including the behaviour MRD-252 calls drift. They are committed
// before the change and must pass on the unchanged code.
//
// Inputs are recorded /api/forecast responses (src/api/__fixtures__), served
// through the real fetchForecastRuns() with only global fetch mocked. Assertions
// are on the values the Compare screen renders (currentForecastRun, yieldSpread,
// formatYield), not on markup.
//
// Legacy console counterpart (NOT asserted here; owned by Watchtower): for the
// same rows, dashboard/js/views/compare.js takes the LAST returned row as
// "Latest" and Meridian.Fmt.yield_t prints 3 dp with no unit.

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  currentForecastRun,
  defaultSeriesYear,
  fetchForecastRuns,
  formatYield,
  yieldSpread,
} from './forecastClient';
import type { ForecastRow } from './types';

import guelphRecorded from './__fixtures__/forecast.GUELPH.recorded.json';
import kitchnerRecorded from './__fixtures__/forecast.KITCHNER.recorded.json';
import guelphOutOfOrder from './__fixtures__/forecast.GUELPH.out-of-order.json';
import guelphTextRunAt from './__fixtures__/forecast.GUELPH.same-year-text-runat.json';

function serve(body: unknown) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

describe('fetchForecastRuns (recorded default data, one run per station)', () => {
  it('requests /api/forecast?station=GUELPH on the default base URL', async () => {
    const fetchMock = serve(guelphRecorded);
    const rows = await fetchForecastRuns('GUELPH');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((fetchMock.mock.calls[0] as unknown[])[0]).toBe('http://localhost:8081/api/forecast?station=GUELPH');
    expect(rows).toHaveLength(1);
  });

  it('GUELPH 2025 headline: current run 2025, 5.951 t/ha raw, shown as "5.95 t/ha"', async () => {
    serve(guelphRecorded);
    const rows = await fetchForecastRuns('GUELPH');
    const spread = yieldSpread(rows);
    expect(spread.currentYear).toBe(2025);
    expect(spread.current).toBe(5.951); // the oracle value
    expect(formatYield(spread.current)).toBe('5.95 t/ha'); // 2 dp + unit (legacy: "5.951")
    expect(defaultSeriesYear(rows)).toBe(2025);
  });

  it('Compare yield table cells for a one-run station: min = max = current, 2 dp + unit', async () => {
    serve(kitchnerRecorded);
    const rows = await fetchForecastRuns('KITCHNER');
    const s = yieldSpread(rows);
    expect(s.runs).toBe(1);
    expect(s.min).toBe(s.max);
    expect(s.max).toBe(s.current);
    expect(formatYield(s.min)).toBe(formatYield(s.current));
    expect(formatYield(s.current)).toMatch(/^\d+\.\d{2} t\/ha$/);
  });
});

describe('current-run selection: out-of-order rows (the MRD-252 drift)', () => {
  const rows = guelphOutOfOrder as ForecastRow[];

  it('fixture really is out of ascending-year order (2025 first, 2023 last returned)', () => {
    expect(rows.map((r) => r.year)).toEqual([2025, 2023]);
  });

  it('ui-next picks greatest YEAR (2025), not the last-returned row (2023)', () => {
    expect(currentForecastRun(rows)?.year).toBe(2025);
    expect(currentForecastRun(rows)?.yield_t).toBe(5.951);
    expect(defaultSeriesYear(rows)).toBe(2025);
    expect(yieldSpread(rows).current).toBe(5.951);
    expect(yieldSpread(rows).currentYear).toBe(2025);
  });

  it('is independent of row order for different years', () => {
    expect(currentForecastRun([...rows].reverse())?.year).toBe(2025);
  });

  it('min/max cover all rows and history is year-ascending', () => {
    const s = yieldSpread(rows);
    expect(s.min).toBe(4.4021);
    expect(s.max).toBe(5.951);
    expect(s.history).toEqual([4.4021, 5.951]);
    expect(s.runs).toBe(2);
  });
});

describe('same-year tie-break compares run_at as TEXT (same hazard family as MRD-250)', () => {
  const rows = guelphTextRunAt as ForecastRow[];

  it('"2023-07-01T02:00:00" beats the later-in-time "2023-07-01 09:00:00" because "T" > " "', () => {
    // Chronologically the space-format row (09:00) is later; text order says otherwise.
    expect(currentForecastRun(rows)?.yield_t).toBe(4.1);
  });
});

describe('formatYield (the 2 dp / unit side of the drift)', () => {
  it('rounds to 2 dp and appends " t/ha"', () => {
    expect(formatYield(5.951)).toBe('5.95 t/ha');
    expect(formatYield(4.4021)).toBe('4.40 t/ha');
    expect(formatYield(0)).toBe('0.00 t/ha');
  });

  it('renders missing or non-finite values as an em dash', () => {
    expect(formatYield(null)).toBe('—');
    expect(formatYield(NaN)).toBe('—');
    expect(formatYield(Infinity)).toBe('—');
  });
});

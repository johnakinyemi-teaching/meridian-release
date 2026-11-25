// src/api/forecastClient.characterization.test.ts
// MRD-252 safety net. CHARACTERIZATION tests: committed before the MRD-252
// change, recording what forecastClient.ts did then. MRD-252 edited exactly the
// expectations the shared rule changes (3 dp yield; same-year tie on the run_at
// TIMESTAMP, not its text); every other pin is unchanged and still holds.
//
// Inputs are recorded /api/forecast responses (src/api/__fixtures__), served
// through the real fetchForecastRuns() with only global fetch mocked. Assertions
// are on the values the Compare screen renders (currentForecastRun, yieldSpread,
// formatYield), not on markup.
//
// Legacy console counterpart (NOT asserted here; owned by Watchtower, MRD-253):
// dashboard/js/views/compare.js must apply the same rule.

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

  it('GUELPH 2025 headline: current run 2025, 5.951 t/ha raw, shown as "5.951 t/ha"', async () => {
    serve(guelphRecorded);
    const rows = await fetchForecastRuns('GUELPH');
    const spread = yieldSpread(rows);
    expect(spread.currentYear).toBe(2025);
    expect(spread.current).toBe(5.951); // the oracle value
    expect(formatYield(spread.current)).toBe('5.951 t/ha'); // MRD-252: was "5.95 t/ha" (2 dp)
    expect(defaultSeriesYear(rows)).toBe(2025);
  });

  it('Compare yield table cells for a one-run station: min = max = current, 3 dp + unit', async () => {
    serve(kitchnerRecorded);
    const rows = await fetchForecastRuns('KITCHNER');
    const s = yieldSpread(rows);
    expect(s.runs).toBe(1);
    expect(s.min).toBe(s.max);
    expect(s.max).toBe(s.current);
    expect(formatYield(s.min)).toBe(formatYield(s.current));
    expect(formatYield(s.current)).toMatch(/^\d+\.\d{3} t\/ha$/); // MRD-252: was \d{2}
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

describe('same-year tie-break compares run_at as a TIMESTAMP (MRD-252; was text, same hazard family as MRD-250)', () => {
  const rows = guelphTextRunAt as ForecastRow[];

  it('the later-in-time "2023-07-01 09:00:00" beats "2023-07-01T02:00:00" despite "T" > " " as text', () => {
    // MRD-252: was 4.1 (the text-order winner, 02:00). The 09:00 row is later.
    expect(currentForecastRun(rows)?.yield_t).toBe(4.9);
  });
});

describe('formatYield (shared rule: 3 dp + " t/ha")', () => {
  it('rounds to 3 dp and appends " t/ha"', () => {
    // MRD-252: were "5.95 t/ha", "4.40 t/ha", "0.00 t/ha" (2 dp).
    expect(formatYield(5.951)).toBe('5.951 t/ha');
    expect(formatYield(4.4021)).toBe('4.402 t/ha');
    expect(formatYield(0)).toBe('0.000 t/ha');
  });

  it('renders missing or non-finite values as an em dash', () => {
    expect(formatYield(null)).toBe('—');
    expect(formatYield(NaN)).toBe('—');
    expect(formatYield(Infinity)).toBe('—');
  });
});

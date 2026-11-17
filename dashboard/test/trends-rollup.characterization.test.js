/* Characterization: the Trends yield roll-up as the legacy console shows it.
 * Watchtower, MRD-253. Committed BEFORE any change to the roll-up.
 *
 * views/trends.js re-implements ui-next's forecastClient.yieldSpread() in ES5.
 * The legacy rules pinned here (see the DRIFT note in trends.js):
 *   - "current" run is the LAST row the service returns;
 *   - min / max are over all runs;
 *   - yields print with Fmt.yield_t, three decimals.
 * ui-next's side of the same case (greatest year, two decimals) is pinned by
 * Frontier in ui-next/src/api/forecastClient.characterization.test.ts
 * (MRD-252). Which rule wins is decided on the MRD-252 PR; until then these
 * values must not move.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const h = require('./harness');

async function trendsTable(opts) {
  const { window: w, $ } = h.boot(opts);
  w.Meridian.Views.Trends.render($('#view'));
  await h.waitFor(() => $('#tr-table table tbody tr').length);
  const rows = h.tableValues($, $('#tr-table table'));
  for (const r of rows) delete r.Trend; // sparkline glyphs are markup, not values
  return rows;
}

test('recorded fleet: one 2025 run per station, three-decimal yields', async () => {
  assert.deepEqual(await trendsTable(), [
    { Station: 'BRANTFRD', Runs: '1', Min: '5.841', Max: '5.841', Current: '5.841', Yr: '2025' },
    { Station: 'CAMBRIDG', Runs: '1', Min: '6.014', Max: '6.014', Current: '6.014', Yr: '2025' },
    { Station: 'GUELPH',   Runs: '1', Min: '5.951', Max: '5.951', Current: '5.951', Yr: '2025' },
    { Station: 'KITCHNER', Runs: '1', Min: '6.138', Max: '6.138', Current: '6.138', Yr: '2025' },
    { Station: 'MILTON',   Runs: '1', Min: '5.928', Max: '5.928', Current: '5.928', Yr: '2025' },
    { Station: 'WATERLOO', Runs: '1', Min: '5.737', Max: '5.737', Current: '5.737', Yr: '2025' },
  ]);
});

test('GUELPH oracle yield shows as 5.951 in the legacy console', async () => {
  const rows = await trendsTable();
  assert.equal(rows.find((r) => r.Station === 'GUELPH').Current, '5.951');
});

test('out-of-order runs: legacy takes the LAST returned run as current', async () => {
  const rows = await trendsTable({ fixtures: { 'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json' } });
  const g = rows.find((r) => r.Station === 'GUELPH');
  /* service returned [2025, 2023]; legacy names 2023 current. ui-next names
   * 2025 (greatest year) and prints 5.95 - that is the MRD-252 drift. */
  assert.deepEqual(g, { Station: 'GUELPH', Runs: '2', Min: '4.402', Max: '5.951', Current: '4.402', Yr: '2023' });
});

test('Fmt.yield_t: three decimals, em dash for missing', () => {
  const { window: w } = h.boot();
  const F = w.Meridian.Fmt;
  assert.equal(F.yield_t(5.95099999999999962), '5.951');
  assert.equal(F.yield_t(4.4021), '4.402');
  assert.equal(F.yield_t(6.0), '6.000');
  assert.equal(F.yield_t(null), '—');
  assert.equal(F.yield_t(undefined), '—');
  assert.equal(F.yield_t(''), '—');
});

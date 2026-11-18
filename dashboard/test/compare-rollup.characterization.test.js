/* MRD-253: characterize Compare yield roll-up before
 * integrating the shared forecast helper.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const h = require('./harness');

async function compareTable(opts) {
  const { window: w, $ } = h.boot(opts);

  w.Meridian.State.compareToggle('GUELPH');
  w.Meridian.State.compareToggle('CAMBRIDG');

  w.Meridian.Views.Compare.render($('#view'));

  await h.waitFor(() => $('#cmp-yield table tbody tr').length);

  const rows = h.tableValues($, $('#cmp-yield table'));

  for (const r of rows) delete r.Trend;

  return rows;
}

test('Compare: recorded station yields retain three decimals', async () => {
  const rows = await compareTable();

  assert.deepEqual(rows.find(r => r.Station === 'GUELPH'), {
    Station: 'GUELPH',
    Runs: '1',
    Min: '5.951',
    Max: '5.951',
    Latest: '5.951',
    Yr: '2025'
  });

  assert.deepEqual(rows.find(r => r.Station === 'CAMBRIDG'), {
    Station: 'CAMBRIDG',
    Runs: '1',
    Min: '6.014',
    Max: '6.014',
    Latest: '6.014',
    Yr: '2025'
  });
});

test('Compare: out-of-order forecasts select the last returned run before refactor', async () => {
  const rows = await compareTable({
    fixtures: {
      'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json'
    }
  });

  assert.deepEqual(rows.find(r => r.Station === 'GUELPH'), {
    Station: 'GUELPH',
    Runs: '2',
    Min: '4.402',
    Max: '5.951',
    Latest: '4.402',
    Yr: '2023'
  });
});
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

test('Compare: out-of-order forecasts select the greatest year', async () => {
  const rows = await compareTable({
    fixtures: {
      'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json'
    }
  });

  /* MRD-253: previously selected 2023 (4.402).
   * The shared MRD-252 rule now selects 2025 (5.951).
   */
  assert.deepEqual(rows.find(r => r.Station === 'GUELPH'), {
    Station: 'GUELPH',
    Runs: '2',
    Min: '4.402',
    Max: '5.951',
    Latest: '5.951',
    Yr: '2025'
  });
});

test('Trends detail view loads the greatest-year forecast series', async () => {
  const { window: w, $, calls } = h.boot({
    fixtures: {
      'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json'
    }
  });

  w.Meridian.Views.Trends.render($('#view'));

  await h.waitFor(() =>
    $('#tr-table table tbody tr').length
  );

  $('#tr-table .tr-row[data-stn="GUELPH"]').trigger('click');

  await h.waitFor(() =>
    calls.some(c =>
      c.path === '/api/series' &&
      c.params.station === 'GUELPH'
    )
  );

  const requests = calls.filter(c =>
    c.path === '/api/series' &&
    c.params.station === 'GUELPH'
  );

  assert.equal(Number(requests[requests.length - 1].params.year), 2025);

  assert.equal(
    $('#tr-detail-lbl').text(),
    '– GUELPH 2025'
  );
});

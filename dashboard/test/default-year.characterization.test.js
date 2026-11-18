/* MRD-253: characterize default-year selection in Forecast
 * and Anomalies before addressing PR #17 review feedback.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const h = require('./harness');

async function openView(view, selectedYear) {
  const { window: w, $, calls } = h.boot({
    fixtures: {
      'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json'
    }
  });

  w.Meridian.State.selectStation('GUELPH');
  w.Meridian.State.selectYear(selectedYear);

  w.Meridian.Views[view].render($('#view'));

  await h.waitFor(() =>
    calls.some(c =>
      c.path === '/api/series' &&
      c.params.station === 'GUELPH'
    )
  );

  const request = calls.find(c =>
    c.path === '/api/series' &&
    c.params.station === 'GUELPH'
  );

  return { $, year: Number(request.params.year) };
}

test('Forecast: unavailable selected year defaults to last returned year', async () => {
  const { $, year } = await openView('Forecast', 2022);

  assert.equal(year, 2023);
  assert.equal($('#fc-year').text(), '– 2023');
});

test('Anomalies: unavailable selected year defaults to last returned year', async () => {
  const { $, year } = await openView('Anomalies', 2022);

  assert.equal(year, 2023);
  assert.equal($('#an-year').val(), '2023');
});

test('Forecast: preserves an explicitly selected available year', async () => {
  const { year } = await openView('Forecast', 2023);

  assert.equal(year, 2023);
});

test('Anomalies: preserves an explicitly selected available year', async () => {
  const { year } = await openView('Anomalies', 2023);

  assert.equal(year, 2023);
});
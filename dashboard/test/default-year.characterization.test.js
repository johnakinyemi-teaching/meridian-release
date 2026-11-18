/* MRD-253: characterize default-year selection in Forecast,
 * Anomalies and Data Quality.
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

test('Forecast: unavailable selected year defaults to greatest year', async () => {
  const { $, year } = await openView('Forecast', 2022);

  // MRD-253: previously defaulted to 2023 (last returned row).
  // Now follows the shared forecast rule agreed under MRD-252.
  assert.equal(year, 2025);
  assert.equal($('#fc-year').text(), '– 2025');
});

test('Anomalies: unavailable selected year defaults to greatest year', async () => {
  const { $, year } = await openView('Anomalies', 2022);

  // MRD-253: previously defaulted to 2023 (last returned row).
  // Now follows the shared forecast rule agreed under MRD-252.
  assert.equal(year, 2025);
  assert.equal($('#an-year').val(), '2025');
});

test('Forecast: preserves an explicitly selected available year', async () => {
  const { year } = await openView('Forecast', 2023);

  assert.equal(year, 2023);
});

test('Anomalies: preserves an explicitly selected available year', async () => {
  const { year } = await openView('Anomalies', 2023);

  assert.equal(year, 2023);
});

test('Quality: unavailable selected year defaults to greatest year', async () => {
  const { year } = await openView('Quality', 2022);

  // MRD-253: align Data Quality with the shared forecast rule.
  // Previously selected 2023 because it was the last returned row.
  assert.equal(year, 2025);
});

test('Quality: preserves an explicitly selected available year', async () => {
  const { year } = await openView('Quality', 2023);

  assert.equal(year, 2023);
});
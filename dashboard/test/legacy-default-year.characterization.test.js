/* MRD-253: characterize default-year selection in the
 * original single-page legacy dashboard (?legacy=1).
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./harness');

test('Legacy dashboard: defaults to greatest forecast year', async () => {
  const { window: w, $, calls } = h.boot({
    fixtures: {
      'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json'
    }
  });

  $('body').append(
    '<div id="legacy">' +
      '<span id="apiver"></span>' +
      '<span id="sel"></span>' +
      '<table id="stations"><tbody></tbody></table>' +
      '<table id="forecast"><tbody></tbody></table>' +
      '<table id="series"><tbody></tbody></table>' +
      '<div id="chart"></div>' +
    '</div>'
  );

  w.eval(fs.readFileSync(
    path.join(h.DASH, 'js/dashboard.js'), 'utf8'
  ));

  await h.waitFor(() =>
    $('#stations tbody tr[data-stn="GUELPH"]').length
  );

  $('#stations tbody tr[data-stn="GUELPH"]').trigger('click');

  const request = await h.waitFor(() =>
    calls.find(c =>
      c.path === '/api/series' &&
      c.params.station === 'GUELPH'
    )
  );

  // MRD-253: previously selected the last returned row (2023).
  // The legacy layout now follows the shared forecast rule.
  assert.equal(Number(request.params.year), 2025);
});

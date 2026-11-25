/* dashboard/test/harness.js - load the legacy operator console into jsdom.
 *
 * Watchtower, A4 (MRD-253). The console has no build step (MRD-181) and no
 * tests, so this harness loads the SAME ES5 files index.html loads, in the
 * same order, into a jsdom window with the vendored jQuery 1.11.3. Nothing
 * here is shipped: dashboard/test/ is never served to operators and the
 * console itself is unchanged.
 *
 * The oracle is the deployed console's behaviour. The only seam we replace
 * is the network: jQuery.ajax is routed to API responses recorded from the
 * real services (fixtures/api/*.json, see fixtures/README.md), so every
 * value a test asserts on is what the console computes from real data.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const DASH = path.resolve(__dirname, '..');
const FIXTURES = path.join(__dirname, 'fixtures', 'api');

/* index.html's script order, minus app.js and dashboard.js (they boot the
 * router and the health poller, which tests drive by hand instead). */
const CONSOLE_SCRIPTS = [
  'js/vendor/jquery-1.11.3.min.js',
  'js/util/quality.js',
  'js/api.js',
  'js/util/fmt.js',
  'js/util/dom.js',
  'js/util/state.js',
  'js/util/forecast.js',
  'js/charts/barchart.js',
  'js/charts/sparkline.js',
  'js/views/quality.js',
  'js/views/stations.js',
  'js/views/forecast.js',
  'js/views/anomalies.js',
  'js/views/compare.js',
  'js/views/trends.js',
  'js/views/about.js',
];

function readFixture(name) {
  return fs.readFileSync(path.join(FIXTURES, name), 'utf8');
}

/* Map an API path + params onto a fixture file name. `overrides` lets a test
 * swap a response, e.g. { 'forecast.GUELPH.json': 'forecast.GUELPH.multi-year.json' }. */
function fixtureFor(urlPath, params, overrides) {
  let name;
  if (urlPath === '/api/stations') name = 'stations.json';
  else if (urlPath === '/api/forecast') name = 'forecast.' + params.station + '.json';
  else if (urlPath === '/api/series') name = 'series.' + params.station + '.' + params.year + '.json';
  else if (urlPath === '/api/health') name = 'health.json';
  else throw new Error('no fixture route for ' + urlPath);
  return (overrides && overrides[name]) || name;
}

/* Boot a console window. opts.skip: script paths to leave out (used to test
 * that shared QC rules remain available without views/quality.js). */
function boot(opts) {
  opts = opts || {};
  const skip = new Set(opts.skip || []);
  const overrides = opts.fixtures || {};
  const dom = new JSDOM('<!DOCTYPE html><html><body><div id="view"></div></body></html>', {
    runScripts: 'outside-only',
    url: 'http://localhost:8080/',
  });
  const w = dom.window;
  const calls = [];

  for (const rel of CONSOLE_SCRIPTS) {
    if (skip.has(rel)) continue;
    w.eval(fs.readFileSync(path.join(DASH, rel), 'utf8') + '\n//# sourceURL=' + rel);
    if (rel.indexOf('jquery') !== -1) {
      /* network seam: answer from recorded responses, asynchronously, the
       * way a real XHR would. JSON.parse in the window so numbers keep the
       * exact doubles the service sent. */
      w.jQuery.ajax = function (o) {
        const urlPath = o.url.replace(/^https?:\/\/[^/]+/, '');
        const params = o.data || {};
        calls.push({ path: urlPath, params: params });
        let body;
        try { body = readFixture(fixtureFor(urlPath, params, overrides)); }
        catch (e) { setTimeout(function () { o.error({}, 'error'); }, 0); return; }
        setTimeout(function () { o.success(w.JSON.parse(body)); }, 0);
      };
    }
  }

  if (w.Meridian && w.Meridian.State) {
    w.Meridian.State.set('stations', JSON.parse(readFixture('stations.json')));
  }
  return { window: w, $: w.jQuery, calls: calls };
}

/* Wait until predicate() is truthy (views render after async API calls). */
function waitFor(predicate, timeoutMs) {
  const limit = Date.now() + (timeoutMs || 3000);
  return new Promise(function (resolve, reject) {
    (function poll() {
      let v;
      try { v = predicate(); } catch (e) { v = null; }
      if (v) return resolve(v);
      if (Date.now() > limit) return reject(new Error('waitFor timed out'));
      setTimeout(poll, 10);
    })();
  });
}

/* Promise wrapper for the callback-style MeridianApi methods. The result is
 * copied into Node's realm (see tableValues). */
function call(w, method) {
  const args = Array.prototype.slice.call(arguments, 2);
  return new Promise(function (resolve, reject) {
    w.MeridianApi[method].apply(null, args.concat([function (err, data) {
      if (err) reject(new Error(String(err))); else resolve(JSON.parse(JSON.stringify(data)));
    }]));
  });
}

/* Read a rendered table as values: one object per body row, keyed by the
 * header label, holding the cell's text. Tests assert on these values, not on
 * markup or classes. */
function tableValues($, $table) {
  const heads = $table.find('thead th').map(function () { return $(this).text().trim(); }).get();
  const rows = $table.find('tbody tr').map(function () {
    const row = {};
    $(this).children('td').each(function (i) { row[heads[i]] = $(this).text().trim(); });
    return [row];
  }).get();
  /* jsdom objects come from the window's realm; copy into Node's so
   * assert.deepStrictEqual compares values, not prototypes. */
  return JSON.parse(JSON.stringify(rows));
}

module.exports = { boot, waitFor, call, tableValues, readFixture, FIXTURES, DASH };

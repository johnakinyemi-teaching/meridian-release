/* Characterization: the QC rule as the legacy console applies it today.
 * Watchtower, MRD-253. Committed BEFORE any change to the rule's location.
 *
 * MRD-253: the thresholds live in three places across the two consoles:
 *   1. dashboard/js/views/quality.js   Meridian.Quality.RULES
 *   2. dashboard/js/api.js             deriveFlags() hard-coded fallback
 *   3. ui-next/src/util/quality.ts     RULES (hand-copied)
 * These tests pin (a) the values and flags the legacy console produces now,
 * and (b) that the three copies agree today, so the consolidation has to keep
 * every one of these assertions green.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const h = require('./harness');

const REPO = path.resolve(h.DASH, '..');
const STATIONS = ['BRANTFRD', 'CAMBRIDG', 'GUELPH', 'KITCHNER', 'MILTON', 'WATERLOO'];

const EXPECTED_RULES = {
  swMin: 0, swMax: 400,
  etMin: 0, etMax: 12,
  drainMin: 0,
  laiMin: 0, laiMax: 9,
  biomDropFrac: 0.15,
};

const EXPECTED_LABELS = {
  'sw-range': 'Soil water out of range',
  'et-range': 'ET out of range',
  'drain-neg': 'Negative drainage',
  'lai-range': 'LAI out of range',
  'biom-drop': 'Biomass drop > 15%',
};

/* Per-row flags the console derives for the crafted boundary series. Bounds
 * are inclusive (a value ON a bound is clean); the biomass rule is a strict
 * "more than 15%" and is skipped when the previous biomass is zero. */
const EDGE_FLAGS = {
  100: [],                                                  // every field on its lower bound
  101: [],                                                  // every field on its upper bound
  102: ['sw-range', 'et-range', 'drain-neg', 'lai-range'],  // just past upper; biomass -15.0% exactly
  103: ['sw-range', 'et-range', 'lai-range', 'biom-drop'],  // just past lower; biomass -15.3%
  104: [],
  105: ['biom-drop'],                                       // biomass drops to zero
  106: [],                                                  // rise from zero: rule skipped
};

function plain(o) { return JSON.parse(JSON.stringify(o)); }

function flagsByDoy(rows) {
  const out = {};
  for (const r of rows) out[r.doy] = plain(r.flags);
  return out;
}

/* Pull an object literal's numeric/string fields out of TypeScript source
 * without compiling it: ui-next builds separately and the console has no
 * TypeScript toolchain. */
function tsObjectLiteral(src, decl) {
  const start = src.indexOf(decl);
  assert.ok(start !== -1, 'declaration not found: ' + decl);
  const open = src.indexOf('{', start + decl.length);
  const close = src.indexOf('};', open);
  const body = src.slice(open + 1, close);
  const out = {};
  const re = /'?([\w-]+)'?\s*:\s*('([^']*)'|-?[\d.]+)/g;
  let m;
  while ((m = re.exec(body))) out[m[1]] = m[3] !== undefined ? m[3] : Number(m[2]);
  return out;
}

test('legacy RULES pin the 2019-08 agronomy bounds', () => {
  const { window: w } = h.boot();
  assert.deepEqual(plain(w.Meridian.Quality.RULES), EXPECTED_RULES);
  assert.deepEqual(plain(w.Meridian.Quality.FLAG_LABELS), EXPECTED_LABELS);
});

test('ui-next quality.ts copy agrees with the legacy RULES and labels today', () => {
  const src = fs.readFileSync(path.join(REPO, 'ui-next/src/util/quality.ts'), 'utf8');
  assert.deepEqual(tsObjectLiteral(src, 'export const RULES: QualityRules ='), EXPECTED_RULES);
  assert.deepEqual(tsObjectLiteral(src, 'export const FLAG_LABELS: Record<FlagCode, string> ='), EXPECTED_LABELS);
});

test('recorded data: no station trips a console QC flag', async () => {
  const { window: w } = h.boot();
  for (const stn of STATIONS) {
    const rows = await h.call(w, 'anomalies', stn, 2025);
    assert.equal(rows.length, 174, stn + ' row count');
    assert.equal(rows.filter((r) => r.suspect).length, 0, stn + ' suspect rows');
    assert.equal(rows.reduce((n, r) => n + r.flags.length, 0), 0, stn + ' flags');
  }
});

test('boundary series: exact per-row flags from MeridianApi.anomalies', async () => {
  const { window: w } = h.boot();
  const rows = await h.call(w, 'anomalies', 'EDGE', 2025);
  assert.deepEqual(flagsByDoy(rows), EDGE_FLAGS);
  assert.deepEqual(rows.map((r) => r.suspect), [false, false, true, true, false, true, false]);
});

test('api.js uses shared QC rules even when the Quality view is absent', async () => {
  const { window: w } = h.boot({
    skip: ['js/views/quality.js']
  });

  assert.equal(w.Meridian.Views.Quality, undefined);
  assert.deepEqual(plain(w.Meridian.Quality.RULES), EXPECTED_RULES);

  const rows = await h.call(w, 'anomalies', 'EDGE', 2025);
  assert.deepEqual(flagsByDoy(rows), EDGE_FLAGS);
  assert.deepEqual(
    rows.map(r => r.suspect),
    [false, false, true, true, false, true, false]
  );
});

test('Data Quality view: tile and threshold values for recorded GUELPH 2025', async () => {
  const { window: w, $ } = h.boot();
  w.Meridian.State.selectStation('GUELPH');
  w.Meridian.Views.Quality.render($('#view'));
  await h.waitFor(() => $('#dq-tiles .tile').length === 4);

  const tiles = {};
  $('#dq-tiles .tile').each(function () {
    tiles[$(this).find('.tile-label').text()] = $(this).find('.tile-val').text();
  });
  assert.deepEqual(tiles, { 'Days': '174', 'Clean': '100.0%', 'Suspect rows': '0', 'Total flags': '0' });

  const rules = {};
  $('#dq-rules .kv').each(function () { rules[$(this).find('.k').text()] = $(this).find('.v').text(); });
  assert.deepEqual(rules, {
    'Soil water (sw)': '0 – 400 mm',
    'Evapotranspiration (et)': '0 – 12 mm/day',
    'Drainage (drain)': '≥ 0 mm/day',
    'Leaf area index (lai)': '0 – 9 m²/m²',
    'Biomass day-over-day drop': '> 15% is suspect',
  });
});

test('Data Quality view: tile values for the boundary series', async () => {
  const { window: w, $ } = h.boot();
  w.Meridian.State.selectStation('EDGE');
  w.Meridian.State.set('stations', [{ stnid: 'EDGE', days: 7, first_year: 2025, last_year: 2025 }]);
  /* the year picker reads /api/forecast; give EDGE a single 2025 run */
  const fx = path.join(h.FIXTURES, 'forecast.EDGE.json');
  assert.ok(fs.existsSync(fx), 'forecast.EDGE.json fixture missing');
  w.Meridian.Views.Quality.render($('#view'));
  await h.waitFor(() => $('#dq-tiles .tile').length === 4);

  const tiles = {};
  $('#dq-tiles .tile').each(function () {
    tiles[$(this).find('.tile-label').text()] = $(this).find('.tile-val').text();
  });
  assert.deepEqual(tiles, { 'Days': '7', 'Clean': '57.1%', 'Suspect rows': '3', 'Total flags': '9' });
});

test('api.js classification responds to changes in the shared QC rules', async () => {
  const { window: w } = h.boot();

  const before = await h.call(w, 'anomalies', 'EDGE', 2025);
  assert.deepEqual(flagsByDoy(before), EDGE_FLAGS);

  // Temporarily change the shared threshold in this test window.
  w.Meridian.Quality.RULES.swMax = 399;

  const after = await h.call(w, 'anomalies', 'EDGE', 2025);

  const upperBoundRow = after.find(r => r.doy === 101);

  assert.ok(upperBoundRow.flags.includes('sw-range'));
  assert.equal(upperBoundRow.suspect, true);
});
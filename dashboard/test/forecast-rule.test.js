/* MRD-253: regression tests for the shared forecast rule
 * agreed with Frontier under MRD-252.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const h = require('./harness');

const { window: w } = h.boot();
const F = w.Meridian.Forecast;

function run(year, run_at, yield_t) {
  return { year, run_at, yield_t };
}

test('currentRun selects the greatest year regardless of API order', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.951);
  const b = run(2023, '2023-08-01T09:00:00', 4.402);

  assert.equal(F.currentRun([a, b]), a);
  assert.equal(F.currentRun([b, a]), a);
});

test('same-year tie selects the latest timestamp', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.1);
  const b = run(2025, '2025-08-01T02:00:00', 4.9);

  assert.equal(F.currentRun([a, b]), a);
  assert.equal(F.currentRun([b, a]), a);
});

test('timestamp comparison accepts T and space separators', () => {
  const a = run(2025, '2025-08-01T02:00:00', 4.1);
  const b = run(2025, '2025-08-01 09:00:00', 4.9);

  assert.equal(F.currentRun([a, b]), b);
  assert.equal(F.currentRun([b, a]), b);
});

test('equal timestamps select the last returned row', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.1);
  const b = run(2025, '2025-08-01 09:00:00', 5.2);

  assert.equal(F.currentRun([a, b]), b);
  assert.equal(F.currentRun([b, a]), a);
});

test('unparseable timestamp falls back to the last returned row', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.1);
  const b = run(2025, 'invalid', 5.2);

  assert.equal(F.currentRun([a, b]), b);
  assert.equal(F.currentRun([b, a]), a);
});

test('mixed invalid timestamps follow the agreed sequential fallback', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.1);
  const b = run(2025, 'invalid', 5.2);
  const c = run(2025, '2025-08-01T02:00:00', 5.3);

  assert.equal(F.currentRun([a, b, c]), c);
});

test('greatest year takes priority over timestamp validity', () => {
  const a = run(2025, 'invalid', 5.1);
  const b = run(2024, '2024-08-01T09:00:00', 4.9);

  assert.equal(F.currentRun([a, b]), a);
});

test('empty or missing forecasts return null', () => {
  assert.equal(F.currentRun([]), null);
  assert.equal(F.currentRun(null), null);
  assert.equal(F.currentRun(undefined), null);
});

test('spread calculates min, max, current and run count', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.951);
  const b = run(2023, '2023-08-01T09:00:00', 4.402);

  const result = F.spread([a, b]);

  assert.equal(result.runs, 2);
  assert.equal(result.min, 4.402);
  assert.equal(result.max, 5.951);
  assert.equal(result.current, 5.951);
  assert.equal(result.year, 2025);
});

test('Trends history preserves chronological year ordering', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.951);
  const b = run(2023, '2023-08-01T09:00:00', 4.402);

  const rows = [a, b];
  const result = F.spread(rows);

  assert.deepEqual(Array.from(result.history), [4.402, 5.951]);
  assert.equal(rows[0], a);
  assert.equal(rows[1], b);
});

test('Compare history preserves original API ordering', () => {
  const a = run(2025, '2025-08-01T09:00:00', 5.951);
  const b = run(2023, '2023-08-01T09:00:00', 4.402);

  const result = F.spread([a, b], true);

  assert.deepEqual(Array.from(result.history), [5.951, 4.402]);
  assert.equal(result.current, 5.951);
  assert.equal(result.year, 2025);
});

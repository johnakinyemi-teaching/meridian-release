// src/api/forecastClient.ts
// 2026-02 — forecast-focused client for the Compare screen.
//
// Compare needs forecast runs for several stations at once plus a couple of
// roll-ups (current run, yield history, a formatted headline yield). The
// canonical client in ./client.ts + ./endpoints.ts only exposes the raw four
// endpoints, and threading useAsync per station through Compare was awkward, so
// this gathers the forecast-shaped calls behind one small module.
//
// It mirrors dashboard/js/api.js — same endpoints, same base-URL story, same
// "derive the roll-ups on the client because the service never grew them"
// approach the legacy console has used since 2018. It is, deliberately, a
// second copy of that logic rather than a shared one; the two consoles ship
// separately and neither build imports the other.
//
// ---------------------------------------------------------------------------
// SHARED RULE (MRD-252, agreed with the legacy console's owners under MRD-253).
// The service never computes a "current" run, so each console derives it. Both
// consoles must apply this same rule, or they show different yields for one
// station:
//
//   1. Current run = the row with the greatest numeric `year`, whatever order
//      the service returns rows in (a backfilled older season returned last
//      must not become "current").
//   2. If several rows share that year, the one with the latest `run_at`,
//      compared as a timestamp, not as text. `run_at` is an opaque string;
//      both "YYYY-MM-DDTHH:MM:SS" and "YYYY-MM-DD HH:MM:SS" are accepted, and
//      as text "T" sorts after " ", which picks the wrong run.
//   3. If `run_at` is also equal, or will not parse, the row returned last wins.
//      The rows are scanned in order, so this fallback is sequential, not a
//      global "latest run_at wins": for same-year rows [09:00, unparseable,
//      02:00] the unparseable row displaces 09:00, then 02:00 displaces it, so
//      02:00 is current. The legacy console does the same (MRD-253).
//   4. Yield is shown to three decimals (legacy Meridian.Fmt.yield_t).
//
// Rules 1-3 are defensive. Today `forecast` has PRIMARY KEY (stnid, year) and
// /api/forecast?station= returns rows ORDER BY year, so the service sends one
// row per year in ascending order: out-of-order or same-year rows cannot reach
// this client yet. On real data the only visible effect of MRD-252 is rule 4.
//
// Before MRD-252 this client broke same-year ties on the `run_at` TEXT and
// printed two decimals, while the legacy console took the last-returned row.
// A change to the rule must be made in both consoles. See MIGRATION.md.
// ---------------------------------------------------------------------------

import type { ForecastRow, SeriesRow } from './types';

const DEFAULT_BASE = 'http://localhost:8081';

/**
 * Resolve the API base URL. A second copy of client.ts's apiBase() — same
 * precedence (build-time env, then a window global, then localhost) so the two
 * clients point at the same service. Kept local on purpose: this module does
 * not import the canonical client (that is the duplication, documented above).
 */
export function forecastApiBase(): string {
  const fromEnv = import.meta.env.VITE_API_BASE as string | undefined;
  if (fromEnv && fromEnv.length > 0) return trimSlash(fromEnv);
  const w = globalThis as unknown as { MERIDIAN_API_BASE?: string };
  if (w.MERIDIAN_API_BASE) return trimSlash(w.MERIDIAN_API_BASE);
  return DEFAULT_BASE;
}

function trimSlash(s: string): string {
  return s.endsWith('/') ? s.slice(0, -1) : s;
}

export class ForecastClientError extends Error {
  readonly url: string;
  readonly status: number | null;
  constructor(message: string, url: string, status: number | null) {
    super(message);
    this.name = 'ForecastClientError';
    this.url = url;
    this.status = status;
  }
}

async function getJson<T>(path: string, params: Record<string, string | number>, signal?: AbortSignal): Promise<T> {
  const url = new URL(forecastApiBase() + path);
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    url.searchParams.set(k, String(v));
  }
  const href = url.toString();
  let res: Response;
  try {
    res = await fetch(href, { method: 'GET', headers: { Accept: 'application/json' }, signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new ForecastClientError(`Network error reaching ${href}. Is the Meridian service on :8081 up?`, href, null);
  }
  if (!res.ok) {
    throw new ForecastClientError(`Service returned HTTP ${res.status} for ${path}.`, href, res.status);
  }
  try {
    return (await res.json()) as T;
  } catch {
    throw new ForecastClientError(`Response from ${path} was not valid JSON.`, href, res.status);
  }
}

/** GET /api/forecast?station=<id> — forecast runs for one station. */
export function fetchForecastRuns(station: string, signal?: AbortSignal): Promise<ForecastRow[]> {
  return getJson<ForecastRow[]>('/api/forecast', { station }, signal);
}

/** GET /api/series?station=<id>&year=<yyyy> — daily series for a run. */
export function fetchDailySeries(station: string, year: number, signal?: AbortSignal): Promise<SeriesRow[]> {
  return getJson<SeriesRow[]>('/api/series', { station, year }, signal);
}

/**
 * `run_at` as epoch milliseconds, or NaN if it will not parse. The space
 * separator is normalised to "T" so both forms parse the same way (as local
 * time) in every browser: V8 accepts "YYYY-MM-DD HH:MM:SS" as-is, but that form
 * is not in the ECMAScript date format and other engines may return NaN for it.
 */
function runAtMs(runAt: string): number {
  return Date.parse(String(runAt).trim().replace(' ', 'T'));
}

/**
 * The forecast run "in force" for a station, by the shared rule (see the
 * header): greatest `year`; within that year the latest `run_at` timestamp;
 * if `run_at` is equal or unparseable, the later row (applied sequentially, so
 * an unparseable row in between can let an earlier run_at win).
 */
export function currentForecastRun(rows: ForecastRow[] | null): ForecastRow | null {
  if (!rows || rows.length === 0) return null;
  let best = rows[0];
  for (const r of rows) {
    if (r.year > best.year) {
      best = r;
    } else if (r.year === best.year) {
      const a = runAtMs(r.run_at);
      const b = runAtMs(best.run_at);
      // Later run_at wins; equal or unparseable falls back to the later row.
      if (Number.isNaN(a) || Number.isNaN(b) || a >= b) best = r;
    }
  }
  return best;
}

/** The default overlay year for Compare: the current run's year, or null. */
export function defaultSeriesYear(rows: ForecastRow[] | null): number | null {
  const run = currentForecastRun(rows);
  return run ? run.year : null;
}

export interface YieldSpread {
  min: number | null;
  max: number | null;
  current: number | null;
  currentYear: number | null;
  runs: number;
  /** Yields oldest-year-first, for a sparkline. */
  history: number[];
}

/** Min / max / current yield plus a year-ordered history, for the yield table. */
export function yieldSpread(rows: ForecastRow[] | null): YieldSpread {
  if (!rows || rows.length === 0) {
    return { min: null, max: null, current: null, currentYear: null, runs: 0, history: [] };
  }
  const byYear = rows.slice().sort((a, b) => a.year - b.year);
  let min = Infinity;
  let max = -Infinity;
  for (const r of byYear) {
    if (r.yield_t < min) min = r.yield_t;
    if (r.yield_t > max) max = r.yield_t;
  }
  const current = currentForecastRun(rows);
  return {
    min: Number.isFinite(min) ? min : null,
    max: Number.isFinite(max) ? max : null,
    current: current ? current.yield_t : null,
    currentYear: current ? current.year : null,
    runs: rows.length,
    history: byYear.map((r) => r.yield_t),
  };
}

/**
 * Yield in t/ha to three decimals (shared rule, see the header), matching the
 * legacy Meridian.Fmt.yield_t and ui-next's own util/format yieldTonnes.
 * Missing or non-finite values render as an em dash.
 */
export function formatYield(n: number | null): string {
  if (n === null || !Number.isFinite(n)) return '—';
  return `${n.toFixed(3)} t/ha`;
}

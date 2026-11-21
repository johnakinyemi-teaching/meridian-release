package ca.meridian.db;

import ca.meridian.util.Clock;
import ca.meridian.util.Env;
import ca.meridian.util.Strings;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the per-station forecast summary.
 *
 * The 2026 versioning work added /api/v2/forecast, which pairs the
 * paginated forecast rows with a small summary block (how many runs a
 * station has, when the latest run landed, the mean yield across runs).
 * That block is an aggregate over the whole forecast table for the
 * station, and the v2 portal polls it on every page change, so it is
 * worth holding rather than re-aggregating each time. This caches it
 * per station for meridian.v2.cache.ttl seconds, in the same spirit as
 * {@link StationCache}.
 *
 * Only the v2 path uses this. The original /api/forecast handler and
 * the batch pipeline never touch it, so a cold start and the nightly
 * run behave exactly as before.
 *
 * R. Osei 2026
 */
public final class ForecastCache {

    /** The cached summary for one station. */
    public static final class Summary {
        public final String station;
        public final int runs;
        public final String latestRun;
        public final double meanYield;
        public final int lastYear;

        public Summary(String station, int runs, String latestRun,
                       double meanYield, int lastYear) {
            this.station = station;
            this.runs = runs;
            this.latestRun = latestRun;
            this.meanYield = meanYield;
            this.lastYear = lastYear;
        }
    }

    private static final class Entry {
        final Summary summary;
        final long loadedAt;
        final String token;

        Entry(Summary summary, long loadedAt, String token) {
            this.summary = summary;
            this.loadedAt = loadedAt;
            this.token = token;
        }
    }

    private final DbClient db;
    private final long ttlMillis;
    private final EvictionMarks marks;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public ForecastCache(DbClient db) {
        this(db, Env.v2CacheTtlSeconds() * 1000L);
    }

    public ForecastCache(DbClient db, long ttlMillis) {
        this.db = db;
        this.ttlMillis = ttlMillis;
        this.marks = new EvictionMarks(db.raw().path());
    }

    /**
     * Returns the station's summary, loading and caching it on a miss or
     * once the entry has aged past the TTL. The station id is expected
     * to be validated by the caller.
     */
    public Summary summary(String station) throws IOException {
        String k = key(station);
        long now = Clock.nowMillis();
        // Read the eviction token before loading, so an evict that lands
        // during a load is never masked: the entry we store carries the
        // token as it was before the load, and the next read, seeing a
        // newer token, reloads.
        String token = marks.token(station);
        Entry e = entries.get(k);
        if (e != null
                && (now - e.loadedAt) < ttlMillis
                && !EvictionMarks.isUnknown(token)
                && e.token.equals(token)) {
            return e.summary;
        }
        Summary fresh = load(station);
        if (!EvictionMarks.isUnknown(token)) {
            entries.put(k, new Entry(fresh, now, token));
        }
        return fresh;
    }

    /**
     * Evicts a station's cached summary after a new forecast run for the
     * given year lands, so the next read reflects the run straight away
     * rather than waiting out the TTL. Ops calls this from the admin tool
     * after a manual backfill (see AdminCli "evict").
     *
     * The admin tool runs in its own JVM, so dropping this cache's own
     * entry is not enough: the running server has a different cache. It
     * also leaves a note beside the store (an eviction token) that the
     * server reads on its next summary and reloads on (MRD-247). A failure
     * to write the note is raised, so the admin tool reports it rather than
     * claiming an eviction that did not reach the server.
     *
     * The year says which run prompted the eviction and is not part of the
     * key. There is one entry per station, aggregated across all of its
     * runs, so a new run for any year puts it out of date.
     */
    public void invalidate(String station, int year) throws IOException {
        entries.remove(key(station));
        marks.mark(station);
    }

    /** Drops every cached summary. */
    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private Summary load(String station) throws IOException {
        String sql = QueryBuilder.select(
                        "COUNT(*) AS runs",
                        "MAX(run_at) AS latest_run",
                        "AVG(yield_t) AS mean_yield",
                        "MAX(year) AS last_year")
                .from("forecast")
                .whereEq("stnid", station)
                .build();
        ResultSetLite rs = db.query(sql);
        Row r = rs.first();
        if (r == null) {
            return new Summary(station, 0, null, 0.0, 0);
        }
        return new Summary(
                station,
                r.intVal("runs"),
                r.str("latest_run"),
                r.dbl("mean_yield"),
                r.intVal("last_year"));
    }

    // The summary is aggregated across every run for the station, so the
    // entry is keyed by the station alone, for reads and evictions alike.
    private static String key(String station) {
        return Strings.normStation(station);
    }
}

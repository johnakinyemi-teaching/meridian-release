/* util/forecast.js - shared forecast selection and yield roll-up.
 *
 * MRD-253: centralizes the forecast calculations used by Trends
 * and Compare. Implements the shared rule agreed with Frontier
 * in MRD-252.
 *
 * ES5 only
 */

window.Meridian = window.Meridian || {};

window.Meridian.Forecast = (function () {

    function runAtMs(runAt) {
        return Date.parse(String(runAt).trim().replace(' ', 'T'));
    }

    /* Greatest year, then latest timestamp.
     * Equal or unparseable timestamps fall back to the later row.
     */
    function currentRun(rows) {
        if (!rows || !rows.length) {
            return null;
        }

        var best = rows[0];

        for (var i = 1; i < rows.length; i++) {
            var r = rows[i];

            if (r.year > best.year) {
                best = r;
            } else if (r.year === best.year) {
                var a = runAtMs(r.run_at);
                var b = runAtMs(best.run_at);

                if (isNaN(a) || isNaN(b) || a >= b) {
                    best = r;
                }
            }
        }

        return best;
    }

    /* Minimum, maximum and current yield,
     * with a year-ordered yield history.
     */
    function spread(rows, preserveOrder) {
        if (!rows || !rows.length) {
            return {
                runs: 0,
                min: null,
                max: null,
                current: null,
                year: null,
                history: []
            };
        }

        var byYear = rows.slice();

        if (!preserveOrder) {
            byYear.sort(function (a, b) {
                return a.year - b.year;
            });
        }

        var min = null, max = null, history = [], i, y;

        for (i = 0; i < byYear.length; i++) {
            y = Number(byYear[i].yield_t);
            history.push(y);

            if (min === null || y < min) {
                min = y;
            }

            if (max === null || y > max) {
                max = y;
            }
        }

        var cur = currentRun(rows);

        return {
            runs: rows.length,
            min: min,
            max: max,
            current: cur ? Number(cur.yield_t) : null,
            year: cur ? cur.year : null,
            history: history
        };
    }

    return {
        currentRun: currentRun,
        spread: spread
    };

})();
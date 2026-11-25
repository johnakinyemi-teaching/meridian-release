/* util/quality.js - shared quality-control rules.
 *
 * MRD-253: centralizes the QC thresholds and flag labels used
 * by the legacy console. Both api.js and views/quality.js
 * consume this module.
 *
 * Cross-console consistency with ui-next is verified by tests.
 * ES5 only; no build step required.
 */

window.Meridian = window.Meridian || {};
window.Meridian.Quality = window.Meridian.Quality || {};

/* Physical bounds from the agronomy group, 2017-03 memo,
 * revised 2019-08 (ET ceiling raised from 10 to 12 after
 * the drought-year calibration).
 * Units: sw mm, et mm/day, drain mm/day, lai m2/m2.
 */
window.Meridian.Quality.RULES = {
    swMin: 0,
    swMax: 400,
    etMin: 0,
    etMax: 12,
    drainMin: 0,
    laiMin: 0,
    laiMax: 9,
    biomDropFrac: 0.15
};

window.Meridian.Quality.FLAG_LABELS = {
    'sw-range':  'Soil water out of range',
    'et-range':  'ET out of range',
    'drain-neg': 'Negative drainage',
    'lai-range': 'LAI out of range',
    'biom-drop': 'Biomass drop > 15%'
};

package com.quantfinlib.risk.simm;

/**
 * What a CRIF row measures. Curvature has no CRIF row of its own: it is
 * derived from the vega rows, as in the SIMM methodology.
 */
public enum SimmRiskMeasure {
    /** First-order sensitivity to the risk factor. */
    DELTA,
    /** Volatility-weighted vega. Feeds both vega and curvature margin. */
    VEGA,
    /** Sensitivity to credit-index base correlation (Credit Qualifying only). */
    BASE_CORRELATION
}

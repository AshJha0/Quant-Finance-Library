/**
 * An initial-margin calculator that follows the published structure of the
 * ISDA Standard Initial Margin Model (SIMM): six risk classes, delta, vega,
 * curvature and base-correlation margins, concentration thresholds,
 * risk-class aggregation inside each product class with psi correlations,
 * and a total that sums the product classes.
 *
 * <p><b>Licensing.</b> ISDA SIMM is a methodology owned by ISDA and is
 * licensed by ISDA for production use. This package is an independent
 * implementation of the publicly described aggregation structure for
 * learning and testing. It is not certified, not endorsed by ISDA, and is
 * not a substitute for a licensed SIMM implementation.</p>
 *
 * <p><b>Calibration.</b> Risk weights, correlations and thresholds change
 * with every SIMM version. They are data here, held in
 * {@link com.quantfinlib.risk.simm.SimmParameters} and loadable from a
 * simple {@code key = value} text file. The bundled set,
 * {@link com.quantfinlib.risk.simm.SimmParameters#illustrative()}, is made
 * of round illustrative numbers and is not any official SIMM version.</p>
 *
 * <p>Input is CRIF-like: {@link com.quantfinlib.risk.simm.CrifSensitivity}
 * rows (product class, risk type, qualifier, bucket, label1, label2,
 * amount), parsed by {@link com.quantfinlib.risk.simm.CrifSensitivity#parseCsv}.
 * The entry point is {@link com.quantfinlib.risk.simm.SimmCalculator}.</p>
 */
package com.quantfinlib.risk.simm;

package com.quantfinlib.pricing;

/**
 * Dupire (1994) local volatility from an implied total-variance surface,
 * in Gatheral's form (Gatheral 2006, <i>The Volatility Surface</i>,
 * equation 1.10):
 *
 * <pre>
 *   σ_loc²(T, y) = ∂_T w / [ 1 − (y/w) ∂_y w + ¼(−¼ − 1/w + y²/w²)(∂_y w)² + ½ ∂_yy w ]
 * </pre>
 *
 * <p>with w = w(T, y) total implied variance and y = ln(K/F(T)). The
 * denominator is the Gatheral-Jacquier density factor g(y), so it is
 * positive exactly where the surface has no butterfly arbitrage and the
 * numerator is non-negative exactly where it has no calendar arbitrage.
 * Derivatives are central differences (step 1e-4 in y and
 * min(1e-4, t/4) in T) on the surface, so any {@link TotalVarianceSurface}
 * works; at a pillar of a piecewise-linear θ_t the T-difference averages the
 * two one-sided slopes.</p>
 *
 * <p>Local vol is a function of calendar time t and spot S, with
 * y = ln(S/F(t)). Times below {@link #MIN_TIME} are evaluated at
 * {@code MIN_TIME}, since the short-expiry limit of a parametric surface is
 * not identified by the quotes. A non-positive numerator or denominator is
 * an arbitrage in the input surface and raises an exception rather than
 * returning NaN.</p>
 */
public final class DupireLocalVol {

    /** Floor on t (years) when evaluating the surface derivatives. */
    public static final double MIN_TIME = 1e-3;

    private static final double DY = 1e-4;

    private final TotalVarianceSurface surface;

    public DupireLocalVol(TotalVarianceSurface surface) {
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        this.surface = surface;
    }

    public TotalVarianceSurface surface() {
        return surface;
    }

    /** Local volatility at calendar time {@code t} (years) and spot {@code s}. */
    public double localVol(double t, double s) {
        if (!(s > 0) || !(t >= 0) || !Double.isFinite(s)) {
            throw new IllegalArgumentException("spot must be > 0 and t >= 0");
        }
        double tt = Math.max(t, MIN_TIME);
        return localVolAtMoneyness(tt, Math.log(s / surface.forward(tt)));
    }

    /** Local volatility at time {@code t} &gt; 0 and log-moneyness y = ln(S/F(t)). */
    public double localVolAtMoneyness(double t, double y) {
        if (!(t > 0) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("t must be > 0 and y finite");
        }
        double w = surface.totalVariance(t, y);
        double wu = surface.totalVariance(t, y + DY);
        double wd = surface.totalVariance(t, y - DY);
        double wy = (wu - wd) / (2 * DY);
        double wyy = (wu - 2 * w + wd) / (DY * DY);
        double dt = Math.min(1e-4, t / 4);
        double wt = (surface.totalVariance(t + dt, y) - surface.totalVariance(t - dt, y)) / (2 * dt);
        double den = SviSlice.densityFactor(y, w, wy, wyy);
        if (!(wt > 0) || !(den > 0)) {
            throw new IllegalStateException(
                    "surface admits arbitrage at t=" + t + ", y=" + y + " (dw/dT=" + wt + ", g=" + den + ")");
        }
        return Math.sqrt(wt / den);
    }
}

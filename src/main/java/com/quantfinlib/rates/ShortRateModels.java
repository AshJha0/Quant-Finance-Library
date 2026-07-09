package com.quantfinlib.rates;

/**
 * The three classic short-rate models, each answering "what is a
 * zero-coupon bond worth if the short rate follows this SDE?" in closed
 * form — the workhorse trio of rates risk factor modeling:
 *
 * <ul>
 *   <li><b>Vasicek</b> {@code dr = a(b − r)dt + σ dW} — Gaussian,
 *       tractable everywhere, and honest about its flaw: rates can go
 *       negative (which, post-2015, is a feature as much as a bug);</li>
 *   <li><b>CIR</b> {@code dr = a(b − r)dt + σ√r dW} — the square-root
 *       diffusion keeps rates non-negative (strictly positive when the
 *       Feller condition {@code 2ab ≥ σ²} holds), at the cost of
 *       fatter formulas;</li>
 *   <li><b>Hull-White</b> — Vasicek with a time-dependent drift fitted
 *       so the model reprices TODAY'S curve exactly: the standard
 *       production choice, because a rates model that disagrees with
 *       the discount curve it hedges against is wrong by construction.
 *       Bond prices come from the curve plus a Gaussian convexity
 *       adjustment; no explicit θ(t) is needed for pricing.</li>
 * </ul>
 *
 * <p>All prices are for unit face. Simulation steps (exact Gaussian for
 * Vasicek, full-truncation Euler for CIR) are provided for Monte Carlo
 * scenario generation — the rates leg of a risk-factor simulation.
 * Static, allocation-free per call, research lane. Calibration of
 * (a, σ) to market instruments is the caller's optimization exercise;
 * these classes price and simulate given parameters.</p>
 */
public final class ShortRateModels {

    private ShortRateModels() {
    }

    // ------------------------------------------------------------------
    // Vasicek
    // ------------------------------------------------------------------

    /** Vasicek zero-coupon bond price P(t, t+T) given the short rate now. */
    public static double vasicekBond(double shortRate, double a, double b, double sigma,
                                     double maturityYears) {
        requirePositive(a, "a");
        requireNonNegative(sigma, "sigma");
        requirePositive(maturityYears, "maturityYears");
        double bT = (1 - Math.exp(-a * maturityYears)) / a;
        double logA = (bT - maturityYears) * (b - sigma * sigma / (2 * a * a))
                - sigma * sigma * bT * bT / (4 * a);
        return Math.exp(logA - bT * shortRate);
    }

    /** The continuously-compounded zero yield implied by {@link #vasicekBond}. */
    public static double vasicekYield(double shortRate, double a, double b, double sigma,
                                      double maturityYears) {
        return -Math.log(vasicekBond(shortRate, a, b, sigma, maturityYears)) / maturityYears;
    }

    /**
     * One EXACT Vasicek simulation step (the transition is Gaussian, so
     * no discretization error): {@code r ← b + (r−b)e^{−aΔt} + stdev·z}.
     */
    public static double vasicekStep(double shortRate, double a, double b, double sigma,
                                     double dtYears, double gaussian) {
        double decay = Math.exp(-a * dtYears);
        double stdev = sigma * Math.sqrt((1 - decay * decay) / (2 * a));
        return b + (shortRate - b) * decay + stdev * gaussian;
    }

    // ------------------------------------------------------------------
    // CIR
    // ------------------------------------------------------------------

    /** CIR zero-coupon bond price P(t, t+T). */
    public static double cirBond(double shortRate, double a, double b, double sigma,
                                 double maturityYears) {
        requirePositive(a, "a");
        requirePositive(sigma, "sigma");
        requirePositive(maturityYears, "maturityYears");
        requireNonNegative(shortRate, "shortRate");
        double h = Math.sqrt(a * a + 2 * sigma * sigma);
        double expHt = Math.exp(h * maturityYears);
        double denom = 2 * h + (a + h) * (expHt - 1);
        double bT = 2 * (expHt - 1) / denom;
        double aT = Math.pow(2 * h * Math.exp((a + h) * maturityYears / 2) / denom,
                2 * a * b / (sigma * sigma));
        return aT * Math.exp(-bT * shortRate);
    }

    /** The Feller ratio 2ab/σ²; ≥ 1 keeps the CIR rate strictly positive. */
    public static double cirFeller(double a, double b, double sigma) {
        return 2 * a * b / (sigma * sigma);
    }

    /** One full-truncation Euler CIR step (never sources vol from a negative rate). */
    public static double cirStep(double shortRate, double a, double b, double sigma,
                                 double dtYears, double gaussian) {
        double rPlus = Math.max(shortRate, 0);
        return shortRate + a * (b - rPlus) * dtYears
                + sigma * Math.sqrt(rPlus * dtYears) * gaussian;
    }

    // ------------------------------------------------------------------
    // Hull-White (one factor, fitted to a market curve)
    // ------------------------------------------------------------------

    /**
     * Hull-White zero-coupon bond price P(t, t+T) given the market curve
     * and the short rate now. By construction, at {@code t = 0} with
     * {@code shortRate = f(0, 0)} this reprices the curve exactly; away
     * from it, the Gaussian convexity adjustment applies:
     *
     * <pre>  P(t,T) = [P(0,T)/P(0,t)] · exp(B(f(0,t) − r) − σ²B²(1−e^{−2at})/(4a))</pre>
     *
     * @param curve      today's discount curve
     * @param tYears     valuation time (0 = today)
     * @param maturityYears time FROM t to the bond's maturity
     * @param shortRate  the simulated short rate at t
     * @param a          mean-reversion speed
     * @param sigma      short-rate vol
     */
    public static double hullWhiteBond(YieldCurve curve, double tYears, double maturityYears,
                                       double shortRate, double a, double sigma) {
        requirePositive(a, "a");
        requireNonNegative(sigma, "sigma");
        requirePositive(maturityYears, "maturityYears");
        requireNonNegative(tYears, "tYears");
        double bT = (1 - Math.exp(-a * maturityYears)) / a;
        double pT = curve.discountFactor(tYears + maturityYears);
        double pt = tYears == 0 ? 1.0 : curve.discountFactor(tYears);
        double fwd = instantaneousForward(curve, tYears);
        double variance = sigma * sigma * (1 - Math.exp(-2 * a * tYears)) / (4 * a);
        return (pT / pt) * Math.exp(bT * (fwd - shortRate) - variance * bT * bT);
    }

    /**
     * The instantaneous forward rate f(0, t) off the curve, by symmetric
     * finite difference of ln P (the curve carries no analytic derivative).
     */
    public static double instantaneousForward(YieldCurve curve, double tYears) {
        requireNonNegative(tYears, "tYears");
        double h = 1.0 / 365;
        if (tYears < h) {
            // A centered window would clamp into a one-sided average of
            // f near (t+h)/2, not t — biased on a steep money-market end.
            // Second-order one-sided stencil of g(t) = −ln P(t) instead
            // (exact whenever g is locally quadratic).
            double g0 = -Math.log(curve.discountFactor(tYears));
            double g1 = -Math.log(curve.discountFactor(tYears + h));
            double g2 = -Math.log(curve.discountFactor(tYears + 2 * h));
            return (-3 * g0 + 4 * g1 - g2) / (2 * h);
        }
        double lo = tYears - h;
        double hi = tYears + h;
        return -(Math.log(curve.discountFactor(hi)) - Math.log(curve.discountFactor(lo)))
                / (hi - lo);
    }

    private static void requirePositive(double x, String name) {
        if (!(x > 0) || x == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }

    private static void requireNonNegative(double x, String name) {
        if (!(x >= 0) || x == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " must be >= 0 and finite");
        }
    }
}

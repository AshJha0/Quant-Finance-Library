package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;

/**
 * CREDIT CURVE — piecewise-constant hazard rates bootstrapped from CDS par
 * spreads, the credit market's exact analogue of {@link YieldCurve}'s
 * bootstrap: walk the quotes from shortest to longest, at each pillar
 * solving for the one hazard rate that reprices that maturity's CDS to
 * zero upfront given everything already solved.
 *
 * <p>The objects: the HAZARD RATE h(t) is the instantaneous default
 * intensity ("conditional on surviving to t, the annualized probability
 * of defaulting right now"); SURVIVAL is its exponential integral,
 * {@code Q(t) = exp(-integral of h)} — piecewise-constant h makes that a
 * product of exponentials, evaluated exactly. The rule-of-thumb every
 * desk carries — the CREDIT TRIANGLE {@code spread ~ h * (1 - R)} — falls
 * out of setting premium = protection on a flat curve, and the tests pin
 * this class against it.</p>
 *
 * <p>Leg discretization (stated): quarterly grid, premium leg
 * {@code S * sum 0.25 * DF(t_i) * Q(t_i)} plus the standard
 * accrual-on-default half-period term, protection leg
 * {@code (1-R) * sum DF(t_i) * (Q(t_{i-1}) - Q(t_i))} with discounting at
 * period end — the textbook discrete form (O(dt) bias vs the integral,
 * ~0.1bp at these grids, stated not hidden). Recovery is a single number
 * for the whole curve, the market's quoting convention (40% senior
 * unsecured). Solving is bisection per pillar on h in [1e-9, 10] with an
 * explicit bracket check — a quote no hazard can explain throws rather
 * than returning the bound. Research lane, deterministic.</p>
 */
public final class CreditCurve {

    private static final double DT = 0.25;

    private final double[] pillarTimes;   // ascending, years
    private final double[] hazards;       // piecewise-constant on (prev, pillar]
    private final double recovery;

    private CreditCurve(double[] pillarTimes, double[] hazards, double recovery) {
        this.pillarTimes = pillarTimes;
        this.hazards = hazards;
        this.recovery = recovery;
    }

    /**
     * Bootstraps from CDS par spreads.
     *
     * @param tenorYears ascending integer-year pillars, &ge; 1
     * @param parSpreads par CDS spreads (decimal: 0.01 = 100bp), &gt; 0
     * @param recovery   assumed recovery rate in [0, 1)
     * @param discount   risk-free discounting curve
     */
    public static CreditCurve bootstrap(int[] tenorYears, double[] parSpreads,
                                        double recovery, YieldCurve discount) {
        int n = tenorYears.length;
        if (n == 0 || parSpreads.length != n) {
            throw new IllegalArgumentException("need aligned, non-empty tenors/spreads");
        }
        if (!(recovery >= 0) || !(recovery < 1)) {
            throw new IllegalArgumentException("recovery must be in [0, 1), got " + recovery);
        }
        int prev = 0;
        for (int i = 0; i < n; i++) {
            if (tenorYears[i] <= prev) {
                throw new IllegalArgumentException("tenors must be ascending positive integers");
            }
            prev = tenorYears[i];
            if (!(parSpreads[i] > 0) || parSpreads[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "spread must be positive and finite: " + parSpreads[i]);
            }
        }
        double[] times = new double[n];
        double[] hazards = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = tenorYears[i];
        }
        CreditCurve curve = new CreditCurve(times, hazards, recovery);
        for (int k = 0; k < n; k++) {
            double spread = parSpreads[k];
            double maturity = times[k];
            double lo = 1e-9, hi = 10;
            double upLo = curve.upfrontWithPillar(k, lo, spread, maturity, discount);
            double upHi = curve.upfrontWithPillar(k, hi, spread, maturity, discount);
            if (upLo * upHi > 0) {
                throw new IllegalArgumentException("pillar " + tenorYears[k]
                        + "y spread " + spread + " has no hazard in [1e-9, 10]");
            }
            for (int it = 0; it < 200; it++) {
                double mid = 0.5 * (lo + hi);
                double up = curve.upfrontWithPillar(k, mid, spread, maturity, discount);
                if (up * upLo > 0) {
                    lo = mid;
                    upLo = up;
                } else {
                    hi = mid;
                }
            }
            hazards[k] = 0.5 * (lo + hi);
        }
        return curve;
    }

    private double upfrontWithPillar(int k, double trialHazard, double spread,
                                     double maturity, YieldCurve discount) {
        double saved = hazards[k];
        hazards[k] = trialHazard;
        double up = CdsPricer.protectionLegPv(this, discount, maturity)
                - spread * CdsPricer.riskyAnnuity(this, discount, maturity);
        hazards[k] = saved;
        return up;
    }

    /** Survival probability Q(t), exact under piecewise-constant hazards. */
    public double survivalProbability(double t) {
        if (!(t >= 0) || t == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("t must be >= 0 and finite, got " + t);
        }
        double integral = 0;
        double from = 0;
        for (int i = 0; i < pillarTimes.length && from < t; i++) {
            double to = Math.min(pillarTimes[i], t);
            if (to > from) {
                integral += hazards[i] * (to - from);
                from = to;
            }
        }
        if (from < t) { // flat extrapolation beyond the last pillar
            integral += hazards[hazards.length - 1] * (t - from);
        }
        return Math.exp(-integral);
    }

    /** Cumulative default probability 1 - Q(t). */
    public double defaultProbability(double t) {
        return 1 - survivalProbability(t);
    }

    /** The hazard rate in force at time t (flat beyond the last pillar). */
    public double hazard(double t) {
        if (!(t >= 0)) {
            throw new IllegalArgumentException("t must be >= 0, got " + t);
        }
        for (int i = 0; i < pillarTimes.length; i++) {
            if (t <= pillarTimes[i]) {
                return hazards[i];
            }
        }
        return hazards[hazards.length - 1];
    }

    public double recovery() {
        return recovery;
    }

    /** The quarterly grid step shared with {@link CdsPricer}. */
    static double gridStep() {
        return DT;
    }
}

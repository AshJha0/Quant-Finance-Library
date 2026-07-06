package com.quantfinlib.fx;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * FX-style volatility surface built from the market's <em>delta-quoted</em>
 * smile: ATM (delta-neutral straddle), 25-delta risk reversal and butterfly,
 * optionally 10-delta wings.
 *
 * <p>FX options are not quoted by strike. A broker screen shows, per expiry:
 * ATM vol, RR25 (call vol − put vol) and BF25 ((call+put)/2 − ATM). This
 * class converts those quotes to the five pillar vols</p>
 *
 * <pre>
 *   vol(25Δ call) = atm + bf25 + rr25 / 2
 *   vol(25Δ put)  = atm + bf25 − rr25 / 2      (same shape at 10Δ)
 * </pre>
 *
 * <p>then solves each pillar's <b>strike</b> from its delta and its own vol,
 * producing an absolute strike/vol smile that strike-based pricers
 * ({@code pricing.BlackScholes}, {@code pricing.VannaVolga}) can consume.</p>
 *
 * <h2>Delta convention</h2>
 * <p>Forward delta ({@code N(d1)}), the standard for long-dated and EM pairs.
 * With {@link Builder#premiumAdjusted}, premium-adjusted forward delta
 * ({@code (K/F)·N(d2)}) is used instead — the convention for pairs whose
 * premium is paid in base currency (e.g. USDJPY). Premium-adjusted call
 * deltas are non-monotone in strike; this class resolves the ambiguity the
 * way the market does, taking the OTM (higher-strike) solution.</p>
 *
 * <h2>Interpolation</h2>
 * <p>Within an expiry: linear in vol against log-moneyness, flat beyond the
 * 10Δ (or 25Δ) wings. Across expiries: linear in total variance σ²τ, flat
 * outside the quoted range. Forwards interpolate log-linearly in time.
 * Lookups after {@link Builder#build} are allocation-free.</p>
 */
public final class FxVolSurface {

    /** One expiry's solved smile: absolute strikes and vols, low to high strike. */
    public record SmilePillar(double expiryYears, double forward, double[] strikes, double[] vols) {
    }

    private final double[] expiries;         // ascending
    private final double[] forwards;         // per expiry
    private final double[][] logMoneyness;   // per expiry, ascending ln(K/F)
    private final double[][] vols;           // per expiry, aligned with logMoneyness
    private final boolean premiumAdjusted;

    private FxVolSurface(double[] expiries, double[] forwards, double[][] logMoneyness,
                         double[][] vols, boolean premiumAdjusted) {
        this.expiries = expiries;
        this.forwards = forwards;
        this.logMoneyness = logMoneyness;
        this.vols = vols;
        this.premiumAdjusted = premiumAdjusted;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates per-expiry delta quotes, then solves strikes once in {@link #build}. */
    public static final class Builder {
        private record Quote(double t, double forward, double atm, double rr25, double bf25,
                             double rr10, double bf10) {
        }

        private final List<Quote> quotes = new ArrayList<>();
        private boolean premiumAdjusted;

        /** 25Δ-only smile quote (rr/bf in absolute vol, e.g. 0.01 = 1 vol point). */
        public Builder add(double expiryYears, double forward, double atmVol,
                           double rr25, double bf25) {
            return add(expiryYears, forward, atmVol, rr25, bf25, Double.NaN, Double.NaN);
        }

        /** Full five-pillar quote with 10Δ wings. */
        public Builder add(double expiryYears, double forward, double atmVol,
                           double rr25, double bf25, double rr10, double bf10) {
            if (expiryYears <= 0 || forward <= 0 || atmVol <= 0) {
                throw new IllegalArgumentException("expiry, forward and atm vol must be > 0");
            }
            quotes.add(new Quote(expiryYears, forward, atmVol, rr25, bf25, rr10, bf10));
            return this;
        }

        /** Switches strike solving to premium-adjusted forward delta. */
        public Builder premiumAdjusted(boolean value) {
            this.premiumAdjusted = value;
            return this;
        }

        public FxVolSurface build() {
            if (quotes.isEmpty()) {
                throw new IllegalStateException("at least one expiry quote required");
            }
            quotes.sort((a, b) -> Double.compare(a.t, b.t));
            int n = quotes.size();
            double[] ts = new double[n];
            double[] fwds = new double[n];
            double[][] lm = new double[n][];
            double[][] vv = new double[n][];
            for (int i = 0; i < n; i++) {
                Quote q = quotes.get(i);
                ts[i] = q.t;
                fwds[i] = q.forward;
                boolean tenDelta = !Double.isNaN(q.rr10);
                // Pillar vols from the broker quote (see class doc).
                double v25c = q.atm + q.bf25 + q.rr25 / 2;
                double v25p = q.atm + q.bf25 - q.rr25 / 2;
                double v10c = tenDelta ? q.atm + q.bf10 + q.rr10 / 2 : Double.NaN;
                double v10p = tenDelta ? q.atm + q.bf10 - q.rr10 / 2 : Double.NaN;
                // Each pillar's strike is solved with its own vol — the market's
                // definition of the quoted smile points.
                double kAtm = dnsStrike(q.forward, q.atm, q.t, premiumAdjusted);
                double k25c = strikeForDelta(q.forward, v25c, q.t, 0.25, true, premiumAdjusted);
                double k25p = strikeForDelta(q.forward, v25p, q.t, -0.25, false, premiumAdjusted);
                double k10c = tenDelta
                        ? strikeForDelta(q.forward, v10c, q.t, 0.10, true, premiumAdjusted) : 0;
                double k10p = tenDelta
                        ? strikeForDelta(q.forward, v10p, q.t, -0.10, false, premiumAdjusted) : 0;
                double[] ks = tenDelta
                        ? new double[]{k10p, k25p, kAtm, k25c, k10c}
                        : new double[]{k25p, kAtm, k25c};
                double[] vs = tenDelta
                        ? new double[]{v10p, v25p, q.atm, v25c, v10c}
                        : new double[]{v25p, q.atm, v25c};
                // Store as log-moneyness so time interpolation is forward-relative.
                double[] x = new double[ks.length];
                for (int j = 0; j < ks.length; j++) {
                    if (j > 0 && ks[j] <= ks[j - 1]) {
                        throw new IllegalStateException(
                                "solved strikes not increasing at expiry " + q.t
                                        + " — check rr/bf signs and magnitudes");
                    }
                    x[j] = Math.log(ks[j] / q.forward);
                }
                lm[i] = x;
                vv[i] = vs;
            }
            return new FxVolSurface(ts, fwds, lm, vv, premiumAdjusted);
        }
    }

    // ------------------------------------------------------------------
    // Delta ↔ strike (static, reusable by hedgers and exotic pricers)
    // ------------------------------------------------------------------

    /**
     * Delta-neutral-straddle (ATM) strike: {@code F·e^{+σ²τ/2}} for forward
     * delta, {@code F·e^{−σ²τ/2}} premium-adjusted. At this strike the call
     * and put deltas cancel exactly.
     */
    public static double dnsStrike(double forward, double vol, double tYears,
                                   boolean premiumAdjusted) {
        double half = 0.5 * vol * vol * tYears;
        return forward * Math.exp(premiumAdjusted ? -half : half);
    }

    /**
     * Strike for a target forward delta (call delta in (0,1), put delta in
     * (−1,0)). Unadjusted deltas invert in closed form; premium-adjusted
     * deltas are solved by bisection on the OTM branch.
     */
    public static double strikeForDelta(double forward, double vol, double tYears,
                                        double delta, boolean isCall, boolean premiumAdjusted) {
        if (isCall ? (delta <= 0 || delta >= 1) : (delta >= 0 || delta <= -1)) {
            throw new IllegalArgumentException("delta out of range for " + (isCall ? "call" : "put")
                    + ": " + delta);
        }
        double sqrtT = Math.sqrt(tYears);
        double sv = vol * sqrtT;
        if (!premiumAdjusted) {
            // Call: Δ = N(d1) → d1 = N⁻¹(Δ). Put: Δ = −N(−d1) → d1 = −N⁻¹(−Δ).
            double d1 = isCall ? MathUtils.normInv(delta) : -MathUtils.normInv(-delta);
            return forward * Math.exp(-d1 * sv + 0.5 * sv * sv);
        }
        // Premium-adjusted: call Δpa = (K/F)·N(d2), put Δpa = −(K/F)·N(−d2).
        double lo = forward * Math.exp(-8 * sv);
        double hi = forward * Math.exp(8 * sv);
        if (isCall) {
            // (K/F)N(d2) rises then falls in K; the market takes the OTM
            // (falling, higher-strike) branch. Coarse-scan for the peak,
            // then bisect to its right.
            double peak = lo;
            double peakVal = -1;
            for (int i = 0; i <= 200; i++) {
                double k = lo * Math.pow(hi / lo, i / 200.0);
                double v = paCallDelta(forward, k, sv);
                if (v > peakVal) {
                    peakVal = v;
                    peak = k;
                }
            }
            if (delta > peakVal) {
                throw new IllegalArgumentException(
                        "premium-adjusted call delta " + delta + " unattainable (max "
                                + peakVal + ") at vol " + vol + ", t " + tYears);
            }
            return bisect(k -> paCallDelta(forward, k, sv) - delta, peak, hi);
        }
        // Put delta is monotone decreasing in K over the whole bracket.
        return bisect(k -> -(k / forward) * MathUtils.normCdf(-d2(forward, k, sv)) - delta, lo, hi);
    }

    private static double paCallDelta(double forward, double strike, double sv) {
        return (strike / forward) * MathUtils.normCdf(d2(forward, strike, sv));
    }

    private static double d2(double forward, double strike, double sv) {
        return Math.log(forward / strike) / sv - 0.5 * sv;
    }

    /** Bisection for a function decreasing over [lo, hi] with a sign change. */
    private static double bisect(java.util.function.DoubleUnaryOperator f, double lo, double hi) {
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (f.applyAsDouble(mid) > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    // ------------------------------------------------------------------
    // Lookups (allocation-free after build)
    // ------------------------------------------------------------------

    /**
     * Interpolated vol for an absolute strike at an expiry. Wings are flat
     * beyond the quoted pillars; time interpolation is linear in total
     * variance and flat outside the quoted expiries.
     */
    public double vol(double expiryYears, double strike) {
        if (strike <= 0) {
            throw new IllegalArgumentException("strike must be > 0: " + strike);
        }
        int n = expiries.length;
        // Boundary expiries: flat in the edge smile, at that pillar's forward.
        if (expiryYears <= expiries[0]) {
            return smileVol(0, Math.log(strike / forwards[0]));
        }
        if (expiryYears >= expiries[n - 1]) {
            return smileVol(n - 1, Math.log(strike / forwards[n - 1]));
        }
        // ONE bracket search serves both the forward interpolation and the
        // variance interpolation — this is the documented hot lookup.
        int lo = bracket(expiryYears);
        int hi = lo + 1;
        double w = (expiryYears - expiries[lo]) / (expiries[hi] - expiries[lo]);
        double forward = Math.exp(Math.log(forwards[lo])
                + w * (Math.log(forwards[hi]) - Math.log(forwards[lo])));
        double x = Math.log(strike / forward);
        // Total-variance interpolation keeps calendar arbitrage at bay when
        // the smiles are themselves arbitrage-free.
        double vLo = smileVol(lo, x);
        double vHi = smileVol(hi, x);
        double wLo = vLo * vLo * expiries[lo];
        double wHi = vHi * vHi * expiries[hi];
        double totalVar = wLo + (wHi - wLo) * w;
        return Math.sqrt(totalVar / expiryYears);
    }

    /** ATM (delta-neutral straddle) vol at an expiry: the smile at zero skew. */
    public double atmVol(double expiryYears) {
        // Seed with the mid-pillar vol; one fixpoint pass is ample for ATM.
        int nearest = nearestExpiry(expiryYears);
        double seed = vols[nearest][vols[nearest].length / 2];
        return vol(expiryYears,
                dnsStrike(forwardAt(expiryYears), seed, expiryYears, premiumAdjusted));
    }

    /** Log-linear interpolated forward at an expiry, flat outside pillars. */
    public double forwardAt(double expiryYears) {
        int n = expiries.length;
        if (expiryYears <= expiries[0]) {
            return forwards[0];
        }
        if (expiryYears >= expiries[n - 1]) {
            return forwards[n - 1];
        }
        int lo = bracket(expiryYears);
        double w = (expiryYears - expiries[lo]) / (expiries[lo + 1] - expiries[lo]);
        return Math.exp(Math.log(forwards[lo])
                + w * (Math.log(forwards[lo + 1]) - Math.log(forwards[lo])));
    }

    /** Binary search: largest pillar index with expiry <= t (interior t only). */
    private int bracket(double t) {
        int lo = 0;
        int hi = expiries.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (expiries[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The solved pillar smile at index {@code i} (reporting, VannaVolga inputs). */
    public SmilePillar pillar(int i) {
        double[] ks = new double[logMoneyness[i].length];
        for (int j = 0; j < ks.length; j++) {
            ks[j] = forwards[i] * Math.exp(logMoneyness[i][j]);
        }
        return new SmilePillar(expiries[i], forwards[i], ks, vols[i].clone());
    }

    public int pillarCount() {
        return expiries.length;
    }

    public boolean isPremiumAdjusted() {
        return premiumAdjusted;
    }

    /** Linear interp in log-moneyness within expiry {@code i}, flat wings. */
    private double smileVol(int i, double x) {
        double[] xs = logMoneyness[i];
        double[] vs = vols[i];
        if (x <= xs[0]) {
            return vs[0];
        }
        int last = xs.length - 1;
        if (x >= xs[last]) {
            return vs[last];
        }
        int lo = 0;
        while (xs[lo + 1] < x) {
            lo++;
        }
        double w = (x - xs[lo]) / (xs[lo + 1] - xs[lo]);
        return vs[lo] + w * (vs[lo + 1] - vs[lo]);
    }

    private int nearestExpiry(double t) {
        int best = 0;
        for (int i = 1; i < expiries.length; i++) {
            if (Math.abs(expiries[i] - t) < Math.abs(expiries[best] - t)) {
                best = i;
            }
        }
        return best;
    }
}

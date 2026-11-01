package com.quantfinlib.fx;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FX volatility surface built from the market's delta-quoted smile: ATM,
 * 25-delta risk reversal and butterfly, optionally 10-delta wings.
 *
 * <p>Pillar vols (Reiswich and Wystup 2010, "FX volatility smile
 * construction", Wilmott; Clark 2011, <i>Foreign Exchange Option Pricing</i>,
 * chapter 3). The smile (or "smile strangle") butterfly BF_s defines</p>
 *
 * <pre>
 *   σ(Δ call) = σ_ATM + BF_s + RR / 2
 *   σ(Δ put)  = σ_ATM + BF_s − RR / 2
 * </pre>
 *
 * <p>and each pillar strike is solved from its delta at its own vol. A broker
 * screen does not show BF_s. It shows the market (broker) strangle BF_m: one
 * vol σ_ATM + BF_m at which a Δ-strangle is priced, its two strikes solved at
 * that single vol. With {@link ButterflyQuote#BROKER_STRANGLE} (the default)
 * the builder finds BF_s so that the market strangle's strikes, priced on the
 * resulting smile, are worth the same as the strangle priced at σ_ATM + BF_m
 * (Clark 2011, section 3.6). With RR = 0 the two coincide; with skew BF_s is
 * larger, by up to a few tenths of a vol on skewed pairs. With 10-delta
 * wings both levels are solved jointly. Use
 * {@link ButterflyQuote#SMILE_STRANGLE} when the input already is BF_s.</p>
 *
 * <h2>Conventions</h2>
 * <p>{@link DeltaConvention}: forward or spot delta, each with or without
 * premium adjustment. Spot deltas need the foreign discount factor
 * e^{−r_f T}, supplied per expiry through {@link Builder#add(double, double,
 * double, double, double, double, double, double)}. {@link AtmConvention}:
 * delta-neutral straddle (the usual quote) or ATM forward (K = F, used for
 * long-dated and some EM pairs). The choice of convention per pair and tenor
 * belongs to the caller; Clark (2011) table 3.1 lists the common ones.</p>
 *
 * <h2>Interpolation</h2>
 * <p>Within an expiry: linear in vol against log-moneyness ln(K/F), flat
 * beyond the outermost pillars. Across expiries: linear in total variance
 * σ²τ at fixed log-moneyness, flat outside the quoted range. Forwards
 * interpolate log-linearly in time. Lookups after {@link Builder#build} are
 * allocation-free.</p>
 */
public final class FxVolSurface {

    /** How the quoted deltas are defined (Reiswich and Wystup 2010, table 1). */
    public enum DeltaConvention {
        /** Forward delta N(d1). */
        FORWARD,
        /** Premium-adjusted forward delta (K/F) N(d2). */
        FORWARD_PREMIUM_ADJUSTED,
        /** Spot delta e^{−r_f T} N(d1). */
        SPOT,
        /** Premium-adjusted spot delta e^{−r_f T} (K/F) N(d2). */
        SPOT_PREMIUM_ADJUSTED;

        public boolean premiumAdjusted() {
            return this == FORWARD_PREMIUM_ADJUSTED || this == SPOT_PREMIUM_ADJUSTED;
        }

        public boolean spot() {
            return this == SPOT || this == SPOT_PREMIUM_ADJUSTED;
        }
    }

    /** Which strike the ATM vol refers to. */
    public enum AtmConvention {
        /** Delta-neutral straddle: call and put deltas cancel. */
        DELTA_NEUTRAL_STRADDLE,
        /** At-the-money forward: K = F. */
        FORWARD
    }

    /** What the quoted butterfly is. */
    public enum ButterflyQuote {
        /** Broker (market) strangle, priced at one vol. */
        BROKER_STRANGLE,
        /** Smile strangle: (σ_call + σ_put)/2 − σ_ATM. */
        SMILE_STRANGLE
    }

    /** One expiry's solved smile: absolute strikes and vols, low to high strike. */
    public record SmilePillar(double expiryYears, double forward, double[] strikes, double[] vols) {
    }

    private final double[] expiries;         // ascending
    private final double[] forwards;         // per expiry
    private final double[][] logMoneyness;   // per expiry, ascending ln(K/F)
    private final double[][] vols;           // per expiry, aligned with logMoneyness
    private final double[] smileBf25;
    private final double[] smileBf10;
    private final DeltaConvention deltaConvention;
    private final AtmConvention atmConvention;

    private FxVolSurface(double[] expiries, double[] forwards, double[][] logMoneyness,
                         double[][] vols, double[] smileBf25, double[] smileBf10,
                         DeltaConvention deltaConvention, AtmConvention atmConvention) {
        this.expiries = expiries;
        this.forwards = forwards;
        this.logMoneyness = logMoneyness;
        this.vols = vols;
        this.smileBf25 = smileBf25;
        this.smileBf10 = smileBf10;
        this.deltaConvention = deltaConvention;
        this.atmConvention = atmConvention;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates per-expiry delta quotes, then solves strikes once in {@link #build}. */
    public static final class Builder {
        private record Quote(double t, double forward, double foreignDf, double atm,
                             double rr25, double bf25, double rr10, double bf10) {
        }

        private final List<Quote> quotes = new ArrayList<>();
        private DeltaConvention deltaConvention = DeltaConvention.FORWARD;
        private AtmConvention atmConvention = AtmConvention.DELTA_NEUTRAL_STRADDLE;
        private ButterflyQuote butterflyQuote = ButterflyQuote.BROKER_STRANGLE;

        /** 25Δ-only smile quote (rr/bf in absolute vol, e.g. 0.01 = 1 vol point). */
        public Builder add(double expiryYears, double forward, double atmVol,
                           double rr25, double bf25) {
            return add(expiryYears, forward, 1.0, atmVol, rr25, bf25, Double.NaN, Double.NaN);
        }

        /** Full five-pillar quote with 10Δ wings. */
        public Builder add(double expiryYears, double forward, double atmVol,
                           double rr25, double bf25, double rr10, double bf10) {
            return add(expiryYears, forward, 1.0, atmVol, rr25, bf25, rr10, bf10);
        }

        /**
         * Quote with the foreign discount factor e^{−r_f T}, needed for spot
         * delta conventions. Pass NaN for rr10/bf10 when there are no 10Δ wings.
         */
        public Builder add(double expiryYears, double forward, double foreignDiscountFactor,
                           double atmVol, double rr25, double bf25, double rr10, double bf10) {
            if (!(expiryYears > 0) || !(forward > 0) || !(atmVol > 0)
                    || !Double.isFinite(expiryYears) || !Double.isFinite(forward)
                    || !Double.isFinite(atmVol)) {
                throw new IllegalArgumentException("expiry, forward and atm vol must be > 0");
            }
            if (!(foreignDiscountFactor > 0) || !Double.isFinite(foreignDiscountFactor)) {
                throw new IllegalArgumentException("foreign discount factor must be > 0");
            }
            if (!Double.isFinite(rr25) || !Double.isFinite(bf25)) {
                throw new IllegalArgumentException("rr25/bf25 must be finite");
            }
            boolean tenDelta = !Double.isNaN(rr10);
            if (tenDelta && (!Double.isFinite(rr10) || !Double.isFinite(bf10))) {
                throw new IllegalArgumentException("rr10/bf10 must be finite or both NaN");
            }
            // The quoted wing vols must be valid vols; the solved smile vols
            // are checked again in build().
            if (!(atmVol + bf25 - Math.abs(rr25) / 2 > 0)
                    || (tenDelta && !(atmVol + bf10 - Math.abs(rr10) / 2 > 0))) {
                throw new IllegalArgumentException(
                        "rr/bf combination produces a non-positive wing vol at expiry "
                                + expiryYears + " - check rr/bf signs and magnitudes");
            }
            quotes.add(new Quote(expiryYears, forward, foreignDiscountFactor, atmVol,
                    rr25, bf25, rr10, bf10));
            return this;
        }

        /** Shortcut: forward delta, premium-adjusted or not. */
        public Builder premiumAdjusted(boolean value) {
            this.deltaConvention = value
                    ? DeltaConvention.FORWARD_PREMIUM_ADJUSTED : DeltaConvention.FORWARD;
            return this;
        }

        public Builder deltaConvention(DeltaConvention value) {
            this.deltaConvention = java.util.Objects.requireNonNull(value);
            return this;
        }

        public Builder atmConvention(AtmConvention value) {
            this.atmConvention = java.util.Objects.requireNonNull(value);
            return this;
        }

        public Builder butterflyQuote(ButterflyQuote value) {
            this.butterflyQuote = java.util.Objects.requireNonNull(value);
            return this;
        }

        public FxVolSurface build() {
            if (quotes.isEmpty()) {
                throw new IllegalStateException("at least one expiry quote required");
            }
            quotes.sort(Comparator.comparingDouble(Quote::t));
            int n = quotes.size();
            double[] ts = new double[n];
            double[] fwds = new double[n];
            double[][] lm = new double[n][];
            double[][] vv = new double[n][];
            double[] bfs25 = new double[n];
            double[] bfs10 = new double[n];
            for (int i = 0; i < n; i++) {
                Quote q = quotes.get(i);
                if (i > 0 && q.t == quotes.get(i - 1).t) {
                    throw new IllegalStateException("duplicate expiry " + q.t);
                }
                ts[i] = q.t;
                fwds[i] = q.forward;
                boolean tenDelta = !Double.isNaN(q.rr10);
                double s25 = q.bf25;
                double s10 = tenDelta ? q.bf10 : Double.NaN;
                if (butterflyQuote == ButterflyQuote.BROKER_STRANGLE) {
                    // Gauss-Seidel over the two levels: each 1-D solve holds the
                    // other level's smile butterfly fixed. Converges in a few
                    // sweeps because the cross-dependence is weak.
                    for (int sweep = 0; sweep < (tenDelta ? 30 : 1); sweep++) {
                        double old25 = s25, old10 = s10;
                        s25 = solveSmileBf(q, 0.25, q.bf25, s25, s10);
                        if (tenDelta) {
                            s10 = solveSmileBf(q, 0.10, q.bf10, s25, s10);
                            if (Math.abs(s25 - old25) < 1e-14 && Math.abs(s10 - old10) < 1e-14) {
                                break;
                            }
                        }
                    }
                }
                double[][] smile = pillars(q, s25, s10);
                lm[i] = smile[0];
                vv[i] = smile[1];
                bfs25[i] = s25;
                bfs10[i] = s10;
            }
            return new FxVolSurface(ts, fwds, lm, vv, bfs25, bfs10, deltaConvention, atmConvention);
        }

        /**
         * Pillar log-moneyness and vols for given smile butterflies;
         * {@code [0]} = ln(K/F) ascending, {@code [1]} = vols.
         */
        private double[][] pillars(Quote q, double sbf25, double sbf10) {
            boolean tenDelta = !Double.isNaN(q.rr10);
            double v25c = q.atm + sbf25 + q.rr25 / 2;
            double v25p = q.atm + sbf25 - q.rr25 / 2;
            double v10c = tenDelta ? q.atm + sbf10 + q.rr10 / 2 : Double.NaN;
            double v10p = tenDelta ? q.atm + sbf10 - q.rr10 / 2 : Double.NaN;
            if (!(v25c > 0) || !(v25p > 0) || (tenDelta && (!(v10c > 0) || !(v10p > 0)))) {
                throw new IllegalStateException("smile wing vol not positive at expiry " + q.t
                        + " - check rr/bf signs and magnitudes");
            }
            double kAtm = atmStrike(q.forward, q.atm, q.t, deltaConvention, atmConvention);
            double k25c = strikeForDelta(q.forward, v25c, q.t, 0.25, true, deltaConvention, q.foreignDf);
            double k25p = strikeForDelta(q.forward, v25p, q.t, -0.25, false, deltaConvention, q.foreignDf);
            double[] ks;
            double[] vs;
            if (tenDelta) {
                double k10c = strikeForDelta(q.forward, v10c, q.t, 0.10, true, deltaConvention, q.foreignDf);
                double k10p = strikeForDelta(q.forward, v10p, q.t, -0.10, false, deltaConvention, q.foreignDf);
                ks = new double[]{k10p, k25p, kAtm, k25c, k10c};
                vs = new double[]{v10p, v25p, q.atm, v25c, v10c};
            } else {
                ks = new double[]{k25p, kAtm, k25c};
                vs = new double[]{v25p, q.atm, v25c};
            }
            double[] x = new double[ks.length];
            for (int j = 0; j < ks.length; j++) {
                if (j > 0 && ks[j] <= ks[j - 1]) {
                    throw new IllegalStateException(
                            "solved strikes not increasing at expiry " + q.t
                                    + " - check rr/bf signs and magnitudes");
                }
                x[j] = Math.log(ks[j] / q.forward);
            }
            return new double[][]{x, vs};
        }

        /**
         * Smile butterfly at one delta level that reprices the broker
         * strangle (Clark 2011, section 3.6). The other level is held fixed.
         */
        private double solveSmileBf(Quote q, double delta, double brokerBf,
                                    double s25, double s10) {
            double vMs = q.atm + brokerBf;
            double kc = strikeForDelta(q.forward, vMs, q.t, delta, true, deltaConvention, q.foreignDf);
            double kp = strikeForDelta(q.forward, vMs, q.t, -delta, false, deltaConvention, q.foreignDf);
            double target = black(q.forward, kc, vMs, q.t, true) + black(q.forward, kp, vMs, q.t, false);
            boolean is25 = delta == 0.25;
            java.util.function.DoubleUnaryOperator f = bf -> {
                double[][] sm = is25 ? pillars(q, bf, s10) : pillars(q, s25, bf);
                double vc = interp(sm[0], sm[1], Math.log(kc / q.forward));
                double vp = interp(sm[0], sm[1], Math.log(kp / q.forward));
                return black(q.forward, kc, vc, q.t, true) + black(q.forward, kp, vp, q.t, false)
                        - target;
            };
            // Bracket around the broker value, then bisect with secant steps.
            double rr = is25 ? q.rr25 : q.rr10;
            double lo = -q.atm + Math.abs(rr) / 2 + 1e-6;
            double hi = brokerBf + 0.5 * q.atm + Math.abs(rr);
            double fLo = safeEval(f, lo);
            double fHi = safeEval(f, hi);
            if (!(fLo < 0) || !(fHi > 0)) {
                // Narrow the lower end until the smile is valid and below target.
                double a = brokerBf;
                double fa = safeEval(f, a);
                double step = 0.25 * (brokerBf - lo);
                while (!(fa <= 0) && a - step > lo) {
                    a -= step;
                    fa = safeEval(f, a);
                }
                lo = a;
                fLo = fa;
                if (!(fLo <= 0) || !(fHi > 0)) {
                    throw new IllegalStateException("broker strangle cannot be matched at expiry "
                            + q.t + " (delta " + delta + ")");
                }
            }
            for (int i = 0; i < 200; i++) {
                double mid = fHi != fLo ? lo - fLo * (hi - lo) / (fHi - fLo) : 0.5 * (lo + hi);
                if (!(mid > lo && mid < hi) || i % 3 == 2) {
                    mid = 0.5 * (lo + hi);
                }
                double fm = safeEval(f, mid);
                if (Double.isNaN(fm) || fm < 0) {
                    lo = mid;
                    fLo = Double.isNaN(fm) ? fLo : fm;
                } else {
                    hi = mid;
                    fHi = fm;
                }
                if (hi - lo < 1e-15 || fm == 0) {
                    return fm == 0 ? mid : 0.5 * (lo + hi);
                }
            }
            return 0.5 * (lo + hi);
        }

        private static double safeEval(java.util.function.DoubleUnaryOperator f, double x) {
            try {
                return f.applyAsDouble(x);
            } catch (IllegalStateException | IllegalArgumentException e) {
                return Double.NaN;
            }
        }
    }

    /** Undiscounted Black price. */
    private static double black(double f, double k, double vol, double t, boolean call) {
        double sv = vol * Math.sqrt(t);
        double d1 = Math.log(f / k) / sv + 0.5 * sv;
        double d2 = d1 - sv;
        return call ? f * MathUtils.normCdf(d1) - k * MathUtils.normCdf(d2)
                : k * MathUtils.normCdf(-d2) - f * MathUtils.normCdf(-d1);
    }

    /** Linear in x, flat beyond the ends. */
    private static double interp(double[] xs, double[] vs, double x) {
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

    // ------------------------------------------------------------------
    // Delta ↔ strike (static, reusable by hedgers and exotic pricers)
    // ------------------------------------------------------------------

    /** ATM strike under the given conventions. */
    public static double atmStrike(double forward, double vol, double tYears,
                                   DeltaConvention delta, AtmConvention atm) {
        if (atm == AtmConvention.FORWARD) {
            return forward;
        }
        return dnsStrike(forward, vol, tYears, delta.premiumAdjusted());
    }

    /**
     * Strike for a target delta under any {@link DeltaConvention}. Spot
     * deltas are divided by {@code foreignDiscountFactor} = e^{−r_f T} to get
     * the forward delta, then solved as below.
     */
    public static double strikeForDelta(double forward, double vol, double tYears, double delta,
                                         boolean isCall, DeltaConvention convention,
                                         double foreignDiscountFactor) {
        double fwdDelta = convention.spot() ? delta / foreignDiscountFactor : delta;
        return strikeForDelta(forward, vol, tYears, fwdDelta, isCall, convention.premiumAdjusted());
    }

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
            int peakIdx = 0;
            for (int i = 0; i <= 200; i++) {
                double k = lo * Math.pow(hi / lo, i / 200.0);
                double v = paCallDelta(forward, k, sv);
                if (v > peakVal) {
                    peakVal = v;
                    peak = k;
                    peakIdx = i;
                }
            }
            // The grid only brackets the true (continuous) maximum; refine it
            // with a few golden-section steps in log-strike around the best
            // grid point so peakVal isn't a slight under-estimate of the
            // true peak, which would spuriously reject attainable deltas
            // right at the edge of the range.
            {
                double logLo = Math.log(lo * Math.pow(hi / lo, Math.max(0, peakIdx - 1) / 200.0));
                double logHi = Math.log(lo * Math.pow(hi / lo, Math.min(200, peakIdx + 1) / 200.0));
                double gr = (Math.sqrt(5) - 1) / 2;
                double a = logLo, b = logHi;
                double c = b - gr * (b - a);
                double d = a + gr * (b - a);
                double fc = paCallDelta(forward, Math.exp(c), sv);
                double fd = paCallDelta(forward, Math.exp(d), sv);
                for (int i = 0; i < 60; i++) {
                    if (fc > fd) {
                        b = d;
                        d = c;
                        fd = fc;
                        c = b - gr * (b - a);
                        fc = paCallDelta(forward, Math.exp(c), sv);
                    } else {
                        a = c;
                        c = d;
                        fc = fd;
                        d = a + gr * (b - a);
                        fd = paCallDelta(forward, Math.exp(d), sv);
                    }
                }
                double refinedK = Math.exp(0.5 * (a + b));
                double refinedVal = paCallDelta(forward, refinedK, sv);
                if (refinedVal > peakVal) {
                    peakVal = refinedVal;
                    peak = refinedK;
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
        // one bracket search serves both the forward interpolation and the
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
                atmStrike(forwardAt(expiryYears), seed, expiryYears, deltaConvention, atmConvention));
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
        return deltaConvention.premiumAdjusted();
    }

    public DeltaConvention deltaConvention() {
        return deltaConvention;
    }

    public AtmConvention atmConvention() {
        return atmConvention;
    }

    /** Smile-strangle 25Δ butterfly solved (or taken as given) at pillar {@code i}. */
    public double smileButterfly25(int i) {
        return smileBf25[i];
    }

    /** Smile-strangle 10Δ butterfly at pillar {@code i}, NaN without 10Δ wings. */
    public double smileButterfly10(int i) {
        return smileBf10[i];
    }

    /** Linear interp in log-moneyness within expiry {@code i}, flat wings. */
    private double smileVol(int i, double x) {
        return interp(logMoneyness[i], vols[i], x);
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

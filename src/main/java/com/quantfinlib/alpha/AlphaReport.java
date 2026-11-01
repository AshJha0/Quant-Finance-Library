package com.quantfinlib.alpha;

import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.util.MathUtils;

import java.util.List;
import java.util.Locale;

/**
 * Alpha reporting: the diagnostics that explain a factor's P&amp;L rather
 * than just totalling it.
 *
 * <ul>
 *   <li><b>Alpha decay</b> ({@link #lagIcProfile}): IC of the scores at
 *       {@code t} against the single-bar return over {@code (t+h−1, t+h]},
 *       for {@code h = 1..H}, with an exponential fit
 *       {@code IC(h) = IC₀·exp(−(h−1)/τ)}. This is the curve that says how
 *       fast the information is used up and so bounds the rebalance
 *       cadence. The signal's own rank autocorrelation at each lag is
 *       reported beside it (turnover is roughly {@code 1 − autocorrelation}).</li>
 *   <li><b>Holding-period IC</b> ({@link #decayProfile}): IC against the
 *       cumulative return {@code (t, t+h]}. For a slow signal this usually
 *       rises and plateaus, so it is not a decay curve; it answers "what is
 *       the IC if I hold for h bars".</li>
 *   <li><b>Factor attribution</b> ({@link #attribute}): OLS of portfolio
 *       returns on factor return streams with classical and Newey-West
 *       standard errors; {@link FactorReturns} builds the streams from the
 *       library's own factors.</li>
 *   <li><b>Curves and ratios</b>: cumulative return, drawdown series, and
 *       the ratio set reused from {@code backtest.PerformanceAnalytics}.</li>
 *   <li><b>Rolling metrics</b>: the windowed Sharpe.</li>
 * </ul>
 */
public final class AlphaReport {

    private AlphaReport() {
    }

    // ------------------------------------------------------------------
    // Holding-period IC
    // ------------------------------------------------------------------

    /**
     * Mean IC per cumulative horizon, plus the horizon where it first falls
     * below half the shortest-horizon value.
     */
    public static final class Decay {
        private final int[] horizons;
        private final double[] meanIcs;
        private final double halfLifeBars;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Decay(int[] horizons, double[] meanIcs, double halfLifeBars) {
            this.horizons = horizons == null ? null : horizons.clone();
            this.meanIcs = meanIcs == null ? null : meanIcs.clone();
            this.halfLifeBars = halfLifeBars;
        }

        /** Element {@code i} of {@code horizons}. */
        public int horizons(int i) {
            return horizons[i];
        }

        /** Length of {@code horizons}. */
        public int horizonsLength() {
            return horizons.length;
        }

        /** A fresh copy of {@code horizons}. */
        public int[] horizonsCopy() {
            return horizons == null ? null : horizons.clone();
        }

        /** Element {@code i} of {@code meanIcs}. */
        public double meanIcs(int i) {
            return meanIcs[i];
        }

        /** Length of {@code meanIcs}. */
        public int meanIcsLength() {
            return meanIcs.length;
        }

        /** A fresh copy of {@code meanIcs}. */
        public double[] meanIcsCopy() {
            return meanIcs == null ? null : meanIcs.clone();
        }

        /** {@code halfLifeBars}. */
        public double halfLifeBars() {
            return halfLifeBars;
        }

        public String format() {
            StringBuilder sb = new StringBuilder("holding-period IC:");
            for (int i = 0; i < horizons.length; i++) {
                sb.append(String.format(Locale.ROOT, " h=%d:%.4f", horizons[i], meanIcs[i]));
            }
            return sb.append(String.format(Locale.ROOT, " | half-crossing=%.1f bars", halfLifeBars))
                    .toString();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Decay o)) {
                return false;
            }
            return java.util.Arrays.equals(horizons, o.horizons)
                    && java.util.Arrays.equals(meanIcs, o.meanIcs)
                    && Double.compare(halfLifeBars, o.halfLifeBars) == 0;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(horizons);
            h = 31 * h + java.util.Arrays.hashCode(meanIcs);
            h = 31 * h + Double.hashCode(halfLifeBars);
            return h;
        }

        @Override
        public String toString() {
            return "Decay[" + "horizons=" + java.util.Arrays.toString(horizons) + ", " + "meanIcs=" + java.util.Arrays.toString(meanIcs) + ", " + "halfLifeBars=" + halfLifeBars + "]";
        }
    }

    /**
     * Mean IC against cumulative forward returns {@code (t, t+h]} at each
     * horizon (non-overlapping grid per horizon). {@code halfLifeBars} is the
     * first horizon, linearly interpolated, where the IC drops below half its
     * shortest-horizon value, {@code +∞} if it never does. For a decay curve
     * use {@link #lagIcProfile}.
     *
     * @param horizons ascending forward horizons in bars
     */
    public static Decay decayProfile(AlphaContext ctx, AlphaFactor factor,
                                     int startIndex, int[] horizons) {
        if (horizons.length < 2) {
            throw new IllegalArgumentException("need at least 2 horizons");
        }
        double[] ics = new double[horizons.length];
        for (int i = 0; i < horizons.length; i++) {
            if (i > 0 && horizons[i] <= horizons[i - 1]) {
                throw new IllegalArgumentException("horizons must be ascending");
            }
            ics[i] = AlphaValidation.meanIc(ctx, factor, startIndex, ctx.bars(), horizons[i]);
        }
        double target = ics[0] / 2;
        double halfLife = Double.POSITIVE_INFINITY;
        if (ics[0] > 0) {
            for (int i = 1; i < ics.length; i++) {
                if (ics[i] < target) {
                    double w = (ics[i - 1] - target) / (ics[i - 1] - ics[i]);
                    halfLife = horizons[i - 1] + w * (horizons[i] - horizons[i - 1]);
                    break;
                }
            }
        }
        return new Decay(horizons.clone(), ics, halfLife);
    }

    // ------------------------------------------------------------------
    // Alpha decay on lagged one-bar returns
    // ------------------------------------------------------------------

    /**
     * Lagged-return IC profile with its exponential fit.
     */
    public static final class LagDecay {
        private final int[] lags;
        private final double[] meanIcs;
        private final double[] signalAutocorrelation;
        private final double ic0;
        private final double tauBars;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param lags 1..maxLag
         * @param meanIcs mean IC of scores(t) vs return over (t+h−1, t+h]
         * @param signalAutocorrelation mean cross-sectional rank correlation of scores(t) with scores(t+h)
         * @param ic0 fitted IC at lag 1
         * @param tauBars fitted e-folding time in bars ({@code +∞} when the profile does not decay; NaN when it cannot be fitted)
         */
        public LagDecay(int[] lags, double[] meanIcs, double[] signalAutocorrelation, double ic0,
                double tauBars) {
            this.lags = lags == null ? null : lags.clone();
            this.meanIcs = meanIcs == null ? null : meanIcs.clone();
            this.signalAutocorrelation = signalAutocorrelation == null ? null : signalAutocorrelation.clone();
            this.ic0 = ic0;
            this.tauBars = tauBars;
        }

        /** Element {@code i} of {@code lags} (1..maxLag). */
        public int lags(int i) {
            return lags[i];
        }

        /** Length of {@code lags}. */
        public int lagsLength() {
            return lags.length;
        }

        /** A fresh copy of {@code lags}. */
        public int[] lagsCopy() {
            return lags == null ? null : lags.clone();
        }

        /** Element {@code i} of {@code meanIcs} (mean IC of scores(t) vs return over (t+h−1, t+h]). */
        public double meanIcs(int i) {
            return meanIcs[i];
        }

        /** Length of {@code meanIcs}. */
        public int meanIcsLength() {
            return meanIcs.length;
        }

        /** A fresh copy of {@code meanIcs}. */
        public double[] meanIcsCopy() {
            return meanIcs == null ? null : meanIcs.clone();
        }

        /** Element {@code i} of {@code signalAutocorrelation} (mean cross-sectional rank correlation of scores(t) with scores(t+h)). */
        public double signalAutocorrelation(int i) {
            return signalAutocorrelation[i];
        }

        /** Length of {@code signalAutocorrelation}. */
        public int signalAutocorrelationLength() {
            return signalAutocorrelation.length;
        }

        /** A fresh copy of {@code signalAutocorrelation}. */
        public double[] signalAutocorrelationCopy() {
            return signalAutocorrelation == null ? null : signalAutocorrelation.clone();
        }

        /** {@code ic0} (fitted IC at lag 1). */
        public double ic0() {
            return ic0;
        }

        /** {@code tauBars}. */
        public double tauBars() {
            return tauBars;
        }

        /** {@code τ·ln 2}: bars until the fitted IC halves. */
        public double halfLifeBars() {
            return tauBars * Math.log(2);
        }

        public String format() {
            StringBuilder sb = new StringBuilder("alpha decay:");
            for (int i = 0; i < lags.length; i++) {
                sb.append(String.format(Locale.ROOT, " %d:%.4f", lags[i], meanIcs[i]));
            }
            return sb.append(String.format(Locale.ROOT, " | IC0=%.4f tau=%.1f half-life=%.1f bars",
                    ic0, tauBars, halfLifeBars())).toString();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LagDecay o)) {
                return false;
            }
            return java.util.Arrays.equals(lags, o.lags)
                    && java.util.Arrays.equals(meanIcs, o.meanIcs)
                    && java.util.Arrays.equals(signalAutocorrelation, o.signalAutocorrelation)
                    && Double.compare(ic0, o.ic0) == 0
                    && Double.compare(tauBars, o.tauBars) == 0;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(lags);
            h = 31 * h + java.util.Arrays.hashCode(meanIcs);
            h = 31 * h + java.util.Arrays.hashCode(signalAutocorrelation);
            h = 31 * h + Double.hashCode(ic0);
            h = 31 * h + Double.hashCode(tauBars);
            return h;
        }

        @Override
        public String toString() {
            return "LagDecay[" + "lags=" + java.util.Arrays.toString(lags) + ", " + "meanIcs=" + java.util.Arrays.toString(meanIcs) + ", " + "signalAutocorrelation=" + java.util.Arrays.toString(signalAutocorrelation) + ", " + "ic0=" + ic0 + ", " + "tauBars=" + tauBars + "]";
        }
    }

    /**
     * IC of the scores at {@code t} against the one-bar return over
     * {@code (t+h−1, t+h]} for {@code h = 1..maxLag}, averaged over every
     * bar {@code t} from {@code startIndex} with the whole window in sample,
     * then fitted by least squares to {@code IC(h) = IC₀·exp(−(h−1)/τ)}
     * (for fixed τ the optimal IC₀ is closed-form; τ is found by
     * golden-section search on {@code log τ} over [0.05, 10⁵] bars, and
     * reported as {@code +∞} when the best fit sits at the upper bound).
     */
    public static LagDecay lagIcProfile(AlphaContext ctx, AlphaFactor factor,
                                        int startIndex, int maxLag) {
        if (maxLag < 2) {
            throw new IllegalArgumentException("need maxLag >= 2");
        }
        int m = ctx.symbolCount();
        double[] icSum = new double[maxLag];
        int[] icN = new int[maxLag];
        double[] acSum = new double[maxLag];
        int[] acN = new int[maxLag];
        double[] oneBar = new double[m];
        for (int t = startIndex; t + maxLag < ctx.bars(); t++) {
            double[] scores = factor.scores(ctx, t);
            for (int h = 1; h <= maxLag; h++) {
                for (int i = 0; i < m; i++) {
                    oneBar[i] = ctx.returnOver(i, t + h - 1, t + h);
                }
                double ic = SignalEvaluator.spearman(scores, oneBar);
                if (!Double.isNaN(ic)) {
                    icSum[h - 1] += ic;
                    icN[h - 1]++;
                }
                double ac = SignalEvaluator.spearman(scores, factor.scores(ctx, t + h));
                if (!Double.isNaN(ac)) {
                    acSum[h - 1] += ac;
                    acN[h - 1]++;
                }
            }
        }
        int[] lags = new int[maxLag];
        double[] ics = new double[maxLag];
        double[] acs = new double[maxLag];
        for (int h = 0; h < maxLag; h++) {
            lags[h] = h + 1;
            ics[h] = icN[h] == 0 ? Double.NaN : icSum[h] / icN[h];
            acs[h] = acN[h] == 0 ? Double.NaN : acSum[h] / acN[h];
        }
        if (icN[0] == 0) {
            throw new IllegalArgumentException("no scored date with a full lag window");
        }
        double[] fit = fitExponential(ics);
        return new LagDecay(lags, ics, acs, fit[0], fit[1]);
    }

    /** {IC₀, τ} least-squares fit of ic[h−1] ≈ IC₀·exp(−(h−1)/τ), skipping NaN. */
    static double[] fitExponential(double[] ic) {
        double lo = Math.log(0.05);
        double hi = Math.log(1e5);
        double g = (Math.sqrt(5) - 1) / 2;
        double a = lo;
        double b = hi;
        double c = b - g * (b - a);
        double d = a + g * (b - a);
        double fc = sse(ic, Math.exp(c));
        double fd = sse(ic, Math.exp(d));
        for (int it = 0; it < 200 && b - a > 1e-10; it++) {
            if (fc <= fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - g * (b - a);
                fc = sse(ic, Math.exp(c));
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + g * (b - a);
                fd = sse(ic, Math.exp(d));
            }
        }
        double tau = Math.exp((a + b) / 2);
        double ic0 = bestIc0(ic, tau);
        if (!Double.isFinite(ic0)) {
            return new double[]{Double.NaN, Double.NaN};
        }
        if ((a + b) / 2 > hi - 1e-3) {
            tau = Double.POSITIVE_INFINITY;
        }
        return new double[]{ic0, tau};
    }

    private static double bestIc0(double[] ic, double tau) {
        double num = 0;
        double den = 0;
        for (int h = 0; h < ic.length; h++) {
            if (!Double.isNaN(ic[h])) {
                double e = Math.exp(-h / tau);
                num += ic[h] * e;
                den += e * e;
            }
        }
        return den == 0 ? Double.NaN : num / den;
    }

    private static double sse(double[] ic, double tau) {
        double ic0 = bestIc0(ic, tau);
        double s = 0;
        for (int h = 0; h < ic.length; h++) {
            if (!Double.isNaN(ic[h])) {
                double r = ic[h] - ic0 * Math.exp(-h / tau);
                s += r * r;
            }
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Factor attribution
    // ------------------------------------------------------------------

    /**
     * OLS attribution with standard errors.
     */
    public static final class Attribution {
        private final double alphaPerBar;
        private final double[] betas;
        private final List<String> factorNames;
        private final double rSquared;
        private final double alphaStdError;
        private final double alphaHacStdError;
        private final double[] betaStdErrors;
        private final double[] betaHacStdErrors;
        private final int hacLag;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param alphaPerBar intercept: return per bar not explained by the factors
         * @param alphaStdError its classical standard error
         * @param alphaHacStdError its Newey-West standard error at {@code hacLag}
         * @param betaStdErrors classical standard errors of the betas
         * @param betaHacStdErrors Newey-West standard errors of the betas
         */
        public Attribution(double alphaPerBar, double[] betas, List<String> factorNames, double rSquared,
                double alphaStdError, double alphaHacStdError, double[] betaStdErrors,
                double[] betaHacStdErrors, int hacLag) {
            this.alphaPerBar = alphaPerBar;
            this.betas = betas == null ? null : betas.clone();
            this.factorNames = List.copyOf(factorNames);
            this.rSquared = rSquared;
            this.alphaStdError = alphaStdError;
            this.alphaHacStdError = alphaHacStdError;
            this.betaStdErrors = betaStdErrors == null ? null : betaStdErrors.clone();
            this.betaHacStdErrors = betaHacStdErrors == null ? null : betaHacStdErrors.clone();
            this.hacLag = hacLag;
        }

        /** {@code alphaPerBar} (intercept: return per bar not explained by the factors). */
        public double alphaPerBar() {
            return alphaPerBar;
        }

        /** Element {@code i} of {@code betas}. */
        public double betas(int i) {
            return betas[i];
        }

        /** Length of {@code betas}. */
        public int betasLength() {
            return betas.length;
        }

        /** A fresh copy of {@code betas}. */
        public double[] betasCopy() {
            return betas == null ? null : betas.clone();
        }

        /** {@code factorNames}. */
        public List<String> factorNames() {
            return factorNames;
        }

        /** {@code rSquared}. */
        public double rSquared() {
            return rSquared;
        }

        /** {@code alphaStdError} (its classical standard error). */
        public double alphaStdError() {
            return alphaStdError;
        }

        /** {@code alphaHacStdError}. */
        public double alphaHacStdError() {
            return alphaHacStdError;
        }

        /** Element {@code i} of {@code betaStdErrors} (classical standard errors of the betas). */
        public double betaStdErrors(int i) {
            return betaStdErrors[i];
        }

        /** Length of {@code betaStdErrors}. */
        public int betaStdErrorsLength() {
            return betaStdErrors.length;
        }

        /** A fresh copy of {@code betaStdErrors}. */
        public double[] betaStdErrorsCopy() {
            return betaStdErrors == null ? null : betaStdErrors.clone();
        }

        /** Element {@code i} of {@code betaHacStdErrors} (Newey-West standard errors of the betas). */
        public double betaHacStdErrors(int i) {
            return betaHacStdErrors[i];
        }

        /** Length of {@code betaHacStdErrors}. */
        public int betaHacStdErrorsLength() {
            return betaHacStdErrors.length;
        }

        /** A fresh copy of {@code betaHacStdErrors}. */
        public double[] betaHacStdErrorsCopy() {
            return betaHacStdErrors == null ? null : betaHacStdErrors.clone();
        }

        /** {@code hacLag}. */
        public int hacLag() {
            return hacLag;
        }

        /** Newey-West t-stat of the intercept. */
        public double alphaTStat() {
            return alphaHacStdError > 0 ? alphaPerBar / alphaHacStdError
                    : alphaPerBar == 0 ? 0 : Math.signum(alphaPerBar) * Double.POSITIVE_INFINITY;
        }

        public String format() {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                    "attribution: alpha=%.6f/bar (t_NW=%.2f)", alphaPerBar, alphaTStat()));
            for (int i = 0; i < betas.length; i++) {
                sb.append(String.format(Locale.ROOT, " %s=%.3f", factorNames.get(i), betas[i]));
            }
            return sb.append(String.format(Locale.ROOT, " R2=%.3f", rSquared)).toString();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Attribution o)) {
                return false;
            }
            return Double.compare(alphaPerBar, o.alphaPerBar) == 0
                    && java.util.Arrays.equals(betas, o.betas)
                    && java.util.Objects.equals(factorNames, o.factorNames)
                    && Double.compare(rSquared, o.rSquared) == 0
                    && Double.compare(alphaStdError, o.alphaStdError) == 0
                    && Double.compare(alphaHacStdError, o.alphaHacStdError) == 0
                    && java.util.Arrays.equals(betaStdErrors, o.betaStdErrors)
                    && java.util.Arrays.equals(betaHacStdErrors, o.betaHacStdErrors)
                    && hacLag == o.hacLag;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Double.hashCode(alphaPerBar);
            h = 31 * h + java.util.Arrays.hashCode(betas);
            h = 31 * h + java.util.Objects.hashCode(factorNames);
            h = 31 * h + Double.hashCode(rSquared);
            h = 31 * h + Double.hashCode(alphaStdError);
            h = 31 * h + Double.hashCode(alphaHacStdError);
            h = 31 * h + java.util.Arrays.hashCode(betaStdErrors);
            h = 31 * h + java.util.Arrays.hashCode(betaHacStdErrors);
            h = 31 * h + Integer.hashCode(hacLag);
            return h;
        }

        @Override
        public String toString() {
            return "Attribution[" + "alphaPerBar=" + alphaPerBar + ", " + "betas=" + java.util.Arrays.toString(betas) + ", " + "factorNames=" + factorNames + ", " + "rSquared=" + rSquared + ", " + "alphaStdError=" + alphaStdError + ", " + "alphaHacStdError=" + alphaHacStdError + ", " + "betaStdErrors=" + java.util.Arrays.toString(betaStdErrors) + ", " + "betaHacStdErrors=" + java.util.Arrays.toString(betaHacStdErrors) + ", " + "hacLag=" + hacLag + "]";
        }
    }

    /**
     * {@link #attribute(double[], double[][], List, int)} with the Andrews
     * (1991) automatic lag picked on the portfolio returns.
     */
    public static Attribution attribute(double[] portfolioReturns, double[][] factorReturns,
                                        List<String> factorNames) {
        int lag = portfolioReturns.length < 3 ? 0 : NeweyWest.automaticLag(portfolioReturns);
        return attribute(portfolioReturns, factorReturns, factorNames, lag);
    }

    /**
     * Regresses portfolio returns on factor return streams with an
     * intercept, {@code r_p = α + Σ βᵢ·fᵢ + ε}, by Householder QR
     * ({@link Ols}). The intercept is the residual alpha.
     *
     * @param portfolioReturns per-bar portfolio returns
     * @param factorReturns    one per-bar return stream per factor, aligned
     * @param hacLag           Bartlett lag for the Newey-West errors
     */
    public static Attribution attribute(double[] portfolioReturns, double[][] factorReturns,
                                        List<String> factorNames, int hacLag) {
        int n = portfolioReturns.length;
        int k = factorReturns.length;
        if (k != factorNames.size() || k == 0) {
            throw new IllegalArgumentException("factor streams and names must align");
        }
        for (double[] f : factorReturns) {
            if (f.length != n) {
                throw new IllegalArgumentException("factor stream length mismatch");
            }
        }
        if (n <= k + 1) {
            throw new IllegalArgumentException("more factors than observations");
        }
        for (int t = 0; t < n; t++) {
            if (Double.isNaN(portfolioReturns[t])) {
                throw new IllegalArgumentException("NaN portfolio return at index " + t);
            }
            for (int j = 0; j < k; j++) {
                if (Double.isNaN(factorReturns[j][t])) {
                    throw new IllegalArgumentException("NaN in factor stream '"
                            + factorNames.get(j) + "' at index " + t
                            + "; trim warm-up bars before attribution");
                }
            }
        }
        double[][] x = new double[n][k + 1];
        for (int t = 0; t < n; t++) {
            x[t][0] = 1;
            for (int j = 0; j < k; j++) {
                x[t][j + 1] = factorReturns[j][t];
            }
        }
        Ols.Fit fit = Ols.fit(x, portfolioReturns, hacLag);
        double[] betas = new double[k];
        double[] se = new double[k];
        double[] hac = new double[k];
        System.arraycopy(fit.coefficientsCopy(), 1, betas, 0, k);
        System.arraycopy(fit.standardErrorsCopy(), 1, se, 0, k);
        System.arraycopy(fit.hacStandardErrorsCopy(), 1, hac, 0, k);
        return new Attribution(fit.coefficients(0), betas, List.copyOf(factorNames),
                fit.rSquared(), fit.standardErrors(0), fit.hacStandardErrors(0),
                se, hac, fit.hacLag());
    }

    // ------------------------------------------------------------------
    // Curves and rolling metrics
    // ------------------------------------------------------------------

    /** Per-bar simple returns of an equity curve — the input to attribution/rolling. */
    public static double[] returnsOf(double[] equity) {
        if (equity.length < 2) {
            throw new IllegalArgumentException("need at least 2 equity points");
        }
        double[] r = new double[equity.length - 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = equity[i + 1] / equity[i] - 1;
        }
        return r;
    }

    /**
     * Drawdown series: fraction below the running peak (0 at new highs).
     * Guards {@code peak > 0} exactly like {@code risk.RiskMetrics.maxDrawdown}
     * so {@code min(drawdownCurve)} and the headline max-drawdown metric can
     * never disagree on a curve that touches zero.
     */
    public static double[] drawdownCurve(double[] equity) {
        double[] dd = new double[equity.length];
        double peak = equity[0];
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            dd[i] = peak > 0 ? equity[i] / peak - 1 : 0;
        }
        return dd;
    }

    /**
     * Rolling annualized Sharpe over a trailing window of per-bar returns;
     * NaN until the window fills. The steadiness plot: a flat positive line
     * is a strategy, a single spike is an anecdote.
     *
     * <p>Uses the sample standard deviation (n−1), the same definition as
     * {@code risk.RiskMetrics.sharpeRatio} behind {@link #summarize}. A
     * full-sample rolling window therefore reproduces the headline Sharpe
     * exactly rather than differing by {@code √(n/(n−1))}.</p>
     */
    public static double[] rollingSharpe(double[] returns, int window, int periodsPerYear) {
        if (window < 2 || window > returns.length) {
            throw new IllegalArgumentException("window must be in [2, returns.length]");
        }
        double[] out = new double[returns.length];
        java.util.Arrays.fill(out, 0, window - 1, Double.NaN);
        for (int i = window - 1; i < returns.length; i++) {
            double mean = MathUtils.mean(returns, i - window + 1, i + 1);
            double sd = MathUtils.stdDevSample(returns, i - window + 1, i + 1);
            out[i] = sd == 0 ? 0 : mean / sd * Math.sqrt(periodsPerYear);
        }
        return out;
    }

    /**
     * The full ratio set on an equity curve (Sharpe, Sortino, Calmar,
     * CAGR, max drawdown), computed by the same engine the backtesters
     * use, so definitions never fork between research and backtest reports.
     */
    public static PerformanceMetrics summarize(double[] equity, int periodsPerYear) {
        return PerformanceAnalytics.compute(equity, List.of(), periodsPerYear);
    }
}

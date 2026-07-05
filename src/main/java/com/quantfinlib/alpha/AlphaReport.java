package com.quantfinlib.alpha;

import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.util.MathUtils;

import java.util.List;
import java.util.Locale;

/**
 * Alpha reporting — the diagnostics that explain a factor's P&amp;L rather
 * than just totalling it:
 *
 * <ul>
 *   <li><b>Alpha decay</b> — mean IC as a function of the forward horizon.
 *       A signal predictive at 1 day but dead at 5 needs fast, expensive
 *       trading; the {@code halfLife} estimate says how long the edge
 *       survives, which bounds the viable rebalance cadence.</li>
 *   <li><b>Factor attribution</b> — OLS of portfolio returns on factor
 *       return streams: how much of the "alpha" is just repackaged
 *       momentum/value beta, and what residual (true) alpha remains.</li>
 *   <li><b>Curves and ratios</b> — cumulative return, drawdown series, and
 *       the full ratio set (Sharpe, Sortino, Calmar, CAGR, max drawdown)
 *       reused verbatim from {@code backtest.PerformanceAnalytics}, so
 *       alpha reports and strategy backtests can never disagree on a
 *       definition.</li>
 *   <li><b>Rolling metrics</b> — the windowed Sharpe that shows whether
 *       performance is steady or one lucky year.</li>
 * </ul>
 */
public final class AlphaReport {

    private AlphaReport() {
    }

    // ------------------------------------------------------------------
    // Alpha decay
    // ------------------------------------------------------------------

    /** IC per horizon plus the interpolated half-life of the shortest-horizon IC. */
    public record Decay(int[] horizons, double[] meanIcs, double halfLifeBars) {

        public String format() {
            StringBuilder sb = new StringBuilder("alpha decay:");
            for (int i = 0; i < horizons.length; i++) {
                sb.append(String.format(Locale.ROOT, " h=%d:%.4f", horizons[i], meanIcs[i]));
            }
            return sb.append(String.format(Locale.ROOT, " | half-life=%.1f bars", halfLifeBars))
                    .toString();
        }
    }

    /**
     * Evaluates the factor's mean IC at each horizon. The half-life is the
     * first horizon (linearly interpolated) where the IC falls below half
     * of its shortest-horizon value; {@code +∞} when it never does within
     * the tested range — the honest answer for slow signals.
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
        // First crossing below half the base IC, linearly interpolated —
        // only meaningful when the base IC is positive to begin with.
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
    // Factor attribution
    // ------------------------------------------------------------------

    /** OLS attribution: per-bar residual alpha, factor betas, and fit quality. */
    public record Attribution(double alphaPerBar, double[] betas, List<String> factorNames,
                              double rSquared) {

        public String format() {
            StringBuilder sb = new StringBuilder(
                    String.format(Locale.ROOT, "attribution: alpha=%.6f/bar", alphaPerBar));
            for (int i = 0; i < betas.length; i++) {
                sb.append(String.format(Locale.ROOT, " %s=%.3f", factorNames.get(i), betas[i]));
            }
            return sb.append(String.format(Locale.ROOT, " R2=%.3f", rSquared)).toString();
        }
    }

    /**
     * Regresses portfolio returns on factor return streams (with an
     * intercept) via the normal equations: {@code r_p = α + Σ βᵢ·fᵢ + ε}.
     * The intercept is the residual alpha — what survives after the known
     * factors take their share. Keep the factor count far below the bar
     * count; the normal equations of collinear factors are a data problem,
     * not a solver problem.
     *
     * @param portfolioReturns per-bar portfolio returns
     * @param factorReturns    one per-bar return stream per factor, aligned
     */
    public static Attribution attribute(double[] portfolioReturns, double[][] factorReturns,
                                        List<String> factorNames) {
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
        // Design matrix X = [1 | factors]; solve (XᵀX) b = Xᵀy.
        int p = k + 1;
        double[][] xtx = new double[p][p];
        double[] xty = new double[p];
        for (int t = 0; t < n; t++) {
            double[] row = new double[p];
            row[0] = 1;
            for (int j = 0; j < k; j++) {
                row[j + 1] = factorReturns[j][t];
            }
            for (int a = 0; a < p; a++) {
                xty[a] += row[a] * portfolioReturns[t];
                for (int b = 0; b < p; b++) {
                    xtx[a][b] += row[a] * row[b];
                }
            }
        }
        double[] beta = MathUtils.solveLinear(xtx, xty);
        // R² from the residuals of the fitted model.
        double meanY = MathUtils.mean(portfolioReturns);
        double ssTot = 0;
        double ssRes = 0;
        for (int t = 0; t < n; t++) {
            double fit = beta[0];
            for (int j = 0; j < k; j++) {
                fit += beta[j + 1] * factorReturns[j][t];
            }
            ssRes += (portfolioReturns[t] - fit) * (portfolioReturns[t] - fit);
            ssTot += (portfolioReturns[t] - meanY) * (portfolioReturns[t] - meanY);
        }
        double[] betas = new double[k];
        System.arraycopy(beta, 1, betas, 0, k);
        return new Attribution(beta[0], betas, List.copyOf(factorNames),
                ssTot == 0 ? 0 : 1 - ssRes / ssTot);
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

    /** Drawdown series: fraction below the running peak (0 at new highs). */
    public static double[] drawdownCurve(double[] equity) {
        double[] dd = new double[equity.length];
        double peak = equity[0];
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            dd[i] = equity[i] / peak - 1;
        }
        return dd;
    }

    /**
     * Rolling annualized Sharpe over a trailing window of per-bar returns;
     * NaN until the window fills. The steadiness plot: a flat positive line
     * is a strategy, a single spike is an anecdote.
     */
    public static double[] rollingSharpe(double[] returns, int window, int periodsPerYear) {
        if (window < 2 || window > returns.length) {
            throw new IllegalArgumentException("window must be in [2, returns.length]");
        }
        double[] out = new double[returns.length];
        java.util.Arrays.fill(out, 0, window - 1, Double.NaN);
        for (int i = window - 1; i < returns.length; i++) {
            double mean = MathUtils.mean(returns, i - window + 1, i + 1);
            double sd = MathUtils.stdDevP(returns, i - window + 1, i + 1);
            out[i] = sd == 0 ? 0 : mean / sd * Math.sqrt(periodsPerYear);
        }
        return out;
    }

    /**
     * The full ratio set on an equity curve — Sharpe, Sortino, Calmar,
     * CAGR, max drawdown — computed by the same engine the backtesters
     * use, so definitions never fork between research and backtest reports.
     */
    public static PerformanceMetrics summarize(double[] equity, int periodsPerYear) {
        return PerformanceAnalytics.compute(equity, List.of(), periodsPerYear);
    }
}

package com.quantfinlib.backtest;

import com.quantfinlib.risk.RiskMetrics;
import com.quantfinlib.util.MathUtils;

/**
 * BENCHMARK-RELATIVE performance — the numbers an allocator actually asks
 * for. A standalone Sharpe answers "was this good?"; these answer "was
 * this good <i>compared to just buying the index</i>?", which is the
 * question every active strategy must survive.
 *
 * <ul>
 *   <li><b>beta</b> — Cov(r_s, r_b)/Var(r_b): how much of the strategy is
 *       just the benchmark in disguise;</li>
 *   <li><b>alpha</b> — annualized Jensen intercept
 *       (mean(r_s) − &beta;·mean(r_b))·P at zero risk-free rate: the return
 *       left over after the benchmark exposure is paid for;</li>
 *   <li><b>tracking error</b> — annualized stdev of active returns
 *       a_t = r_s,t − r_b,t: how far the strategy strays;</li>
 *   <li><b>information ratio</b> — annualized mean(a)/TE: alpha per unit of
 *       straying. The active-management analogue of Sharpe; sustained
 *       IR &gt; 0.5 is good, &gt; 1 is elite;</li>
 *   <li><b>up/down capture</b> — mean strategy return over periods when the
 *       benchmark rose (fell), divided by the benchmark's own mean in those
 *       periods. The dream profile is up &gt; 1, down &lt; 1. Arithmetic
 *       means of per-period returns, not compounded — stated, and the right
 *       choice at daily granularity where cross-terms are negligible.
 *       {@code NaN} when the benchmark had no up (down) periods: no
 *       evidence, not zero.</li>
 * </ul>
 *
 * <p>Both series must be the same length and aligned period-by-period —
 * this class cannot detect a one-day offset, and an offset silently
 * destroys beta (it becomes a lead-lag estimate). Align first, then
 * compare. Requires the benchmark to actually vary; comparing against a
 * constant series is refused rather than returning a 0/0 beta.</p>
 */
public final class BenchmarkComparison {

    /**
     * @param alpha            annualized Jensen alpha (risk-free = 0)
     * @param beta             regression beta vs the benchmark
     * @param trackingError    annualized stdev of active returns
     * @param informationRatio annualized active return / tracking error;
     *                         0 when TE is 0 (identical series)
     * @param upCapture        capture ratio over benchmark-up periods
     *                         ({@code NaN} if none)
     * @param downCapture      capture ratio over benchmark-down periods
     *                         ({@code NaN} if none)
     * @param activeReturn     annualized mean of (strategy − benchmark)
     */
    public record Result(double alpha, double beta, double trackingError,
                         double informationRatio, double upCapture,
                         double downCapture, double activeReturn) {
    }

    private BenchmarkComparison() {
    }

    /**
     * @param strategy       per-period strategy returns, aligned with
     *                       {@code benchmark}, &ge; 3 periods, finite
     * @param benchmark      per-period benchmark returns, must vary
     * @param periodsPerYear annualization factor (252 daily, 12 monthly)
     */
    public static Result compare(double[] strategy, double[] benchmark, int periodsPerYear) {
        if (strategy.length != benchmark.length) {
            throw new IllegalArgumentException("length mismatch: strategy=" + strategy.length
                    + " benchmark=" + benchmark.length);
        }
        int n = strategy.length;
        if (n < 3) {
            throw new IllegalArgumentException("need >= 3 aligned periods, got " + n);
        }
        if (periodsPerYear <= 0) {
            throw new IllegalArgumentException("periodsPerYear must be > 0, got " + periodsPerYear);
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(strategy[i]) || !Double.isFinite(benchmark[i])) {
                throw new IllegalArgumentException("non-finite return at period " + i);
            }
        }
        double varB = MathUtils.variance(benchmark);
        if (!(varB > 0)) {
            throw new IllegalArgumentException("benchmark returns carry no variance");
        }

        double beta = RiskMetrics.beta(strategy, benchmark);
        double meanS = MathUtils.mean(strategy);
        double meanB = MathUtils.mean(benchmark);
        double alpha = (meanS - beta * meanB) * periodsPerYear;

        double[] active = new double[n];
        for (int i = 0; i < n; i++) {
            active[i] = strategy[i] - benchmark[i];
        }
        double activeAnn = MathUtils.mean(active) * periodsPerYear;
        double te = MathUtils.stdDev(active) * Math.sqrt(periodsPerYear);
        double ir = te > 0 ? activeAnn / te : 0;

        double upS = 0, upB = 0, downS = 0, downB = 0;
        int ups = 0, downs = 0;
        for (int i = 0; i < n; i++) {
            if (benchmark[i] > 0) {
                upS += strategy[i];
                upB += benchmark[i];
                ups++;
            } else if (benchmark[i] < 0) {
                downS += strategy[i];
                downB += benchmark[i];
                downs++;
            }
        }
        double upCapture = ups == 0 ? Double.NaN : (upS / ups) / (upB / ups);
        double downCapture = downs == 0 ? Double.NaN : (downS / downs) / (downB / downs);

        return new Result(alpha, beta, te, ir, upCapture, downCapture, activeAnn);
    }
}

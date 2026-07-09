package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.execution.BenchmarkExecutor;
import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import com.quantfinlib.orderbook.Side;

/**
 * Backtests the DYNAMIC execution stack over bar data — the bridge
 * between {@code execution.BenchmarkExecutor} (built for live intervals)
 * and the backtest lane: replay a session's bars, let the executor
 * re-decide each bar exactly as it would live, fill against the bar with
 * a {@link TradeCostModel}, and grade the result the way a TCA desk
 * would (implementation shortfall vs arrival, slippage vs the session
 * VWAP). {@code ExecutionAwareBacktester} answers "how do fills change a
 * STRATEGY's results?"; this answers the execution desk's own question:
 * <b>"which benchmark algorithm, and at what cost?"</b> — one parent
 * order, N bars, a number per benchmark.
 *
 * <p><b>Honest simplifications</b> (each visible, none silent): fills
 * print at the bar close plus the cost model's bps (no intra-bar
 * microstructure); the per-bar liquidity cap is
 * {@code participationCap × bar volume} — and it is the ONLY cap: the
 * executor is constructed with its internal depth fraction at 1 so the
 * two knobs cannot silently compound; the VWAP volume curve uses the
 * session's REALIZED cumulative volume — an oracle curve, so the VWAP
 * benchmark result here is an upper bound on what a live volume-curve
 * forecast can achieve (live, use {@code microstructure.VolumeCurve});
 * and each bar's volume enters the POV target BEFORE that bar trades —
 * one bar of look-ahead versus a strictly reactive live POV, so POV
 * completion here is mildly optimistic. Deterministic: same bars, same
 * config, same numbers. Research lane.</p>
 */
public final class ExecutionAlgoBacktester {

    /**
     * @param spreadBps        assumed quoted spread in bps of the close
     *                         (bars carry no quotes) — the spread input
     *                         the executor damps on
     * @param participationCap per-bar fill cap as a fraction of bar
     *                         volume, in (0, 1]
     * @param costModel        all-in one-way cost per fill (see
     *                         {@link TradeCostModel})
     */
    public record Config(double spreadBps, double participationCap,
                         TradeCostModel costModel) {

        public Config {
            if (spreadBps < 0 || participationCap <= 0 || participationCap > 1
                    || costModel == null) {
                throw new IllegalArgumentException("invalid backtest config");
            }
        }

        /** 5 bps spread, 10% participation, flat 1 bp cost. */
        public static Config defaults() {
            return new Config(5, 0.10, TradeCostModel.flat(1));
        }
    }

    /**
     * The TCA-style verdict for one parent worked over one session.
     *
     * @param executed          shares filled
     * @param completed         the whole parent filled within the session
     * @param avgFillPrice      cost-inclusive average fill price
     * @param arrivalPrice      the decision price (first bar's open)
     * @param sessionVwap       volume-weighted average close of the session
     * @param shortfallBps      implementation shortfall vs arrival, in bps,
     *                          signed so that POSITIVE = cost (both sides)
     * @param vwapSlippageBps   average fill vs session VWAP, in bps,
     *                          POSITIVE = worse than VWAP (both sides)
     */
    public record Result(long executed, boolean completed, double avgFillPrice,
                         double arrivalPrice, double sessionVwap,
                         double shortfallBps, double vwapSlippageBps) {
    }

    private final Config config;

    public ExecutionAlgoBacktester(Config config) {
        this.config = config;
    }

    public ExecutionAlgoBacktester() {
        this(Config.defaults());
    }

    /**
     * Works {@code parentQty} through the session under {@code benchmark}
     * and grades it. PARTICIPATION parents use {@link BenchmarkExecutor#pov}
     * with {@code povRate}; other benchmarks ignore it.
     */
    public Result run(BarSeries series, Side side, long parentQty,
                      Benchmark benchmark, double povRate) {
        int n = series.size();
        if (n < 1 || parentQty <= 0) {
            throw new IllegalArgumentException("need bars and parentQty > 0");
        }
        // maxDepthFraction = 1: the Config's participationCap (already baked
        // into displayedDepth below) must be the single liquidity cap — the
        // executor's own depth fraction compounding on top would silently
        // quarter it.
        BenchmarkExecutor exec = benchmark == Benchmark.PARTICIPATION
                ? new BenchmarkExecutor(side, parentQty, benchmark, povRate, 1, 1.0)
                : new BenchmarkExecutor(side, parentQty, benchmark, 0.1, 1, 1.0);

        double totalVolume = 0;
        double vwapNumerator = 0;
        for (int i = 0; i < n; i++) {
            totalVolume += series.volume(i);
            vwapNumerator += series.close(i) * series.volume(i);
        }
        double sessionVwap = totalVolume > 0 ? vwapNumerator / totalVolume : Double.NaN;
        double arrival = series.open(0);

        double cumVolume = 0;
        double fillNotional = 0;
        long executed = 0;
        for (int i = 0; i < n; i++) {
            double close = series.close(i);
            double volume = series.volume(i);
            cumVolume += volume;
            exec.onMarketVolume((long) volume);

            double depth = config.participationCap() * volume;
            double volumeFrac = totalVolume > 0 ? cumVolume / totalVolume : Double.NaN;
            MarketState m = new MarketState(close, close * config.spreadBps() / 1e4,
                    0, depth > 0 ? depth : 0, volumeFrac, 0, 0);

            long due = exec.dueQuantity((i + 1.0) / n, m);
            if (due <= 0) {
                continue;
            }
            double costBps = config.costModel().costBps(series, i, due * close);
            double fillPrice = side == Side.BUY
                    ? close * (1 + costBps / 1e4)
                    : close * (1 - costBps / 1e4);
            exec.onFill(due);
            executed += due;
            fillNotional += due * fillPrice;
        }

        double avgFill = executed > 0 ? fillNotional / executed : Double.NaN;
        double sign = side == Side.BUY ? 1 : -1;
        double shortfallBps = executed > 0 && arrival > 0
                ? sign * (avgFill - arrival) / arrival * 1e4 : Double.NaN;
        double vwapSlippageBps = executed > 0 && sessionVwap > 0
                ? sign * (avgFill - sessionVwap) / sessionVwap * 1e4 : Double.NaN;
        return new Result(executed, executed >= parentQty, avgFill, arrival,
                sessionVwap, shortfallBps, vwapSlippageBps);
    }

    /** {@link #run} for the time/volume benchmarks (no POV rate needed). */
    public Result run(BarSeries series, Side side, long parentQty, Benchmark benchmark) {
        if (benchmark == Benchmark.PARTICIPATION) {
            throw new IllegalArgumentException("PARTICIPATION needs a rate: use the 5-arg run");
        }
        return run(series, side, parentQty, benchmark, 0);
    }
}

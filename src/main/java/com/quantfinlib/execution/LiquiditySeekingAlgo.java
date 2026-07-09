package com.quantfinlib.execution;

import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import com.quantfinlib.util.MathUtils;

/**
 * The opportunistic execution archetype — the counterpart to
 * {@link BenchmarkExecutor}'s schedule-driven family. A schedule algo
 * asks "am I behind the curve?"; a liquidity seeker asks <b>"is the
 * market cheap RIGHT NOW?"</b> and trades in bursts when it is: spread
 * tighter than its time-of-day forecast, calm volatility regime, low
 * estimated impact. Between opportunities it sits still — which is why
 * it needs the one discipline every seek-style algo ships with: a
 * <b>completion floor</b> that ramps in over the final stretch of the
 * horizon, so patience can never become a missed parent.
 *
 * <p>Inputs reuse the {@link MarketState} units contract (normalized vol
 * regime, impact in bps, take-now depth) plus the spread FORECAST from
 * {@code microstructure.SpreadForecaster} — cheapness is always relative
 * to what the spread usually is at this hour, not to an absolute number
 * (2 pips is cheap at the London open and expensive at midnight):</p>
 *
 * <pre>{@code
 * long due = seek.dueQuantity(f, marketState, spreadForecaster.forecast(bucket, now));
 * }</pre>
 *
 * <p>Decision per interval: score the moment — spread at-or-under
 * forecast, vol regime below the calm threshold, impact below the cap —
 * and if ALL hold, take an aggressive clip ({@code maxDepthFraction ×
 * displayedDepth}). If not, send only the completion floor: 0 until
 * {@code forceCompleteFrom}, then the remaining quantity spread linearly
 * over what is left of the horizon (at f = 1 the floor IS the
 * remainder). NaN inputs degrade honestly: an unknown forecast means the
 * moment cannot be judged cheap (no opportunistic burst), but the floor
 * still guarantees completion. Zero allocation per decision, single
 * parent, single writer.</p>
 */
public final class LiquiditySeekingAlgo {

    /**
     * @param spreadTolerance   how far above forecast still counts as cheap,
     *                          as a fraction (0.1 = up to 10% over); 0 = strict
     * @param maxVolRegime      opportunistic bursts only below this normalized
     *                          vol regime (0..1), e.g. 0.5
     * @param maxImpactBps      opportunistic bursts only below this estimated
     *                          impact, e.g. 5 bps
     * @param maxDepthFraction  burst clip as a fraction of displayed depth,
     *                          in (0, 1]
     * @param forceCompleteFrom schedule fraction where the completion floor
     *                          starts ramping, in [0, 1), e.g. 0.7
     */
    public record Config(double spreadTolerance, double maxVolRegime, double maxImpactBps,
                         double maxDepthFraction, double forceCompleteFrom) {

        public Config {
            if (spreadTolerance < 0 || maxVolRegime < 0 || maxImpactBps < 0
                    || maxDepthFraction <= 0 || maxDepthFraction > 1
                    || forceCompleteFrom < 0 || forceCompleteFrom >= 1) {
                throw new IllegalArgumentException("invalid seek config");
            }
        }

        /** 10% spread tolerance, vol regime < 0.5, impact < 5 bps, 25% clips, floor from 70%. */
        public static Config defaults() {
            return new Config(0.10, 0.5, 5.0, 0.25, 0.7);
        }
    }

    private final long parentQty;
    private final Config config;
    private long executed;

    public LiquiditySeekingAlgo(long parentQty, Config config) {
        if (parentQty <= 0) {
            throw new IllegalArgumentException("parentQty must be > 0");
        }
        this.parentQty = parentQty;
        this.config = config;
    }

    public LiquiditySeekingAlgo(long parentQty) {
        this(parentQty, Config.defaults());
    }

    /**
     * Shares to send now. An opportunistic burst when the moment is cheap
     * (see class doc); otherwise the completion floor. Always capped at
     * the remainder.
     *
     * @param scheduleFraction elapsed fraction of the horizon in [0, 1]
     * @param m                the live market snapshot ({@link MarketState}
     *                         units contract)
     * @param forecastSpread   the expected spread for this time of day
     *                         ({@code SpreadForecaster.forecast}); NaN =
     *                         cheapness unknowable, floor only
     */
    public long dueQuantity(double scheduleFraction, MarketState m, double forecastSpread) {
        long remaining = parentQty - executed;
        if (remaining <= 0) {
            return 0;
        }
        double f = MathUtils.clamp(scheduleFraction, 0, 1);

        long due = 0;
        if (isCheap(m, forecastSpread)) {
            double depth = m.displayedDepth();
            due = depth == Double.POSITIVE_INFINITY
                    ? remaining
                    : (long) Math.floor(config.maxDepthFraction() * Math.max(depth, 0));
        }

        // The completion floor: from forceCompleteFrom, the remainder spread
        // linearly over the horizon left; at f = 1 the floor IS the remainder.
        if (f > config.forceCompleteFrom()) {
            double ramp = (f - config.forceCompleteFrom()) / (1 - config.forceCompleteFrom());
            long floor = f >= 1 ? remaining : (long) Math.ceil(remaining * ramp);
            due = Math.max(due, floor);
        }
        return Math.min(due, remaining);
    }

    /**
     * All three opportunity gates at once — spread at/under its forecast
     * (within tolerance), calm regime, low impact. NaN anywhere fails the
     * gate it appears in: an unknowable moment is never "cheap".
     */
    private boolean isCheap(MarketState m, double forecastSpread) {
        boolean spreadCheap = m.spread() >= 0 && forecastSpread > 0
                && forecastSpread != Double.POSITIVE_INFINITY
                && m.spread() <= forecastSpread * (1 + config.spreadTolerance());
        // NaN fails BOTH remaining gates (NaN <= x is false): an unknowable
        // regime or impact must never authorize a burst — a vol-feed outage
        // during a spike is exactly when firing would hurt most.
        boolean calm = m.volatility() <= config.maxVolRegime();
        boolean lowImpact = m.impactBps() <= config.maxImpactBps();
        return spreadCheap && calm && lowImpact;
    }

    /** Our own child fill. */
    public void onFill(long qty) {
        if (qty > 0) {
            executed += qty;
        }
    }

    public long executed() {
        return executed;
    }

    public long remaining() {
        return parentQty - executed;
    }

    public boolean done() {
        return executed >= parentQty;
    }
}

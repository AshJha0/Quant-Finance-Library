package com.quantfinlib.microstructure;

import com.quantfinlib.pricing.FairValueEngine;
import com.quantfinlib.util.MathUtils;

/**
 * The unified streaming signal engine: one multi-symbol, hot-lane component
 * that turns raw top-of-book quotes and trade prints into the five signal
 * families a trading decision reads — for equities and FX alike (prices are
 * doubles; equity integer ticks are exact in a double, FX rates feed in
 * directly):
 *
 * <ul>
 *   <li><b>Imbalance</b> — order-flow imbalance (Cont-Kukanov-Stoikov,
 *       time-decayed), inside queue imbalance and signed trade-flow
 *       imbalance, via a per-symbol {@link FlowSignals};</li>
 *   <li><b>Fair value</b> — the size-weighted microprice
 *       ({@link FairValueEngine#microprice});</li>
 *   <li><b>Volatility</b> — a streaming EWMA realized-variance rate over
 *       irregular tick arrivals: per valid mid change,
 *       {@code r² / dt} enters a time-decayed average, and
 *       {@link #volPerSqrtSecond} is its square root — multiply by
 *       {@code √(seconds per year)} to annualize externally;</li>
 *   <li><b>Liquidity</b> — time-decayed EWMAs of the absolute spread (and
 *       {@link #spreadBps} of mid), displayed top-of-book depth, and quote
 *       arrival intensity (from the decayed inter-quote gap);</li>
 *   <li><b>Momentum</b> — two <em>time-aware</em> EMAs of the mid (decay by
 *       elapsed time, not by update count — constant-step EMAs like
 *       {@code indicators.StreamingIndicators} mis-weight irregular tick
 *       arrivals), read as the normalized fast/slow gap
 *       {@code (fast − slow)/mid}.</li>
 * </ul>
 *
 * <p><b>Composite alpha.</b> {@link #alpha} blends the dimensionless forms
 * of the signals under caller-set weights: queue imbalance and trade
 * imbalance are already in [-1, 1]; OFI is normalized by decayed depth and
 * clamped (net recent flow as a fraction of what's displayed); momentum is
 * scaled by volatility over the fast horizon (a t-statistic-like ratio) and
 * squash-clamped. This composite is a <em>scaffold for intraday signal
 * research, not a validated alpha</em> — before trading any weighting, run
 * it through the {@code alpha} package's walk-forward and permutation
 * machinery like any other signal. Cross-sectional daily alphas live there,
 * not here.</p>
 *
 * <p><b>Gap discipline</b> (inherited from {@link FlowSignals} and applied
 * to every family): a one-sided or NaN quote is a signal gap — nothing
 * updates, nothing poisons, and the next two-sided quote re-seeds baselines.
 * Zero allocation per event after construction; single writer (one feed or
 * aggregation thread owns the engine); dense int symbol ids, exactly like
 * the market-data bus.</p>
 */
public final class SignalEngine {

    /**
     * Half-lives (all in nanos) for the decayed estimators, plus the
     * composite weights. {@link #defaults()} is a reasonable intraday
     * starting point — 500ms flow, 2s/20s momentum, 10s volatility and
     * liquidity, equal composite weights.
     */
    public record Config(long flowHalfLifeNanos, long fastHalfLifeNanos,
                         long slowHalfLifeNanos, long volHalfLifeNanos,
                         long liquidityHalfLifeNanos,
                         double wQueueImbalance, double wTradeImbalance,
                         double wOfi, double wMomentum) {

        public Config {
            if (flowHalfLifeNanos <= 0 || fastHalfLifeNanos <= 0 || slowHalfLifeNanos <= 0
                    || volHalfLifeNanos <= 0 || liquidityHalfLifeNanos <= 0) {
                throw new IllegalArgumentException("half-lives must be positive");
            }
            if (fastHalfLifeNanos >= slowHalfLifeNanos) {
                throw new IllegalArgumentException(
                        "momentum needs fastHalfLife < slowHalfLife");
            }
            if (wQueueImbalance < 0 || wTradeImbalance < 0 || wOfi < 0 || wMomentum < 0) {
                throw new IllegalArgumentException("composite weights must be >= 0");
            }
        }

        public static Config defaults() {
            return new Config(500_000_000L, 2_000_000_000L, 20_000_000_000L,
                    10_000_000_000L, 10_000_000_000L, 1, 1, 1, 1);
        }
    }

    private final Config config;
    private final int symbolCount;
    private final FlowSignals[] flow;
    private final double sqrtFastHorizonSec;   // momentumZ scale, hoisted off the hot path
    private final double weightSum;            // composite denominator, likewise

    // Per-symbol state (dense arrays, symbol id = index).
    private final double[] microprice;
    private final double[] prevMid;
    private final long[] prevMidTime;
    private final double[] fastEma;
    private final double[] slowEma;
    private final double[] varRatePerSec;     // EWMA of r^2/dt  (per-second variance)
    private final double[] spreadEwma;
    private final double[] depthEwma;         // displayed bid+ask size at the inside
    private final double[] gapEwmaNanos;      // inter-quote arrival gap
    private final boolean[] hasMid;

    private long quoteCount;
    private long tradeCount;

    public SignalEngine(int symbolCount, Config config) {
        if (symbolCount < 1) {
            throw new IllegalArgumentException("symbolCount must be >= 1");
        }
        this.symbolCount = symbolCount;
        this.config = config;
        this.sqrtFastHorizonSec = Math.sqrt(config.fastHalfLifeNanos() * 1e-9);
        this.weightSum = config.wQueueImbalance() + config.wTradeImbalance()
                + config.wOfi() + config.wMomentum();
        this.flow = new FlowSignals[symbolCount];
        for (int s = 0; s < symbolCount; s++) {
            flow[s] = new FlowSignals(config.flowHalfLifeNanos());
        }
        this.microprice = MathUtils.nanArray(symbolCount);
        this.prevMid = new double[symbolCount];
        this.prevMidTime = new long[symbolCount];
        this.fastEma = new double[symbolCount];
        this.slowEma = new double[symbolCount];
        this.varRatePerSec = new double[symbolCount];
        this.spreadEwma = new double[symbolCount];
        this.depthEwma = new double[symbolCount];
        this.gapEwmaNanos = new double[symbolCount];
        this.hasMid = new boolean[symbolCount];
    }

    public SignalEngine(int symbolCount) {
        this(symbolCount, Config.defaults());
    }

    // ------------------------------------------------------------------
    // Feed (the hot path)
    // ------------------------------------------------------------------

    /**
     * Top-of-book update. Prices are raw doubles (FX rates directly; for
     * equities pass ticks or converted prices — any monotonic representation,
     * consistently per symbol). One-sided/NaN quotes are gaps: counted, but
     * no estimator updates and no baseline poisoning.
     */
    public void onQuote(int symbolId, double bid, long bidSize, double ask, long askSize,
                        long timestampNanos) {
        quoteCount++;
        flow[symbolId].onQuote(bid, bidSize, ask, askSize, timestampNanos);
        // Gap gate: sizes must be positive and prices DEALABLE (strictly
        // positive and finite) — !(x > 0) also catches NaN. A zero-price
        // placeholder quote passing this gate would seed prevMid = 0 and the
        // next return r = (mid − 0)/0 would poison the variance EWMA with
        // Inf/NaN forever.
        if (bidSize <= 0 || askSize <= 0
                || !(bid > 0) || !(ask > 0)
                || bid == Double.POSITIVE_INFINITY || ask == Double.POSITIVE_INFINITY) {
            hasMid[symbolId] = false;      // gap: next two-sided quote re-seeds
            return;
        }
        double mid = 0.5 * (bid + ask);
        microprice[symbolId] = FairValueEngine.microprice(bid, ask, bidSize, askSize);

        if (hasMid[symbolId]) {
            long dt = timestampNanos - prevMidTime[symbolId];
            if (dt > 0) {
                double dtSec = dt * 1e-9;
                // Momentum: time-aware EMAs (decay by elapsed time).
                double aFast = 1 - MathUtils.decayFactor(dt, config.fastHalfLifeNanos());
                double aSlow = 1 - MathUtils.decayFactor(dt, config.slowHalfLifeNanos());
                fastEma[symbolId] += aFast * (mid - fastEma[symbolId]);
                slowEma[symbolId] += aSlow * (mid - slowEma[symbolId]);
                // Volatility: EWMA of squared return per second (prevMid is
                // guaranteed dealable by the gap gate above).
                double r = (mid - prevMid[symbolId]) / prevMid[symbolId];
                double aVol = 1 - MathUtils.decayFactor(dt, config.volHalfLifeNanos());
                varRatePerSec[symbolId] += aVol * (r * r / dtSec - varRatePerSec[symbolId]);
                // Liquidity: spread, depth, arrival gap.
                double aLiq = 1 - MathUtils.decayFactor(dt, config.liquidityHalfLifeNanos());
                spreadEwma[symbolId] += aLiq * ((ask - bid) - spreadEwma[symbolId]);
                depthEwma[symbolId] += aLiq * ((bidSize + askSize) - depthEwma[symbolId]);
                gapEwmaNanos[symbolId] += aLiq * (dt - gapEwmaNanos[symbolId]);
                prevMid[symbolId] = mid;
                prevMidTime[symbolId] = timestampNanos;
            }
            // dt <= 0 (same-timestamp burst): fold into the same observation
            // window — mid/microprice refresh above, estimators wait.
        } else {
            // Seed all baselines from the first two-sided quote after a gap.
            // Seed EVERY estimator from this first post-gap quote, so a gap
            // (halt, stale feed) rebuilds cleanly instead of resuming a
            // pre-gap volatility/intensity into the first decisions. The
            // rate estimators (var, gap) have no value until a second quote,
            // so they zero here and seed on the next update.
            hasMid[symbolId] = true;
            prevMid[symbolId] = mid;
            prevMidTime[symbolId] = timestampNanos;
            fastEma[symbolId] = mid;
            slowEma[symbolId] = mid;
            spreadEwma[symbolId] = ask - bid;
            depthEwma[symbolId] = bidSize + askSize;
            varRatePerSec[symbolId] = 0;
            gapEwmaNanos[symbolId] = 0;
        }
    }

    /** Trade print with aggressor side (Lee-Ready if the venue doesn't say). */
    public void onTrade(int symbolId, boolean buyAggressor, long quantity, long timestampNanos) {
        tradeCount++;
        flow[symbolId].onTrade(buyAggressor, quantity, timestampNanos);
    }

    // ------------------------------------------------------------------
    // Imbalance
    // ------------------------------------------------------------------

    /** Time-decayed net order-flow imbalance, in size units (+ = buying pressure). */
    public double ofi(int symbolId) {
        return flow[symbolId].ofi();
    }

    /** Inside queue imbalance in [-1, 1]; 0 on a one-sided book. */
    public double queueImbalance(int symbolId) {
        return flow[symbolId].queueImbalance();
    }

    /** Signed aggressor-volume imbalance in [-1, 1]; 0 before any trade. */
    public double tradeImbalance(int symbolId) {
        return flow[symbolId].tradeImbalance();
    }

    // ------------------------------------------------------------------
    // Fair value / volatility / liquidity / momentum
    // ------------------------------------------------------------------

    /** Size-weighted microprice; NaN before the first two-sided quote. */
    public double microprice(int symbolId) {
        return microprice[symbolId];
    }

    /**
     * Streaming realized volatility as return per √second (0 until two
     * valid mids). Multiply by √(seconds per horizon) for a horizon vol,
     * e.g. × √(252 × 6.5 × 3600) to annualize an equity session.
     */
    public double volPerSqrtSecond(int symbolId) {
        return Math.sqrt(Math.max(varRatePerSec[symbolId], 0));
    }

    /** Time-decayed absolute inside spread (0 until seeded). */
    public double spread(int symbolId) {
        return spreadEwma[symbolId];
    }

    /** Decayed spread as basis points of the current mid; NaN before a mid. */
    public double spreadBps(int symbolId) {
        double mid = prevMid[symbolId];
        return hasMid[symbolId] && mid > 0 ? spreadEwma[symbolId] / mid * 1e4 : Double.NaN;
    }

    /** Time-decayed displayed depth (bid + ask size at the inside). */
    public double topDepth(int symbolId) {
        return depthEwma[symbolId];
    }

    /** Quote arrival intensity, per second (0 until two quotes). */
    public double quoteIntensityPerSecond(int symbolId) {
        double gap = gapEwmaNanos[symbolId];
        return gap > 0 ? 1e9 / gap : 0;
    }

    /**
     * Normalized momentum: (fast EMA − slow EMA) / mid. Positive = the
     * short horizon trades above the long horizon (upward drift). 0 until
     * seeded.
     */
    public double momentum(int symbolId) {
        if (!hasMid[symbolId] || prevMid[symbolId] <= 0) {
            return 0;
        }
        return (fastEma[symbolId] - slowEma[symbolId]) / prevMid[symbolId];
    }

    // ------------------------------------------------------------------
    // Composite
    // ------------------------------------------------------------------

    /**
     * The weighted intraday composite in roughly [-1, 1]: each ingredient
     * is dimensionless (imbalances natively; OFI as a clamped fraction of
     * decayed displayed depth; momentum as a clamped ratio to volatility
     * over the fast horizon), blended by the Config weights and divided by
     * their sum. 0 when unseeded or all weights are 0. A research scaffold
     * — validate any weighting through the {@code alpha} package before
     * trading it.
     */
    public double alpha(int symbolId) {
        if (weightSum <= 0) {
            return 0;
        }
        double score = config.wQueueImbalance() * queueImbalance(symbolId)
                + config.wTradeImbalance() * tradeImbalance(symbolId)
                + config.wOfi() * normalizedOfi(symbolId)
                + config.wMomentum() * momentumZ(symbolId);
        return score / weightSum;
    }

    /** OFI as a fraction of decayed displayed depth, clamped to [-1, 1]. */
    public double normalizedOfi(int symbolId) {
        double depth = depthEwma[symbolId];
        if (depth <= 0) {
            return 0;
        }
        return MathUtils.clamp(flow[symbolId].ofi() / depth, -1, 1);
    }

    /**
     * Momentum scaled by the volatility accrued over the fast horizon —
     * a t-statistic-like "drift vs noise" ratio — clamped to [-1, 1].
     */
    public double momentumZ(int symbolId) {
        double vol = volPerSqrtSecond(symbolId);
        if (vol <= 0) {
            return 0;
        }
        return MathUtils.clamp(momentum(symbolId) / (vol * sqrtFastHorizonSec), -1, 1);
    }

    public int symbolCount() {
        return symbolCount;
    }

    public long quoteCount() {
        return quoteCount;
    }

    public long tradeCount() {
        return tradeCount;
    }
}

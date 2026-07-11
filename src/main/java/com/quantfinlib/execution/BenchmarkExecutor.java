package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.util.MathUtils;

/**
 * The dynamic benchmark execution algorithm: one stateful executor that
 * works a parent order toward any of the standard benchmarks — <b>VWAP,
 * TWAP, Arrival Price, Implementation Shortfall, Closing Price, Opening
 * Price, and Participation (POV)</b> — and, unlike a precomputed slice list
 * ({@link TwapScheduler}, {@link VwapScheduler},
 * {@link ImplementationShortfallScheduler}), <em>re-decides every interval
 * from live market state</em>. Cross-asset: prices/sizes are doubles, so it
 * serves equities and FX identically.
 *
 * <h2>Two layers</h2>
 * <ol>
 *   <li><b>The benchmark curve</b> — each {@link Benchmark} defines the
 *       fraction of the parent that <em>should</em> be complete by now.
 *       Time-driven benchmarks (TWAP linear, Arrival/IS front-loaded, Close
 *       back-loaded, Open aggressively front-loaded) use elapsed schedule
 *       fraction; volume-driven benchmarks (VWAP, POV) use the realized
 *       volume curve — VWAP against the expected cumulative profile, POV
 *       against a fixed share of actual volume.</li>
 *   <li><b>The dynamic adjustment</b> — the raw "behind schedule" quantity
 *       is then shaped by the real-time inputs a production algo watches:
 *       <ul>
 *         <li><b>Alpha</b> — expected short-term move: trade faster when the
 *             price is about to move against you (alpha in your trading
 *             direction), slower when it favors waiting;</li>
 *         <li><b>Spread &amp; volatility</b> — the cost/urgency trade-off:
 *             a wide spread or (for passive benchmarks) high volatility
 *             damps aggression; for Arrival/IS, volatility <em>raises</em>
 *             urgency (timing risk);</li>
 *         <li><b>Liquidity</b> — the interval child is capped at a
 *             participation fraction of displayed depth, so the algo never
 *             asks for more than the book can give;</li>
 *         <li><b>Fill rate / schedule drift</b> — feed realized fills back
 *             via {@link #onFill}; falling behind pulls the next child up,
 *             running ahead lets it ease off.</li>
 *       </ul></li>
 * </ol>
 *
 * <p>Usage: construct with the parent, benchmark and horizon; each interval
 * call {@link #dueQuantity} with the current {@link MarketState} and the
 * elapsed schedule fraction (and, for volume benchmarks, feed market prints
 * via {@link #onMarketVolume}); send the returned child; report fills via
 * {@link #onFill}. The executor is single-parent, single-writer.</p>
 */
public final class BenchmarkExecutor {

    /** The benchmark this parent is measured against. */
    public enum Benchmark {
        /** Equal participation over time. */
        TWAP,
        /** Follow the expected volume curve. */
        VWAP,
        /** Minimize slippage vs the price when the order arrived (front-loaded). */
        ARRIVAL_PRICE,
        /** Almgren-Chriss shortfall: front-loaded, volatility raises urgency. */
        IMPLEMENTATION_SHORTFALL,
        /** Track the closing price: back-loaded toward the close. */
        CLOSING_PRICE,
        /** Track the opening price: aggressively front-loaded at the open. */
        OPENING_PRICE,
        /** Percentage-of-volume: a fixed share of realized volume (time-agnostic). */
        PARTICIPATION
    }

    /**
     * A snapshot of the real-time inputs a benchmark algo evaluates. All
     * fields are optional in the sense that a neutral value disables that
     * input: {@code spread}=0, {@code volatility}=0, {@code alpha}=0,
     * {@code displayedDepth}=+∞ (no liquidity cap),
     * {@code expectedVolumeFractionElapsed}=schedule fraction (VWAP falls
     * back to TWAP), {@code impactBps}=0. NaN in any field is treated as
     * its neutral value — a transient bad input must degrade the input,
     * never silently stall the parent.
     *
     * <p><b>Units contract (normalized, so the shipped models plug in
     * directly):</b></p>
     * <ul>
     *   <li>{@code volatility} — a normalized volatility-REGIME signal,
     *       ~0 calm to ~1 extreme:
     *       {@code microstructure.VolatilityCurve.regime(bucket, currentVol)}
     *       produces exactly this (elevated vs the time-of-day baseline).
     *       Feeding a raw per-√second return (~1e-4) would make the term
     *       inert;</li>
     *   <li>{@code alpha} — a normalized expected-move signal in [-1, 1]
     *       (+ = up): {@code SignalEngine.alpha()} plugs in directly. A raw
     *       price-return forecast must be rescaled (÷ its typical magnitude)
     *       first;</li>
     *   <li>{@code displayedDepth} — size dealable NOW (displayed top of
     *       book). Feeding {@code HiddenLiquidityDetector.estimatedTrueDepth}
     *       is a conscious choice to size against inferred reserve, not a
     *       drop-in.</li>
     * </ul>
     *
     * @param mid                            current mid (for reference/markout)
     * @param spread                         bid/ask spread in price units (cost)
     * @param volatility                     normalized vol-regime signal, ~0..1
     * @param displayedDepth                 size available to take now (liquidity cap)
     * @param expectedVolumeFractionElapsed  VWAP curve: fraction of the day's
     *                                       volume expected to have traded by now
     * @param alpha                          normalized expected-move signal in
     *                                       [-1, 1] (+ = up)
     * @param impactBps                      estimated impact of a full child in
     *                                       bps ({@code MarketImpactModel} /
     *                                       {@code ml.MarketImpactPredictor}
     *                                       output); damps aggression alongside
     *                                       the spread — trading into your own
     *                                       impact is a cost like crossing one
     */
    public record MarketState(double mid, double spread, double volatility,
                              double displayedDepth, double expectedVolumeFractionElapsed,
                              double alpha, double impactBps) {

        /** A neutral state: no spread/vol/alpha/impact, unlimited depth, VWAP=TWAP. */
        public static MarketState neutral(double mid, double scheduleFraction) {
            return new MarketState(mid, 0, 0, Double.POSITIVE_INFINITY,
                    scheduleFraction, 0, 0);
        }
    }

    private final Side side;
    private final long parentQty;
    private final Benchmark benchmark;
    private final double participationRate;    // POV only
    private final double alphaUrgency;         // sensitivity to alpha
    private final double maxDepthFraction;     // liquidity cap

    private long executed;
    private long marketVolume;                 // for VWAP/POV realized volume

    /**
     * @param side              buy or sell (sets the alpha sign convention)
     * @param parentQty         total quantity to execute
     * @param benchmark         the benchmark to track
     * @param participationRate POV target in (0,1] (ignored unless PARTICIPATION)
     * @param alphaUrgency      how hard alpha shifts the pace (0 disables; ~5–20 typical)
     * @param maxDepthFraction  cap each child at this fraction of displayed depth (0,1]
     */
    public BenchmarkExecutor(Side side, long parentQty, Benchmark benchmark,
                             double participationRate, double alphaUrgency,
                             double maxDepthFraction) {
        if (parentQty <= 0) {
            throw new IllegalArgumentException("parentQty must be > 0");
        }
        if (benchmark == Benchmark.PARTICIPATION
                && (participationRate <= 0 || participationRate > 1)) {
            throw new IllegalArgumentException("PARTICIPATION needs participationRate in (0,1]");
        }
        if (alphaUrgency < 0) {
            throw new IllegalArgumentException("alphaUrgency must be >= 0");
        }
        if (maxDepthFraction <= 0 || maxDepthFraction > 1) {
            throw new IllegalArgumentException("maxDepthFraction must be in (0,1]");
        }
        this.side = side;
        this.parentQty = parentQty;
        this.benchmark = benchmark;
        this.participationRate = participationRate;
        this.alphaUrgency = alphaUrgency;
        this.maxDepthFraction = maxDepthFraction;
    }

    /**
     * Sensible defaults: alpha urgency 1 (a full-scale normalized alpha of
     * ±1 doubles/halves the pace — smooth, never rail-pinned), child capped
     * at 25% of displayed depth. PARTICIPATION must state its rate — use
     * {@link #pov}.
     */
    public static BenchmarkExecutor of(Side side, long parentQty, Benchmark benchmark) {
        if (benchmark == Benchmark.PARTICIPATION) {
            throw new IllegalArgumentException(
                    "PARTICIPATION needs an explicit rate: use pov(side, qty, rate)");
        }
        return new BenchmarkExecutor(side, parentQty, benchmark, 0.1, 1, 0.25);
    }

    /** POV convenience. */
    public static BenchmarkExecutor pov(Side side, long parentQty, double participationRate) {
        return new BenchmarkExecutor(side, parentQty, Benchmark.PARTICIPATION,
                participationRate, 1, 0.25);
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /** A market print that was NOT our fill (drives VWAP/POV realized volume). */
    public void onMarketVolume(long qty) {
        if (qty > 0) {
            marketVolume += qty;
        }
    }

    /** Our own child fill. */
    public void onFill(long qty) {
        if (qty > 0) {
            executed += qty;
        }
    }

    // ------------------------------------------------------------------
    // The decision
    // ------------------------------------------------------------------

    /**
     * Shares to send now, given the current market and how far through the
     * schedule we are. Returns 0 when on/ahead of schedule or done; caps at
     * the parent remainder and at {@code maxDepthFraction × displayedDepth}.
     *
     * @param scheduleFraction elapsed fraction of the execution horizon in
     *                         [0, 1] (wall-clock progress); ignored by
     *                         PARTICIPATION, which is volume-driven
     * @param m                the live market snapshot
     */
    public long dueQuantity(double scheduleFraction, MarketState m) {
        long remaining = parentQty - executed;
        if (remaining <= 0) {
            return 0;
        }
        double f = clamp01(scheduleFraction);

        long behind;
        if (benchmark == Benchmark.PARTICIPATION) {
            // Volume-driven: target = participation × others' volume.
            long target = (long) (participationRate * marketVolume);
            behind = target - executed;
        } else {
            // Target completion by now = curve(progress) × parent. A NaN
            // volume-curve input degrades VWAP to the time fraction —
            // (long) of NaN is 0, which would read as "nothing due" and
            // silently stall the parent.
            double targetFrac = targetCompletion(f, m);
            if (Double.isNaN(targetFrac)) {
                targetFrac = f;
            }
            behind = (long) Math.round(targetFrac * parentQty) - executed;
        }
        if (behind <= 0) {
            return 0;
        }

        // Dynamic shaping: alpha pulls the pace, spread/vol damp it. For
        // PARTICIPATION the multiplier may only DAMP (clamp to <= 1): the
        // participation rate is a hard promise to the client, and letting
        // alpha push a POV child to 4x "behind" realizes participation
        // far above the configured rate — the one number POV must honor
        // (PovTracker treats it as a hard cap; this engine must agree).
        double urgency = urgencyMultiplier(m);
        if (benchmark == Benchmark.PARTICIPATION) {
            urgency = Math.min(urgency, 1.0);
        }
        long shaped = (long) Math.ceil(behind * urgency);

        // Liquidity cap and parent remainder.
        long cap = remaining;
        if (m.displayedDepth() < Double.POSITIVE_INFINITY) {
            cap = Math.min(cap, (long) Math.floor(maxDepthFraction * m.displayedDepth()));
        }
        return Math.max(0, Math.min(shaped, cap));
    }

    /**
     * Fraction of the parent that SHOULD be complete at schedule progress
     * {@code f} under this benchmark's curve.
     */
    private double targetCompletion(double f, MarketState m) {
        return switch (benchmark) {
            case TWAP -> f;
            case VWAP -> clamp01(m.expectedVolumeFractionElapsed());
            // Front-loaded: 1−(1−f)² trades more early (cuts timing risk).
            case ARRIVAL_PRICE, IMPLEMENTATION_SHORTFALL -> 1 - (1 - f) * (1 - f);
            // Back-loaded: f² keeps weight near the close.
            case CLOSING_PRICE -> f * f;
            // Aggressively front-loaded: √f is near-done early.
            case OPENING_PRICE -> Math.sqrt(f);
            // Volume-driven, branched off before this switch: a silent
            // fallback value here would make a mis-routed POV behave like
            // TWAP with no error — fail loud instead.
            case PARTICIPATION -> throw new AssertionError(
                    "PARTICIPATION is volume-driven and handled before the curve");
        };
    }

    /**
     * How hard a 1% relative trading cost (spread fraction of mid plus
     * impact as a fraction) damps aggression: the term is
     * {@code 1/(1 + cost × SPREAD_SENSITIVITY)}, so a 1% cost halves the
     * pace and a 2-pip FX spread (~0.002%) barely registers.
     * A deliberate calibration constant, not a unit conversion.
     */
    public static final double SPREAD_SENSITIVITY = 100;

    /**
     * The real-time pace multiplier around the schedule. Alpha in the
     * trading direction speeds up (you're racing an adverse move); a wide
     * spread or estimated impact slows down (cost); volatility slows
     * passive benchmarks but speeds urgency-driven ones (timing risk).
     * Bounded to [0.25, 4].
     */
    private double urgencyMultiplier(MarketState m) {
        // NaN inputs are neutral (0): a transient bad signal must weaken
        // the input, never NaN-poison the multiplier into a silent stall
        // ((long) of NaN is 0 — "nothing due" forever).
        double alpha = Double.isNaN(m.alpha()) ? 0 : m.alpha();
        double signedAlpha = side == Side.BUY ? alpha : -alpha;
        double u = 1 + alphaUrgency * signedAlpha;

        // Cost of trading NOW: relative spread plus the estimated impact of
        // a full child (both dimensionless fractions of price), damping
        // aggression together — NaN in either fails the > 0 test = neutral.
        double cost = 0;
        if (m.mid() > 0 && m.spread() > 0) {
            cost += m.spread() / m.mid();
        }
        if (m.impactBps() > 0) {
            cost += m.impactBps() / 1e4;
        }
        if (cost > 0) {
            u *= 1.0 / (1 + cost * SPREAD_SENSITIVITY);
        }

        // Volatility (normalized regime, ~0..1): raises urgency for
        // shortfall/arrival (timing risk), lowers it for passive benchmarks.
        if (m.volatility() > 0) {
            if (benchmark == Benchmark.IMPLEMENTATION_SHORTFALL
                    || benchmark == Benchmark.ARRIVAL_PRICE) {
                u *= 1 + m.volatility();
            } else {
                u *= 1.0 / (1 + m.volatility());
            }
        }
        return MathUtils.clamp(u, 0.25, 4.0);
    }

    // ------------------------------------------------------------------
    // Progress / diagnostics
    // ------------------------------------------------------------------

    public long executed() {
        return executed;
    }

    public long remaining() {
        return parentQty - executed;
    }

    public long parentQty() {
        return parentQty;
    }

    public long marketVolume() {
        return marketVolume;
    }

    public boolean done() {
        return executed >= parentQty;
    }

    /** Realized participation vs other-flow volume (NaN before any market print). */
    public double realizedParticipation() {
        return marketVolume == 0 ? Double.NaN : (double) executed / marketVolume;
    }

    /**
     * Schedule drift: executed fraction minus the benchmark's target
     * fraction at {@code scheduleFraction}. Positive = ahead, negative =
     * behind. For PARTICIPATION this compares against the participation
     * target instead of the time curve.
     */
    public double scheduleDrift(double scheduleFraction, MarketState m) {
        double executedFrac = (double) executed / parentQty;
        double target = benchmark == Benchmark.PARTICIPATION
                ? (marketVolume == 0 ? 0 : Math.min(1.0,
                        participationRate * marketVolume / parentQty))
                : targetCompletion(clamp01(scheduleFraction), m);
        return executedFrac - target;
    }

    public Side side() {
        return side;
    }

    public Benchmark benchmark() {
        return benchmark;
    }

    private static double clamp01(double x) {
        return MathUtils.clamp(x, 0, 1);
    }
}

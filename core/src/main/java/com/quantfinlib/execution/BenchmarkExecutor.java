package com.quantfinlib.execution;

import com.quantfinlib.core.Side;
import com.quantfinlib.stats.Numerics;

/**
 * The dynamic benchmark execution algorithm: one stateful executor that
 * works a parent order toward any of the standard benchmarks (<b>VWAP,
 * TWAP, Arrival Price, Implementation Shortfall, Closing Price, Opening
 * Price, and Participation (POV)</b>). Unlike a precomputed slice list
 * ({@link TwapScheduler}, {@link VwapScheduler},
 * {@link ImplementationShortfallScheduler}), it <em>re-decides every
 * interval from live market state</em>. Cross-asset: prices/sizes are
 * doubles, so it serves equities and FX identically.
 *
 * <h2>Two layers</h2>
 * <ol>
 *   <li><b>The benchmark curve</b>: each {@link Benchmark} defines the
 *       fraction of the parent that <em>should</em> be complete by now.
 *       Time-driven benchmarks (TWAP linear, Arrival/IS front-loaded, Close
 *       back-loaded, Open aggressively front-loaded) use elapsed schedule
 *       fraction; volume-driven benchmarks (VWAP, POV) use the realized
 *       volume curve, VWAP against the expected cumulative profile and POV
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
 *
 * <h2>In-flight children</h2>
 * <p>Report each child with {@link #onChildSent} and, when the venue is
 * done with it, the unfilled remainder with {@link #onChildDone}. Sent but
 * unfilled quantity counts against the schedule, so a child that rests
 * unfilled is not sent again next interval; without this bookkeeping the
 * deficit would be re-sent every interval while the first child still
 * works. Fills reported through {@link #onFill} move quantity from open to
 * executed.</p>
 *
 * <h2>Completion</h2>
 * <p>Over the last {@code 1 - rampStart} of the horizon the depth cap is
 * relaxed linearly from {@code maxDepthFraction} to all of the displayed
 * depth, and at {@code scheduleFraction >= 1} every curve's target is the
 * full parent, so the whole unworked remainder is due, limited only by what
 * is displayed and by the maximum child. A residual left at the horizon is
 * then one the book could not absorb, visible as {@link #remaining()}. Optional child-size bounds
 * ({@link #withChildBounds}) suppress dribble orders and cap signalling.</p>
 *
 * <h2>Participation</h2>
 * <p>The POV rate {@code p} is a share of total traded volume, including
 * ours (the industry definition). With {@code V} the volume printed by
 * others, executing {@code x} gives a share {@code x / (x + V)}; setting this
 * equal to {@code p} gives the target {@code x = p / (1 - p) * V}. Using
 * {@code p * V} instead would realise only {@code p / (1 + p)} (9.1 % for a
 * 10 % target).</p>
 */
public final class BenchmarkExecutor {

    /** The benchmark this parent is measured against. */
    public enum Benchmark {
        /** Equal participation over time. */
        TWAP,
        /** Follow the expected volume curve. */
        VWAP,
        /**
         * Front-loaded fixed curve {@code 1 - (1 - f)^2} against the arrival
         * price. A heuristic shape, not derived from a cost model; use
         * IMPLEMENTATION_SHORTFALL for a risk-aversion-driven trajectory.
         */
        ARRIVAL_PRICE,
        /**
         * Almgren-Chriss shortfall trajectory (Almgren and Chriss, 2000,
         * eq. 18): completed fraction
         * {@code 1 - sinh(kT (1 - f)) / sinh(kT)} with urgency {@code kT}
         * set by {@link #withShortfallUrgency} (default 2); volatility
         * raises the pace further.
         */
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
     *   <li>{@code volatility} — a normalized volatility-regime signal,
     *       ~0 calm to ~1 extreme:
     *       {@code microstructure.VolatilityCurve.regime(bucket, currentVol)}
     *       produces exactly this (elevated vs the time-of-day baseline).
     *       Feeding a raw per-√second return (~1e-4) would make the term
     *       inert;</li>
     *   <li>{@code alpha} — a normalized expected-move signal in [-1, 1]
     *       (+ = up): {@code SignalEngine.alpha()} plugs in directly. A raw
     *       price-return forecast must be rescaled (÷ its typical magnitude)
     *       first;</li>
     *   <li>{@code displayedDepth} — size dealable now (displayed top of
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
    private long open;                         // sent, not yet filled or done
    private long marketVolume;                 // for VWAP/POV realized volume
    private double shortfallKappaT = 2.0;
    private long minChild;
    private long maxChild = Long.MAX_VALUE;
    private double rampStart = 0.9;

    /**
     * @param side              buy or sell (sets the alpha sign convention)
     * @param parentQty         total quantity to execute
     * @param benchmark         the benchmark to track
     * @param participationRate POV target in (0,1] (ignored unless participation)
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
                && !(participationRate > 0 && participationRate < 1)) {
            throw new IllegalArgumentException("PARTICIPATION needs participationRate in (0,1)");
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
     * ±1 doubles/halves the pace, smoothly and never rail-pinned), child
     * capped at 25% of displayed depth. Participation must state its rate;
     * use {@link #pov}.
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

    /**
     * Urgency {@code kT} of the IMPLEMENTATION_SHORTFALL trajectory
     * (Almgren-Chriss kappa times horizon, kappa = sqrt(lambda sigma^2 / eta));
     * must be finite and positive. Small values approach TWAP, large values
     * front-load.
     */
    public BenchmarkExecutor withShortfallUrgency(double kappaT) {
        if (!(kappaT > 0) || Double.isInfinite(kappaT)) {
            throw new IllegalArgumentException("kappaT must be finite and > 0");
        }
        this.shortfallKappaT = kappaT;
        return this;
    }

    /**
     * Child-size bounds: below {@code min} nothing is sent (except to finish
     * the parent), above {@code max} the child is capped.
     */
    public BenchmarkExecutor withChildBounds(long min, long max) {
        if (min < 0 || max < Math.max(1, min)) {
            throw new IllegalArgumentException("need 0 <= min <= max, max >= 1");
        }
        this.minChild = min;
        this.maxChild = max;
        return this;
    }

    /**
     * Schedule fraction in [0, 1) from which the depth cap is relaxed
     * linearly towards the full remainder at the horizon (default 0.9).
     */
    public BenchmarkExecutor withCompletionRamp(double start) {
        if (!(start >= 0 && start < 1)) {
            throw new IllegalArgumentException("ramp start must be in [0,1)");
        }
        this.rampStart = start;
        return this;
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /** A child order of {@code qty} was sent and is now working. */
    public void onChildSent(long qty) {
        if (qty > 0) {
            open += qty;
        }
    }

    /**
     * A child finished (cancelled, expired or IOC remainder) with
     * {@code unfilledQty} never executed; that quantity stops counting as
     * open. Fills must already have been reported with {@link #onFill}.
     */
    public void onChildDone(long unfilledQty) {
        if (unfilledQty > 0) {
            open = Math.max(0, open - unfilledQty);
        }
    }

    /** A market print that was not our fill (drives VWAP/POV realized volume). */
    public void onMarketVolume(long qty) {
        if (qty > 0) {
            marketVolume += qty;
        }
    }

    /** Our own child fill; reduces the open quantity by the same amount. */
    public void onFill(long qty) {
        if (qty > 0) {
            executed += qty;
            open = Math.max(0, open - qty);
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
     *                         participation, which is volume-driven
     * @param m                the live market snapshot
     */
    public long dueQuantity(double scheduleFraction, MarketState m) {
        long remaining = parentQty - executed - open;
        if (remaining <= 0) {
            return 0;
        }
        double f = clamp01(scheduleFraction);
        long behind;
        if (benchmark == Benchmark.PARTICIPATION) {
            behind = povTarget() - executed - open;
        } else {
            // Target completion by now = curve(progress) × parent. A NaN
            // volume-curve input degrades VWAP to the time fraction —
            // (long) of NaN is 0, which would read as "nothing due" and
            // silently stall the parent.
            double targetFrac = targetCompletion(f, m);
            if (Double.isNaN(targetFrac)) {
                targetFrac = f;
            }
            behind = Math.round(targetFrac * parentQty) - executed - open;
        }
        if (behind <= 0) {
            return 0;
        }

        // Dynamic shaping: alpha pulls the pace, spread/vol damp it. For
        // participation the multiplier may only damp (clamp to <= 1): the
        // participation rate is a hard promise to the client, and letting
        // alpha push a POV child to 4x "behind" realizes participation
        // far above the configured rate — the one number POV must honor
        // (PovTracker treats it as a hard cap; this engine must agree).
        double urgency = urgencyMultiplier(m);
        if (benchmark == Benchmark.PARTICIPATION) {
            urgency = Math.min(urgency, 1.0);
        }
        long shaped = (long) Math.ceil(behind * urgency);

        // Liquidity cap (relaxed over the completion ramp), child bounds and
        // the parent remainder.
        long cap = Math.min(remaining, maxChild);
        if (m.displayedDepth() < Double.POSITIVE_INFINITY) {
            double frac = maxDepthFraction;
            if (benchmark != Benchmark.PARTICIPATION && f > rampStart) {
                frac += (1 - maxDepthFraction) * (f - rampStart) / (1 - rampStart);
            }
            // The 1e-9 absorbs binary rounding of the ramp fraction (0.95 - 0.9
            // is 0.0499999...), which would otherwise floor 25000 to 24999.
            cap = Math.min(cap, (long) Math.floor(frac * m.displayedDepth() + 1e-9));
        }
        long due = Math.max(0, Math.min(shaped, cap));
        if (due < minChild && due < remaining) {
            return 0;
        }
        return due;
    }

    /** POV target executed quantity: {@code p / (1 - p)} times others' volume. */
    private long povTarget() {
        // The 1e-6 absorbs binary rounding (0.1 / 0.9 * 900000 must give 100000).
        return (long) Math.floor(participationRate / (1 - participationRate) * marketVolume
                + 1e-6);
    }

    /**
     * Fraction of the parent that should be complete at schedule progress
     * {@code f} under this benchmark's curve.
     */
    private double targetCompletion(double f, MarketState m) {
        return switch (benchmark) {
            case TWAP -> f;
            case VWAP -> clamp01(m.expectedVolumeFractionElapsed());
            // Front-loaded: 1−(1−f)² trades more early (cuts timing risk).
            case ARRIVAL_PRICE -> 1 - (1 - f) * (1 - f);
            case IMPLEMENTATION_SHORTFALL ->
                    1 - Math.sinh(shortfallKappaT * (1 - f)) / Math.sinh(shortfallKappaT);
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
     * A calibration constant, not a unit conversion.
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

        // Cost of trading now: relative spread plus the estimated impact of
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
        return Numerics.clamp(u, 0.25, 4.0);
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

    /** Sent child quantity not yet filled or reported done. */
    public long openQuantity() {
        return open;
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

    /**
     * Realised share of total volume, {@code executed / (executed + others)};
     * NaN before any volume.
     */
    public double realizedParticipation() {
        long total = executed + marketVolume;
        return total == 0 ? Double.NaN : (double) executed / total;
    }

    /**
     * Schedule drift: executed fraction minus the benchmark's target
     * fraction at {@code scheduleFraction}. Positive = ahead, negative =
     * behind. For participation this compares against the participation
     * target instead of the time curve.
     */
    public double scheduleDrift(double scheduleFraction, MarketState m) {
        double executedFrac = (double) executed / parentQty;
        double target = benchmark == Benchmark.PARTICIPATION
                ? Math.min(1.0, (double) povTarget() / parentQty)
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
        return Numerics.clamp(x, 0, 1);
    }
}

package com.quantfinlib.trading;

import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.orderbook.Side;

/**
 * Streaming two-sided quoter on the fast lane — the market-making loop:
 * tick in → mid → inventory skew → tick-grid snap → two orders out through
 * the {@link HftRiskGate} and {@link HftOrderGateway}, with zero allocation
 * per tick.
 *
 * <p>Quote construction per tick (all primitive arithmetic):</p>
 * <pre>
 *   skew = −position × skewPerUnit          (long inventory shades DOWN)
 *   bid  = mid − halfSpread + skew           → rounded DOWN to the grid
 *   ask  = mid + halfSpread + skew           → rounded UP   to the grid
 * </pre>
 *
 * <p>Inventory comes straight from the risk gate's position (updated by
 * fills via {@link HftRiskGate#onFill}), so the skew loop closes without
 * any extra state. Both sides still pass the gate's own checks — a quoter
 * cannot out-trade its risk limits.</p>
 *
 * <p><b>Conflation.</b> Venues throttle quote updates and stale requotes
 * waste the wire: a re-quote is suppressed unless the mid moved at least
 * {@code minMove} <em>or</em> {@code minRequoteIntervalNanos} elapsed.
 * Suppression is counted, never silent.</p>
 *
 * <p>Register as a {@link TickListener} on the bus (runs on the consumer
 * thread), exactly like a strategy. Single-threaded by construction; the
 * per-symbol state arrays are indexed by dense symbol id.</p>
 *
 * <p><b>Mixed books</b>: half-spreads, skews, conflation thresholds and tick
 * grids are per-instrument quantities (a EURUSD half-spread is ~100× too
 * tight for USDJPY), so the constructor's config is only the default —
 * override per symbol with {@link #configureSymbol} and one quoter serves
 * the whole book.</p>
 */
public final class HftQuoter implements TickListener {

    /**
     * Quoting parameters. {@code halfSpread}/{@code skewPerUnit}/{@code minMove}
     * are in price terms (use {@code fx.CurrencyPair#priceFromPips} to
     * convert market conventions); {@code tickSchedule} is optional grid
     * snapping (null = quote raw prices).
     */
    public record Config(long quoteSize, double halfSpread, double skewPerUnit,
                         long minRequoteIntervalNanos, double minMove,
                         TickSizeSchedule tickSchedule) {

        public Config {
            if (quoteSize <= 0 || halfSpread <= 0 || skewPerUnit < 0
                    || minRequoteIntervalNanos < 0 || minMove < 0) {
                throw new IllegalArgumentException(
                        "quoteSize and halfSpread must be > 0; skew/conflation params >= 0");
            }
        }

        /** Minimal config: size and half-spread, no skew, no conflation, no grid. */
        public static Config of(long quoteSize, double halfSpread) {
            return new Config(quoteSize, halfSpread, 0, 0, 0, null);
        }

        public Config withSkewPerUnit(double skew) {
            return new Config(quoteSize, halfSpread, skew, minRequoteIntervalNanos, minMove,
                    tickSchedule);
        }

        /**
         * Suppresses a re-quote only when the mid moved less than
         * {@code minMovePrice} <b>AND</b> the last quote is younger than
         * {@code minIntervalNanos} — BOTH gates must pass to suppress.
         *
         * <p><b>Pitfall</b>: {@code minIntervalNanos = 0} therefore disables
         * conflation entirely (nothing is ever "younger than 0 ns"), it does
         * NOT mean "suppress on move alone". For purely move-gated
         * conflation — the usual choice for derived crosses — use
         * {@link #withMinMove}.</p>
         */
        public Config withConflation(long minIntervalNanos, double minMovePrice) {
            return new Config(quoteSize, halfSpread, skewPerUnit, minIntervalNanos, minMovePrice,
                    tickSchedule);
        }

        /**
         * Purely move-gated conflation: re-quote only when the mid has moved
         * at least {@code minMovePrice}, regardless of age (the interval
         * gate is set effectively infinite). This is the fan-out control
         * that makes dense synthetic-cross books scale — measured: 10,000
         * crosses over 200 legs run at ~8× the throughput of quote-everything
         * with a 2-pip gate suppressing ~99% of updates.
         */
        public Config withMinMove(double minMovePrice) {
            return new Config(quoteSize, halfSpread, skewPerUnit, Long.MAX_VALUE / 4,
                    minMovePrice, tickSchedule);
        }

        public Config withTickSchedule(TickSizeSchedule schedule) {
            return new Config(quoteSize, halfSpread, skewPerUnit, minRequoteIntervalNanos,
                    minMove, schedule);
        }
    }

    private final HftOrderGateway gateway;
    private final HftRiskGate riskGate;

    // Per-symbol quoting parameters, dense-id indexed: half-spreads, skews
    // and grids are inherently per-instrument (a EURUSD half-spread is ~100×
    // too tight for USDJPY), so one quoter serves a mixed book. Seeded from
    // the constructor default; overridden per symbol via configureSymbol.
    private final long[] quoteSize;
    private final double[] halfSpread;
    private final double[] skewPerUnit;
    private final long[] minRequoteIntervalNanos;
    private final double[] minMove;
    private final TickSizeSchedule[] grids;

    // Per-symbol conflation state, dense-id indexed. NaN mid = never quoted.
    private final double[] lastQuoteMid;
    private final long[] lastQuoteNanos;

    // Diagnostics (single-writer counters, read from anywhere).
    private long quoteUpdates;
    private long suppressedUpdates;
    private long rejectedSides;

    public HftQuoter(HftOrderGateway gateway, int maxSymbols, Config defaults) {
        this.gateway = gateway;
        this.riskGate = gateway.riskGate();
        this.quoteSize = new long[maxSymbols];
        this.halfSpread = new double[maxSymbols];
        this.skewPerUnit = new double[maxSymbols];
        this.minRequoteIntervalNanos = new long[maxSymbols];
        this.minMove = new double[maxSymbols];
        this.grids = new TickSizeSchedule[maxSymbols];
        this.lastQuoteMid = new double[maxSymbols];
        this.lastQuoteNanos = new long[maxSymbols];
        java.util.Arrays.fill(lastQuoteMid, Double.NaN);
        for (int i = 0; i < maxSymbols; i++) {
            apply(i, defaults);
        }
    }

    /**
     * Per-symbol quoting parameters (cold path — call at setup or on config
     * updates from the same thread that quotes, or before {@code start}).
     */
    public HftQuoter configureSymbol(int symbolId, Config config) {
        apply(symbolId, config);
        return this;
    }

    private void apply(int symbolId, Config config) {
        quoteSize[symbolId] = config.quoteSize();
        halfSpread[symbolId] = config.halfSpread();
        skewPerUnit[symbolId] = config.skewPerUnit();
        minRequoteIntervalNanos[symbolId] = config.minRequoteIntervalNanos();
        minMove[symbolId] = config.minMove();
        grids[symbolId] = config.tickSchedule();
    }

    /**
     * The hot path: one tick → (possibly) one two-sided quote. Runs on the
     * bus consumer thread; no allocation, no locks, no boxing.
     */
    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        // Conflation: suppress unless the mid moved or the interval expired.
        double lastMid = lastQuoteMid[symbolId];
        if (!Double.isNaN(lastMid)
                && Math.abs(price - lastMid) < minMove[symbolId]
                && timestampNanos - lastQuoteNanos[symbolId] < minRequoteIntervalNanos[symbolId]) {
            suppressedUpdates++;
            return;
        }

        // Inventory skew: long inventory shades both sides down to attract
        // buyers of our excess; short shades up. Spread width is preserved.
        long position = riskGate.position(symbolId);
        double skew = -position * skewPerUnit[symbolId];
        double bid = price - halfSpread[symbolId] + skew;
        double ask = price + halfSpread[symbolId] + skew;

        // Grid snap toward passivity: bid down, ask up — never through mid.
        // CLAMPED rounding: a heavily skewed bid can fall below the grid's
        // first band, and this runs on the bus consumer thread — throwing
        // here would kill every listener. Bad prices die at the risk gate.
        TickSizeSchedule grid = grids[symbolId];
        if (grid != null) {
            bid = grid.roundDownClamped(bid);
            ask = grid.roundUpClamped(ask);
        }

        // Two sides through the risk gate. A rejected side is counted and
        // skipped; the other side may still quote (one-sided market).
        long sizePerSide = quoteSize[symbolId];
        if (gateway.submit(symbolId, Side.BUY, sizePerSide, bid, timestampNanos) <= 0) {
            rejectedSides++;
        }
        if (gateway.submit(symbolId, Side.SELL, sizePerSide, ask, timestampNanos) <= 0) {
            rejectedSides++;
        }
        quoteUpdates++;
        lastQuoteMid[symbolId] = price;
        lastQuoteNanos[symbolId] = timestampNanos;
    }

    /** Quote updates actually sent (each is up to two orders). */
    public long quoteUpdates() {
        return quoteUpdates;
    }

    /** Updates suppressed by conflation. */
    public long suppressedUpdates() {
        return suppressedUpdates;
    }

    /** Individual sides refused by the risk gate or a full ring. */
    public long rejectedSides() {
        return rejectedSides;
    }
}

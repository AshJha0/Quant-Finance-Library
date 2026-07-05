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

        public Config withConflation(long minIntervalNanos, double minMovePrice) {
            return new Config(quoteSize, halfSpread, skewPerUnit, minIntervalNanos, minMovePrice,
                    tickSchedule);
        }

        public Config withTickSchedule(TickSizeSchedule schedule) {
            return new Config(quoteSize, halfSpread, skewPerUnit, minRequoteIntervalNanos,
                    minMove, schedule);
        }
    }

    private final HftOrderGateway gateway;
    private final HftRiskGate riskGate;
    private final Config config;

    // Per-symbol conflation state, dense-id indexed. NaN mid = never quoted.
    private final double[] lastQuoteMid;
    private final long[] lastQuoteNanos;

    // Diagnostics (single-writer counters, read from anywhere).
    private long quoteUpdates;
    private long suppressedUpdates;
    private long rejectedSides;

    public HftQuoter(HftOrderGateway gateway, int maxSymbols, Config config) {
        this.gateway = gateway;
        this.riskGate = gateway.riskGate();
        this.config = config;
        this.lastQuoteMid = new double[maxSymbols];
        this.lastQuoteNanos = new long[maxSymbols];
        java.util.Arrays.fill(lastQuoteMid, Double.NaN);
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
                && Math.abs(price - lastMid) < config.minMove()
                && timestampNanos - lastQuoteNanos[symbolId] < config.minRequoteIntervalNanos()) {
            suppressedUpdates++;
            return;
        }

        // Inventory skew: long inventory shades both sides down to attract
        // buyers of our excess; short shades up. Spread width is preserved.
        long position = riskGate.position(symbolId);
        double skew = -position * config.skewPerUnit();
        double bid = price - config.halfSpread() + skew;
        double ask = price + config.halfSpread() + skew;

        // Grid snap toward passivity: bid down, ask up — never through mid.
        TickSizeSchedule grid = config.tickSchedule();
        if (grid != null) {
            bid = grid.roundDown(bid);
            ask = grid.roundUp(ask);
        }

        // Two sides through the risk gate. A rejected side is counted and
        // skipped; the other side may still quote (one-sided market).
        if (gateway.submit(symbolId, Side.BUY, config.quoteSize(), bid, timestampNanos) <= 0) {
            rejectedSides++;
        }
        if (gateway.submit(symbolId, Side.SELL, config.quoteSize(), ask, timestampNanos) <= 0) {
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

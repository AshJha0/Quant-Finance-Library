package com.quantfinlib.trading;

import com.quantfinlib.marketdata.QuoteListener;
import com.quantfinlib.marketdata.StridedSequences;
import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.orderbook.Side;

/**
 * Streaming two-sided quoter on the fast lane: market data in, inventory
 * skew, tick-grid snap, then at most one working bid and one working offer
 * per symbol, maintained by cancel/replace through the
 * {@link HftOrderGateway}. Zero allocation per update.
 *
 * <p>Quote construction (all primitive arithmetic):</p>
 * <pre>
 *   fair = (bestBid + bestAsk) / 2          from onQuote; the last trade if no BBO yet
 *   skew = -position * skewPerUnit          (long inventory shades DOWN)
 *   bid  = fair - halfSpread + skew         rounded DOWN to the grid
 *   ask  = fair + halfSpread + skew         rounded UP   to the grid
 * </pre>
 *
 * <h2>Working orders</h2>
 * <p>Each side has at most one working order. On a requote the quoter
 * replaces it in place (new price, leaves restored to {@code quoteSize});
 * if the previous order is still PENDING_NEW or has a cancel or replace in
 * flight, the side waits for the venue's answer and the update is counted
 * as deferred, so quotes never stack. A side whose order has gone terminal
 * (filled, cancelled, rejected) is re-entered with a new order. If a
 * replace is refused by the risk gate or the throttle, the working order is
 * cancelled rather than left at a stale price. {@link #pullQuotes} cancels
 * both sides.</p>
 *
 * <h2>Market-data checks</h2>
 * <ul>
 *   <li>A NaN, zero, negative or infinite fair price is ignored and counted
 *       ({@link #invalidInputs()}).</li>
 *   <li>A crossed or locked input BBO pulls both quotes
 *       ({@link #crossedBooks()}).</li>
 *   <li>A computed bid at or above the best offer, or offer at or below the
 *       best bid, would take liquidity: that side is not quoted and its
 *       working order is cancelled ({@link #crossedSides()}).</li>
 * </ul>
 *
 * <p>Inventory comes from the risk gate's position, which the gateway
 * updates from venue fills. {@link #onTick} drives quoting only for symbols
 * with no two-sided BBO yet (trade-priced fallback); once {@link #onQuote}
 * has delivered a book, trades are ignored.</p>
 *
 * <p><b>Conflation.</b> A requote is suppressed unless the fair price moved
 * at least {@code minMove} or {@code minRequoteIntervalNanos} elapsed.
 * Suppression is counted.</p>
 *
 * <p>Single-threaded: call {@link #onTick}, {@link #onQuote} and
 * {@link #pullQuotes} from the gateway's trading thread (normally the bus
 * consumer). Half-spreads, skews and grids are per instrument; override the
 * constructor default per symbol with {@link #configureSymbol}.</p>
 */
public final class HftQuoter implements TickListener, QuoteListener {

    /**
     * Quoting parameters. {@code halfSpread}/{@code skewPerUnit}/{@code minMove}
     * are in price units (use {@code fx.CurrencyPair#priceFromPips} to
     * convert market conventions); {@code tickSchedule} is optional grid
     * snapping (null = quote raw prices).
     */
    public record Config(long quoteSize, double halfSpread, double skewPerUnit,
                         long minRequoteIntervalNanos, double minMove,
                         TickSizeSchedule tickSchedule) {

        public Config {
            if (quoteSize <= 0 || !(halfSpread > 0) || !(skewPerUnit >= 0)
                    || minRequoteIntervalNanos < 0 || !(minMove >= 0)) {
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
         * Suppresses a requote only when the fair price moved less than
         * {@code minMovePrice} and the last quote is younger than
         * {@code minIntervalNanos}; both conditions must hold to suppress.
         *
         * <p>Pitfall: {@code minIntervalNanos = 0} therefore disables
         * conflation (nothing is younger than 0 ns); it does not mean
         * "suppress on move alone". For move-only conflation use
         * {@link #withMinMove}.</p>
         */
        public Config withConflation(long minIntervalNanos, double minMovePrice) {
            return new Config(quoteSize, halfSpread, skewPerUnit, minIntervalNanos, minMovePrice,
                    tickSchedule);
        }

        /**
         * Move-only conflation: requote only when the fair price has moved at
         * least {@code minMovePrice}, regardless of age (the interval gate is
         * set effectively infinite).
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

    private static final int UPDATES = 0;
    private static final int SUPPRESSED = 1;
    private static final int REJECTED = 2;
    private static final int DEFERRED = 3;
    private static final int REPLACES = 4;
    private static final int INVALID = 5;
    private static final int CROSSED_SIDES = 6;
    private static final int CROSSED_BOOKS = 7;

    private final HftOrderGateway gateway;
    private final HftRiskGate riskGate;

    private final long[] quoteSize;
    private final double[] halfSpread;
    private final double[] skewPerUnit;
    private final long[] minRequoteIntervalNanos;
    private final double[] minMove;
    private final TickSizeSchedule[] grids;

    // Per-symbol state, dense-id indexed. NaN = unknown.
    private final double[] lastQuoteMid;
    private final long[] lastQuoteNanos;
    private final double[] bestBid;
    private final double[] bestAsk;
    private final long[] workingBid;   // gateway order id, 0 = none
    private final long[] workingAsk;

    // Diagnostics: single writer (the quoting thread), opaque for readers.
    private final StridedSequences counters = new StridedSequences(8);

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
        this.bestBid = new double[maxSymbols];
        this.bestAsk = new double[maxSymbols];
        this.workingBid = new long[maxSymbols];
        this.workingAsk = new long[maxSymbols];
        java.util.Arrays.fill(lastQuoteMid, Double.NaN);
        java.util.Arrays.fill(bestBid, Double.NaN);
        java.util.Arrays.fill(bestAsk, Double.NaN);
        for (int i = 0; i < maxSymbols; i++) {
            apply(i, defaults);
        }
    }

    /** Per-symbol quoting parameters (cold path; quoting thread or before start). */
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

    /** Trade print: drives quoting only while the symbol has no two-sided BBO. */
    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        if (!Double.isNaN(bestBid[symbolId]) && !Double.isNaN(bestAsk[symbolId])) {
            return;
        }
        requote(symbolId, price, timestampNanos);
    }

    /** Top of book: fair price is the mid; a crossed or locked book pulls both quotes. */
    @Override
    public void onQuote(int symbolId, double bid, double bidSize, double ask, double askSize,
                        long timestampNanos) {
        boolean bidOk = bid > 0 && bid != Double.POSITIVE_INFINITY;
        boolean askOk = ask > 0 && ask != Double.POSITIVE_INFINITY;
        bestBid[symbolId] = bidOk ? bid : Double.NaN;
        bestAsk[symbolId] = askOk ? ask : Double.NaN;
        if (!bidOk || !askOk) {
            counters.incrementOpaque(INVALID);
            return;
        }
        if (bid >= ask) {
            counters.incrementOpaque(CROSSED_BOOKS);
            pullQuotes(symbolId, timestampNanos);
            return;
        }
        requote(symbolId, 0.5 * (bid + ask), timestampNanos);
    }

    private void requote(int symbolId, double fair, long timestampNanos) {
        if (!(fair > 0) || fair == Double.POSITIVE_INFINITY) {
            counters.incrementOpaque(INVALID);
            return;
        }
        double lastMid = lastQuoteMid[symbolId];
        if (!Double.isNaN(lastMid)
                && Math.abs(fair - lastMid) < minMove[symbolId]
                && timestampNanos - lastQuoteNanos[symbolId] < minRequoteIntervalNanos[symbolId]) {
            counters.incrementOpaque(SUPPRESSED);
            return;
        }

        long position = riskGate.position(symbolId);
        double skew = -position * skewPerUnit[symbolId];
        double bid = fair - halfSpread[symbolId] + skew;
        double ask = fair + halfSpread[symbolId] + skew;

        // Grid snap toward passivity. Clamped rounding: a heavily skewed bid
        // can fall below the grid's first band, and a throw here would reach
        // the bus consumer. Bad prices die at the risk gate.
        TickSizeSchedule grid = grids[symbolId];
        if (grid != null) {
            bid = grid.roundDownClamped(bid);
            ask = grid.roundUpClamped(ask);
        }

        long size = quoteSize[symbolId];
        double bb = bestBid[symbolId];
        double ba = bestAsk[symbolId];
        if (!Double.isNaN(ba) && bid >= ba) {
            counters.incrementOpaque(CROSSED_SIDES);
            workingBid[symbolId] = pull(workingBid[symbolId], timestampNanos);
        } else {
            workingBid[symbolId] = work(workingBid[symbolId], symbolId, Side.BUY, size, bid,
                    timestampNanos);
        }
        if (!Double.isNaN(bb) && ask <= bb) {
            counters.incrementOpaque(CROSSED_SIDES);
            workingAsk[symbolId] = pull(workingAsk[symbolId], timestampNanos);
        } else {
            workingAsk[symbolId] = work(workingAsk[symbolId], symbolId, Side.SELL, size, ask,
                    timestampNanos);
        }
        counters.incrementOpaque(UPDATES);
        lastQuoteMid[symbolId] = fair;
        lastQuoteNanos[symbolId] = timestampNanos;
    }

    /** Brings one side to (price, size); returns the id now working on that side (0 = none). */
    private long work(long working, int symbolId, Side side, long size, double price,
                      long timestampNanos) {
        if (working != 0) {
            int st = gateway.state(working);
            if (OrderStateMachine.isLive(st)) {
                if (st == OrderStateMachine.PENDING_NEW || gateway.isPendingChange(working)) {
                    counters.incrementOpaque(DEFERRED);
                    return working;
                }
                if (gateway.orderPrice(working) == price
                        && gateway.leavesQuantity(working) == size) {
                    return working;
                }
                int r = gateway.replace(working, gateway.filledQuantity(working) + size, price,
                        timestampNanos);
                if (r == 0) {
                    counters.incrementOpaque(REPLACES);
                    return working;
                }
                if (r != HftOrderGateway.REPLACE_NOT_LIVE) {
                    counters.incrementOpaque(REJECTED);
                    gateway.cancel(working, timestampNanos);
                    return working;
                }
            }
        }
        long id = gateway.submit(symbolId, side, size, price, timestampNanos);
        if (id > 0) {
            return id;
        }
        counters.incrementOpaque(REJECTED);
        return 0;
    }

    private long pull(long working, long timestampNanos) {
        if (working != 0 && gateway.isLive(working)) {
            gateway.cancel(working, timestampNanos);
            return working;
        }
        return 0;
    }

    /** Cancels both working quotes on a symbol (quoting thread). */
    public void pullQuotes(int symbolId, long timestampNanos) {
        workingBid[symbolId] = pull(workingBid[symbolId], timestampNanos);
        workingAsk[symbolId] = pull(workingAsk[symbolId], timestampNanos);
        lastQuoteMid[symbolId] = Double.NaN;
    }

    /** Gateway id of the working bid (0 if none). */
    public long workingBidId(int symbolId) {
        return workingBid[symbolId];
    }

    /** Gateway id of the working offer (0 if none). */
    public long workingAskId(int symbolId) {
        return workingAsk[symbolId];
    }

    /** Quote updates acted on (each touches up to two orders). */
    public long quoteUpdates() {
        return counters.getOpaque(UPDATES);
    }

    /** Updates suppressed by conflation. */
    public long suppressedUpdates() {
        return counters.getOpaque(SUPPRESSED);
    }

    /** Sides refused by the risk gate, the throttle or a full ring. */
    public long rejectedSides() {
        return counters.getOpaque(REJECTED);
    }

    /** Side updates held because the previous order awaited an ack or a cancel/replace answer. */
    public long deferredSides() {
        return counters.getOpaque(DEFERRED);
    }

    /** Cancel/replace requests sent. */
    public long replaces() {
        return counters.getOpaque(REPLACES);
    }

    /** Inputs ignored for a NaN, non-positive or infinite price. */
    public long invalidInputs() {
        return counters.getOpaque(INVALID);
    }

    /** Sides not quoted because they would have crossed the BBO. */
    public long crossedSides() {
        return counters.getOpaque(CROSSED_SIDES);
    }

    /** Crossed or locked input books (quotes pulled). */
    public long crossedBooks() {
        return counters.getOpaque(CROSSED_BOOKS);
    }
}

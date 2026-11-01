package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Zero-allocation pre-trade risk gate for the fast lane, the counterpart of
 * {@link com.quantfinlib.risk.PreTradeLimitChecker}. All state is primitive
 * arrays indexed by dense symbol id; {@link #check} performs no allocation,
 * no hashing and no string formatting, and returns an int reason code.
 *
 * <h2>Checks, in order</h2>
 * <ol>
 *   <li>kill switch ({@link #REJECT_KILLED}) and per-symbol halt;</li>
 *   <li>price validity: the limit price must be finite and strictly
 *       positive ({@link #REJECT_PRICE}); NaN, zero, negative and infinite
 *       prices never reach the wire;</li>
 *   <li>order quantity and order notional;</li>
 *   <li>daily caps: order count, cumulative accepted notional and realised
 *       loss (the loss figure is pushed in with {@link #updateDailyPnl});</li>
 *   <li>position, including open-order exposure: the worst case if every
 *       working order on the same side fills,
 *       {@code |position + sign * (openSameSide + qty)|}, must stay within
 *       the cap unless the order reduces the absolute position without
 *       flipping its sign;</li>
 *   <li>price collar against the reference price;</li>
 *   <li>duplicate order: same symbol, side, quantity and price as the last
 *       accepted order within {@link #duplicateWindowNanos}.</li>
 * </ol>
 * <p>These map to the pre-trade controls of MiFID II RTS 6 Art. 15 (price
 * collars, maximum order value and volume, maximum messages) and SEC Rule
 * 15c3-5(c)(1) (credit thresholds, erroneous and duplicative orders).</p>
 *
 * <h2>Open-order exposure</h2>
 * <p>{@link HftOrderGateway} reserves each accepted order's quantity with
 * {@link #reserve} and releases it on fill, cancel or reject, so the
 * position check sees resting orders, not just fills. On a fill the
 * position is applied before the reservation is released, so a concurrent
 * check can briefly overstate exposure but never understate it.</p>
 *
 * <h2>Threading</h2>
 * <p>{@link #check} runs on the trading thread; {@link #onFill} and
 * {@link #release} on the venue-report thread; {@link #halt}, {@link #kill}
 * and the limit setters from any thread. Cross-thread element access uses
 * {@link VarHandle} acquire/release (plain loads and stores on x86), and
 * position and exposure updates use atomic adds. Limits are read with
 * acquire semantics on every check, so they can be tightened intraday from
 * an operations thread (RTS 6 Art. 15(2)). The daily counters and the
 * duplicate-order memory are written only by the thread that calls
 * {@link #recordAccepted}, normally the single trading thread.</p>
 */
public final class HftRiskGate {

    public static final int OK = 0;
    public static final int REJECT_QUANTITY = 1;
    public static final int REJECT_NOTIONAL = 2;
    public static final int REJECT_POSITION = 3;
    public static final int REJECT_PRICE_COLLAR = 4;
    public static final int REJECT_HALTED = 5;
    public static final int REJECT_KILLED = 6;
    /** Price NaN, infinite, zero or negative. */
    public static final int REJECT_PRICE = 7;
    public static final int REJECT_DAILY_NOTIONAL = 8;
    public static final int REJECT_DAILY_ORDERS = 9;
    public static final int REJECT_DAILY_LOSS = 10;
    public static final int REJECT_DUPLICATE = 11;
    /** Message-rate throttle refused the order (set by the gateway). */
    public static final int REJECT_THROTTLED = 12;
    /** The gateway's venue thread is dead or the gateway is stopped. */
    public static final int REJECT_GATEWAY_DOWN = 13;
    /** The gateway's order-state table has no free slot. */
    public static final int REJECT_TOO_MANY_OPEN = 14;
    /** A collar is configured, the symbol has no reference price and references are required. */
    public static final int REJECT_NO_REFERENCE = 15;
    private static final int REASONS = 16;

    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle DOUBLES =
            MethodHandles.arrayElementVarHandle(double[].class);
    private static final VarHandle BOOLEANS =
            MethodHandles.arrayElementVarHandle(boolean[].class);
    private static final VarHandle MAX_ORDER_QTY;
    private static final VarHandle MAX_ORDER_NOTIONAL;
    private static final VarHandle MAX_POSITION;
    private static final VarHandle COLLAR_PCT;
    private static final VarHandle MAX_DAILY_NOTIONAL;
    private static final VarHandle MAX_DAILY_ORDERS;
    private static final VarHandle MAX_DAILY_LOSS;
    private static final VarHandle DUP_WINDOW;
    private static final VarHandle REQUIRE_REF;
    private static final VarHandle DAILY_NOTIONAL;
    private static final VarHandle DAILY_ORDERS;
    private static final VarHandle DAILY_PNL;
    private static final VarHandle KILL_EPOCH;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            MAX_ORDER_QTY = l.findVarHandle(HftRiskGate.class, "maxOrderQuantity", long.class);
            MAX_ORDER_NOTIONAL = l.findVarHandle(HftRiskGate.class, "maxOrderNotional", double.class);
            MAX_POSITION = l.findVarHandle(HftRiskGate.class, "maxPositionQuantity", long.class);
            COLLAR_PCT = l.findVarHandle(HftRiskGate.class, "priceCollarPct", double.class);
            MAX_DAILY_NOTIONAL = l.findVarHandle(HftRiskGate.class, "maxDailyNotional", double.class);
            MAX_DAILY_ORDERS = l.findVarHandle(HftRiskGate.class, "maxDailyOrders", long.class);
            MAX_DAILY_LOSS = l.findVarHandle(HftRiskGate.class, "maxDailyLoss", double.class);
            DUP_WINDOW = l.findVarHandle(HftRiskGate.class, "duplicateWindowNanos", long.class);
            REQUIRE_REF = l.findVarHandle(HftRiskGate.class, "requireReference", boolean.class);
            DAILY_NOTIONAL = l.findVarHandle(HftRiskGate.class, "dailyNotional", double.class);
            DAILY_ORDERS = l.findVarHandle(HftRiskGate.class, "dailyOrders", long.class);
            DAILY_PNL = l.findVarHandle(HftRiskGate.class, "dailyPnl", double.class);
            KILL_EPOCH = l.findVarHandle(HftRiskGate.class, "killEpoch", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long[] positions;          // signed, by symbol id
    private final long[] openBuy;            // reserved working buy quantity
    private final long[] openSell;           // reserved working sell quantity
    private final double[] referencePrices;  // NaN = unset
    private final boolean[] halted;
    private final long[] rejections = new long[REASONS];
    private final boolean[] killed = new boolean[1];

    // Duplicate-order memory: last accepted order per symbol (trading thread).
    private final byte[] lastSide;
    private final long[] lastQty;
    private final double[] lastPrice;
    private final long[] lastNanos;

    // Limits: accessed only through their VarHandles (acquire/release).
    @SuppressWarnings("unused") private long maxOrderQuantity = Long.MAX_VALUE;
    @SuppressWarnings("unused") private double maxOrderNotional = Double.MAX_VALUE;
    @SuppressWarnings("unused") private long maxPositionQuantity = Long.MAX_VALUE;
    @SuppressWarnings("unused") private double priceCollarPct = Double.MAX_VALUE;
    @SuppressWarnings("unused") private double maxDailyNotional = Double.MAX_VALUE;
    @SuppressWarnings("unused") private long maxDailyOrders = Long.MAX_VALUE;
    @SuppressWarnings("unused") private double maxDailyLoss = Double.MAX_VALUE;
    @SuppressWarnings("unused") private long duplicateWindowNanos;
    @SuppressWarnings("unused") private boolean requireReference;
    // Daily state.
    @SuppressWarnings("unused") private double dailyNotional;
    @SuppressWarnings("unused") private long dailyOrders;
    @SuppressWarnings("unused") private double dailyPnl;
    @SuppressWarnings("unused") private long killEpoch;

    public HftRiskGate(int maxSymbols) {
        if (maxSymbols <= 0) {
            throw new IllegalArgumentException("maxSymbols must be > 0");
        }
        this.positions = new long[maxSymbols];
        this.openBuy = new long[maxSymbols];
        this.openSell = new long[maxSymbols];
        this.referencePrices = new double[maxSymbols];
        this.halted = new boolean[maxSymbols];
        this.lastSide = new byte[maxSymbols];
        this.lastQty = new long[maxSymbols];
        this.lastPrice = new double[maxSymbols];
        this.lastNanos = new long[maxSymbols];
        java.util.Arrays.fill(referencePrices, Double.NaN);
        java.util.Arrays.fill(lastPrice, Double.NaN);
    }

    // ------------------------------------------------------------------
    // Configuration: callable at any time, from any thread
    // ------------------------------------------------------------------

    public HftRiskGate maxOrderQuantity(long qty) {
        MAX_ORDER_QTY.setRelease(this, qty);
        return this;
    }

    public HftRiskGate maxOrderNotional(double notional) {
        MAX_ORDER_NOTIONAL.setRelease(this, notional);
        return this;
    }

    public HftRiskGate maxPositionQuantity(long qty) {
        MAX_POSITION.setRelease(this, qty);
        return this;
    }

    /** Fat-finger guard versus the reference price (0.02 = 2 %). */
    public HftRiskGate priceCollarPct(double pct) {
        COLLAR_PCT.setRelease(this, pct);
        return this;
    }

    /** Cap on cumulative accepted notional since the last {@link #resetDaily}. */
    public HftRiskGate maxDailyNotional(double notional) {
        MAX_DAILY_NOTIONAL.setRelease(this, notional);
        return this;
    }

    /** Cap on accepted orders since the last {@link #resetDaily}. */
    public HftRiskGate maxDailyOrders(long orders) {
        MAX_DAILY_ORDERS.setRelease(this, orders);
        return this;
    }

    /**
     * Loss limit, as a positive amount: new orders are rejected once the
     * pushed daily P&amp;L falls below {@code -loss}.
     */
    public HftRiskGate maxDailyLoss(double loss) {
        MAX_DAILY_LOSS.setRelease(this, loss);
        return this;
    }

    /**
     * Rejects an order identical (symbol, side, quantity, price) to the
     * last accepted order on that symbol within {@code nanos}; 0 disables.
     * Only the timestamped {@link #check(int, Side, long, double, long)}
     * applies it.
     */
    public HftRiskGate duplicateWindowNanos(long nanos) {
        DUP_WINDOW.setRelease(this, nanos);
        return this;
    }

    /**
     * When true and a collar is configured, a symbol without a reference
     * price is rejected with {@link #REJECT_NO_REFERENCE} instead of
     * skipping the collar (fail closed). Default false.
     */
    public HftRiskGate requireReferencePrice(boolean require) {
        REQUIRE_REF.setRelease(this, require);
        return this;
    }

    /** Pushes the current daily P&amp;L (negative = loss) for the loss limit. */
    public void updateDailyPnl(double pnl) {
        DAILY_PNL.setRelease(this, pnl);
    }

    /** Clears the daily order count, notional and P&amp;L (start of day). */
    public void resetDaily() {
        DAILY_NOTIONAL.setRelease(this, 0.0);
        DAILY_ORDERS.setRelease(this, 0L);
        DAILY_PNL.setRelease(this, 0.0);
    }

    /** Halts or unhalts a symbol, from any thread. */
    public void halt(int symbolId, boolean isHalted) {
        BOOLEANS.setRelease(halted, symbolId, isHalted);
    }

    /**
     * Gate-wide kill switch: every check rejects with {@link #REJECT_KILLED}
     * until cleared. Engaging it also bumps {@link #killEpoch()}, which every
     * {@link HftOrderGateway} on this gate watches: on a change the gateway
     * sends a cancel-all for every symbol to its venue listeners. Callable
     * from any thread.
     */
    public void kill(boolean isKilled) {
        BOOLEANS.setRelease(killed, 0, isKilled);
        if (isKilled) {
            KILL_EPOCH.getAndAdd(this, 1L);
        }
    }

    public boolean isKilled() {
        return (boolean) BOOLEANS.getAcquire(killed, 0);
    }

    /** Incremented on every {@code kill(true)}. */
    public long killEpoch() {
        return (long) KILL_EPOCH.getAcquire(this);
    }

    public double referencePrice(int symbolId) {
        return (double) DOUBLES.getAcquire(referencePrices, symbolId);
    }

    public int symbolCapacity() {
        return positions.length;
    }

    /** Updates the collar reference, normally from an independent market-data thread. */
    public void setReferencePrice(int symbolId, double price) {
        DOUBLES.setRelease(referencePrices, symbolId, price);
    }

    // ------------------------------------------------------------------
    // Hot path
    // ------------------------------------------------------------------

    /**
     * Validates one order without the duplicate check. Returns {@link #OK}
     * or a rejection reason code. Pure apart from rejection counters.
     */
    public int check(int symbolId, Side side, long quantity, double price) {
        return evaluate(symbolId, side, quantity, quantity, price, Long.MIN_VALUE, true);
    }

    /** As {@link #check(int, Side, long, double)}, plus the duplicate-order check at {@code timestampNanos}. */
    public int check(int symbolId, Side side, long quantity, double price, long timestampNanos) {
        return evaluate(symbolId, side, quantity, quantity, price, timestampNanos, true);
    }

    /**
     * Validates a cancel/replace: {@code newQuantity} is the new total
     * order size (checked against size and notional limits) and
     * {@code extraExposure} the additional working quantity it would add
     * beyond what the order already has reserved (checked against the
     * position limit; 0 for a size-down).
     */
    public int checkReplace(int symbolId, Side side, long newQuantity, long extraExposure,
                            double price) {
        return evaluate(symbolId, side, newQuantity, Math.max(0, extraExposure), price,
                Long.MIN_VALUE, false);
    }

    private int evaluate(int symbolId, Side side, long quantity, long exposure, double price,
                         long timestampNanos, boolean newOrder) {
        if ((boolean) BOOLEANS.getAcquire(killed, 0)) {
            return reject(REJECT_KILLED);
        }
        if ((boolean) BOOLEANS.getAcquire(halted, symbolId)) {
            return reject(REJECT_HALTED);
        }
        // One branch: false for NaN, zero, negatives; then +Inf.
        if (!(price > 0.0) || price == Double.POSITIVE_INFINITY) {
            return reject(REJECT_PRICE);
        }
        if (quantity <= 0 || quantity > (long) MAX_ORDER_QTY.getAcquire(this)) {
            return reject(REJECT_QUANTITY);
        }
        double notional = quantity * price;
        if (notional > (double) MAX_ORDER_NOTIONAL.getAcquire(this)) {
            return reject(REJECT_NOTIONAL);
        }
        if (newOrder
                && (long) DAILY_ORDERS.getAcquire(this) >= (long) MAX_DAILY_ORDERS.getAcquire(this)) {
            return reject(REJECT_DAILY_ORDERS);
        }
        if ((double) DAILY_NOTIONAL.getAcquire(this) + exposure * price
                > (double) MAX_DAILY_NOTIONAL.getAcquire(this)) {
            return reject(REJECT_DAILY_NOTIONAL);
        }
        if (-(double) DAILY_PNL.getAcquire(this) >= (double) MAX_DAILY_LOSS.getAcquire(this)) {
            return reject(REJECT_DAILY_LOSS);
        }
        long sign = side.sign();
        long current = (long) LONGS.getAcquire(positions, symbolId);
        long openSame = (long) LONGS.getAcquire(side == Side.BUY ? openBuy : openSell, symbolId);
        long base = current + sign * openSame;
        long projected = base + sign * exposure;
        // A projection over the cap passes only if it shrinks the absolute
        // worst-case position without flipping its sign: refusing the order
        // that de-risks an over-cap book is wrong, but a "reduce" that
        // flips +1600 through zero to -1400 is a new over-cap short.
        long cap = (long) MAX_POSITION.getAcquire(this);
        if (Math.abs(projected) > cap
                && (Math.abs(projected) >= Math.abs(base) || (projected ^ base) < 0)) {
            return reject(REJECT_POSITION);
        }
        double collar = (double) COLLAR_PCT.getAcquire(this);
        if (collar != Double.MAX_VALUE) {
            double ref = (double) DOUBLES.getAcquire(referencePrices, symbolId);
            if (!Double.isNaN(ref)) {
                if (Math.abs(price - ref) > ref * collar) {
                    return reject(REJECT_PRICE_COLLAR);
                }
            } else if ((boolean) REQUIRE_REF.getAcquire(this)) {
                return reject(REJECT_NO_REFERENCE);
            }
        }
        if (timestampNanos != Long.MIN_VALUE) {
            long window = (long) DUP_WINDOW.getAcquire(this);
            if (window > 0
                    && lastQty[symbolId] == quantity
                    && lastPrice[symbolId] == price
                    && lastSide[symbolId] == (byte) sign
                    && timestampNanos - lastNanos[symbolId] < window) {
                return reject(REJECT_DUPLICATE);
            }
        }
        return OK;
    }

    private int reject(int reason) {
        LONGS.getAndAdd(rejections, reason, 1L);
        return reason;
    }

    /** Counts a rejection decided outside the gate (throttle, gateway down). */
    public void countRejection(int reasonCode) {
        reject(reasonCode);
    }

    /**
     * Records an accepted new order: daily order count, daily notional and
     * the duplicate-order memory. Called by the gateway after a successful
     * publish, on the trading thread.
     */
    public void recordAccepted(int symbolId, Side side, long quantity, double price,
                               long timestampNanos) {
        DAILY_ORDERS.setRelease(this, (long) DAILY_ORDERS.getAcquire(this) + 1);
        addDailyNotional(quantity * price);
        lastSide[symbolId] = (byte) side.sign();
        lastQty[symbolId] = quantity;
        lastPrice[symbolId] = price;
        lastNanos[symbolId] = timestampNanos;
    }

    /** Adds notional to the daily total (used for replace upsizes). */
    public void addDailyNotional(double notional) {
        DAILY_NOTIONAL.setRelease(this, (double) DAILY_NOTIONAL.getAcquire(this) + notional);
    }

    /** Reserves working-order exposure (the gateway calls this before publishing). */
    public void reserve(int symbolId, Side side, long quantity) {
        LONGS.getAndAdd(side == Side.BUY ? openBuy : openSell, symbolId, quantity);
    }

    /** Releases working-order exposure on fill, cancel or reject. */
    public void release(int symbolId, Side side, long quantity) {
        LONGS.getAndAdd(side == Side.BUY ? openBuy : openSell, symbolId, -quantity);
    }

    /** Reserved working quantity on one side of a symbol. */
    public long openQuantity(int symbolId, Side side) {
        return (long) LONGS.getAcquire(side == Side.BUY ? openBuy : openSell, symbolId);
    }

    /**
     * Applies a fill to the position book, from any thread (atomic add).
     * When fills come through {@link HftOrderGateway#onFill} the gateway also
     * releases the order's reservation, after this call.
     */
    public void onFill(int symbolId, Side side, long quantity) {
        LONGS.getAndAdd(positions, symbolId, side.sign() * quantity);
    }

    // ------------------------------------------------------------------
    // Observability
    // ------------------------------------------------------------------

    public long position(int symbolId) {
        return (long) LONGS.getAcquire(positions, symbolId);
    }

    public long rejectionCount(int reasonCode) {
        return (long) LONGS.getAcquire(rejections, reasonCode);
    }

    public long dailyOrders() {
        return (long) DAILY_ORDERS.getAcquire(this);
    }

    public double dailyNotional() {
        return (double) DAILY_NOTIONAL.getAcquire(this);
    }

    public long maxPositionQuantity() {
        return (long) MAX_POSITION.getAcquire(this);
    }

    public static String reasonName(int code) {
        return switch (code) {
            case OK -> "OK";
            case REJECT_QUANTITY -> "MAX_ORDER_QTY";
            case REJECT_NOTIONAL -> "MAX_NOTIONAL";
            case REJECT_POSITION -> "MAX_POSITION";
            case REJECT_PRICE_COLLAR -> "PRICE_COLLAR";
            case REJECT_HALTED -> "HALTED";
            case REJECT_KILLED -> "KILLED";
            case REJECT_PRICE -> "INVALID_PRICE";
            case REJECT_DAILY_NOTIONAL -> "DAILY_NOTIONAL";
            case REJECT_DAILY_ORDERS -> "DAILY_ORDERS";
            case REJECT_DAILY_LOSS -> "DAILY_LOSS";
            case REJECT_DUPLICATE -> "DUPLICATE";
            case REJECT_THROTTLED -> "THROTTLED";
            case REJECT_GATEWAY_DOWN -> "GATEWAY_DOWN";
            case REJECT_TOO_MANY_OPEN -> "TOO_MANY_OPEN_ORDERS";
            case REJECT_NO_REFERENCE -> "NO_REFERENCE_PRICE";
            default -> "UNKNOWN(" + code + ")";
        };
    }
}

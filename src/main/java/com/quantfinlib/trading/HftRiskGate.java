package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Zero-allocation pre-trade risk gate for the HFT order path — the fast-lane
 * counterpart of {@link com.quantfinlib.risk.PreTradeLimitChecker}. All state
 * is primitive arrays indexed by dense symbol id; {@link #check} performs no
 * allocation, no hashing and no string formatting, and returns an int reason
 * code. Rejection counts are kept per reason for observability.
 *
 * <h2>Threading</h2>
 * <p>The production wiring is inherently multi-threaded: {@link #check} runs
 * on the trading/quoting thread (often the bus consumer), {@link #onFill} on
 * the venue-ack thread, {@link #halt} from ops/dashboards, and
 * {@link #setReferencePrice} from the market-data thread. Cross-thread
 * element access therefore uses {@link VarHandle} acquire/release ordering:
 * on x86 an acquire load is a plain load and a release store a plain store,
 * so the ≈1 ns/check cost is unchanged (verified by re-running
 * {@code HftOrderBenchmark} after this change), while readers are guaranteed
 * fresh, untorn values — a plain {@code long[]} would let the JIT serve a
 * stale position to the quoter's skew and the position limit forever.
 * {@code onFill} uses an atomic add, so multiple fill sources are safe too.
 * Limit <em>configuration</em> remains setup-time single-threaded.</p>
 */
public final class HftRiskGate {

    public static final int OK = 0;
    public static final int REJECT_QUANTITY = 1;
    public static final int REJECT_NOTIONAL = 2;
    public static final int REJECT_POSITION = 3;
    public static final int REJECT_PRICE_COLLAR = 4;
    public static final int REJECT_HALTED = 5;
    private static final int REASONS = 6;

    // Per-element VarHandles: cross-thread visibility without object
    // wrappers or boxing — the arrays stay primitive and cache-friendly.
    private static final VarHandle LONGS =
            MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle DOUBLES =
            MethodHandles.arrayElementVarHandle(double[].class);
    private static final VarHandle BOOLEANS =
            MethodHandles.arrayElementVarHandle(boolean[].class);

    private final long[] positions;          // signed, by symbol id
    private final double[] referencePrices;  // NaN = no collar check
    private final boolean[] halted;
    private final long[] rejections = new long[REASONS];
    private long maxOrderQuantity = Long.MAX_VALUE;
    private double maxOrderNotional = Double.MAX_VALUE;
    private long maxPositionQuantity = Long.MAX_VALUE;
    private double priceCollarPct = Double.MAX_VALUE;

    public HftRiskGate(int maxSymbols) {
        this.positions = new long[maxSymbols];
        this.referencePrices = new double[maxSymbols];
        this.halted = new boolean[maxSymbols];
        java.util.Arrays.fill(referencePrices, Double.NaN);
    }

    // ------------------------------------------------------------------
    // Configuration (cold path)
    // ------------------------------------------------------------------

    public HftRiskGate maxOrderQuantity(long qty) {
        this.maxOrderQuantity = qty;
        return this;
    }

    public HftRiskGate maxOrderNotional(double notional) {
        this.maxOrderNotional = notional;
        return this;
    }

    public HftRiskGate maxPositionQuantity(long qty) {
        this.maxPositionQuantity = qty;
        return this;
    }

    /** Fat-finger guard versus the reference price (0.02 = 2%). */
    public HftRiskGate priceCollarPct(double pct) {
        this.priceCollarPct = pct;
        return this;
    }

    /** Halts/unhalts a symbol — callable from any thread (ops, dashboards). */
    public void halt(int symbolId, boolean isHalted) {
        BOOLEANS.setRelease(halted, symbolId, isHalted);
    }

    /** Updates the collar reference (e.g. from the market data bus). */
    public void setReferencePrice(int symbolId, double price) {
        DOUBLES.setRelease(referencePrices, symbolId, price);
    }

    // ------------------------------------------------------------------
    // Hot path
    // ------------------------------------------------------------------

    /**
     * Validates one order. Returns {@link #OK} or a rejection reason code.
     * Zero allocation; acquire loads only (free on x86).
     */
    public int check(int symbolId, Side side, long quantity, double price) {
        if ((boolean) BOOLEANS.getAcquire(halted, symbolId)) {
            bumpRejection(REJECT_HALTED);
            return REJECT_HALTED;
        }
        if (quantity <= 0 || quantity > maxOrderQuantity) {
            bumpRejection(REJECT_QUANTITY);
            return REJECT_QUANTITY;
        }
        if (quantity * price > maxOrderNotional) {
            bumpRejection(REJECT_NOTIONAL);
            return REJECT_NOTIONAL;
        }
        long newPosition = (long) LONGS.getAcquire(positions, symbolId)
                + side.sign() * quantity;
        if (Math.abs(newPosition) > maxPositionQuantity) {
            bumpRejection(REJECT_POSITION);
            return REJECT_POSITION;
        }
        double ref = (double) DOUBLES.getAcquire(referencePrices, symbolId);
        if (ref == ref && priceCollarPct != Double.MAX_VALUE) {   // NaN-safe
            double deviation = Math.abs(price - ref);
            if (deviation > ref * priceCollarPct) {
                bumpRejection(REJECT_PRICE_COLLAR);
                return REJECT_PRICE_COLLAR;
            }
        }
        return OK;
    }

    /**
     * Applies a fill to the position book — callable from the venue-ack
     * thread (atomic add: concurrent fill sources cannot lose updates).
     */
    public void onFill(int symbolId, Side side, long quantity) {
        LONGS.getAndAdd(positions, symbolId, side.sign() * quantity);
    }

    // ------------------------------------------------------------------
    // Observability
    // ------------------------------------------------------------------

    /** Live position — readable from any thread (quoter skew, hedger, dashboards). */
    public long position(int symbolId) {
        return (long) LONGS.getAcquire(positions, symbolId);
    }

    public long rejectionCount(int reasonCode) {
        return (long) LONGS.getAcquire(rejections, reasonCode);
    }

    /** Single-writer counter (the checking thread); released for readers. */
    private void bumpRejection(int reason) {
        LONGS.setRelease(rejections, reason, (long) LONGS.get(rejections, reason) + 1);
    }

    public static String reasonName(int code) {
        return switch (code) {
            case OK -> "OK";
            case REJECT_QUANTITY -> "MAX_ORDER_QTY";
            case REJECT_NOTIONAL -> "MAX_NOTIONAL";
            case REJECT_POSITION -> "MAX_POSITION";
            case REJECT_PRICE_COLLAR -> "PRICE_COLLAR";
            case REJECT_HALTED -> "HALTED";
            default -> "UNKNOWN(" + code + ")";
        };
    }
}

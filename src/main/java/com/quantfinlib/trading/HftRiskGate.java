package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

/**
 * Zero-allocation pre-trade risk gate for the HFT order path — the fast-lane
 * counterpart of {@link com.quantfinlib.risk.PreTradeLimitChecker}. All state
 * is primitive arrays indexed by dense symbol id; {@link #check} performs no
 * allocation, no hashing and no string formatting, and returns an int reason
 * code. Rejection counts are kept per reason for observability.
 *
 * <p>Single-writer semantics: configure at setup; call {@code check}/{@code
 * onFill} from the trading thread and {@code setReferencePrice} from the
 * market-data thread (benign data race on a single double, as with the last
 * price cache).</p>
 */
public final class HftRiskGate {

    public static final int OK = 0;
    public static final int REJECT_QUANTITY = 1;
    public static final int REJECT_NOTIONAL = 2;
    public static final int REJECT_POSITION = 3;
    public static final int REJECT_PRICE_COLLAR = 4;
    public static final int REJECT_HALTED = 5;
    private static final int REASONS = 6;

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

    public void halt(int symbolId, boolean isHalted) {
        halted[symbolId] = isHalted;
    }

    /** Updates the collar reference (e.g. from the market data bus). */
    public void setReferencePrice(int symbolId, double price) {
        referencePrices[symbolId] = price;
    }

    // ------------------------------------------------------------------
    // Hot path
    // ------------------------------------------------------------------

    /**
     * Validates one order. Returns {@link #OK} or a rejection reason code.
     * Zero allocation.
     */
    public int check(int symbolId, Side side, long quantity, double price) {
        if (halted[symbolId]) {
            rejections[REJECT_HALTED]++;
            return REJECT_HALTED;
        }
        if (quantity <= 0 || quantity > maxOrderQuantity) {
            rejections[REJECT_QUANTITY]++;
            return REJECT_QUANTITY;
        }
        if (quantity * price > maxOrderNotional) {
            rejections[REJECT_NOTIONAL]++;
            return REJECT_NOTIONAL;
        }
        long newPosition = positions[symbolId] + side.sign() * quantity;
        if (Math.abs(newPosition) > maxPositionQuantity) {
            rejections[REJECT_POSITION]++;
            return REJECT_POSITION;
        }
        double ref = referencePrices[symbolId];
        if (ref == ref && priceCollarPct != Double.MAX_VALUE) {   // NaN-safe
            double deviation = Math.abs(price - ref);
            if (deviation > ref * priceCollarPct) {
                rejections[REJECT_PRICE_COLLAR]++;
                return REJECT_PRICE_COLLAR;
            }
        }
        return OK;
    }

    /** Applies a fill to the position book (call from the venue-ack handler). */
    public void onFill(int symbolId, Side side, long quantity) {
        positions[symbolId] += side.sign() * quantity;
    }

    // ------------------------------------------------------------------
    // Observability
    // ------------------------------------------------------------------

    public long position(int symbolId) {
        return positions[symbolId];
    }

    public long rejectionCount(int reasonCode) {
        return rejections[reasonCode];
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

package com.quantfinlib.trading;

import com.quantfinlib.core.Side;

/**
 * In-process venue for tests, benchmarks and paper runs: register it as an
 * {@link OrderListener} on an {@link HftOrderGateway} and it answers every
 * message on the venue thread through the gateway's report methods.
 *
 * <ul>
 *   <li>new order: acknowledged, then filled for whatever the
 *       {@link FillPolicy} returns (0 by default); an IOC remainder is
 *       cancelled;</li>
 *   <li>cancel: confirmed if the order is still live;</li>
 *   <li>replace: confirmed with the requested terms;</li>
 *   <li>cancel-all: every live order it has acknowledged on that symbol
 *       (or on all symbols) is cancelled.</li>
 * </ul>
 *
 * <p>No allocation per message; the live-order memory is a fixed ring of
 * the most recent {@code capacity} acknowledged ids.</p>
 */
public final class LoopbackVenue implements OrderListener {

    /** Quantity to fill on arrival of a new order (0 = rest unfilled). */
    @FunctionalInterface
    public interface FillPolicy {
        long fillQuantity(long orderId, int symbolId, Side side, long quantity, double price,
                          int flags);
    }

    /** Never fills: orders rest until cancelled. */
    public static final FillPolicy NO_FILLS = (id, sym, side, qty, px, flags) -> 0;
    /** Fills every order in full on arrival. */
    public static final FillPolicy FILL_ALL = (id, sym, side, qty, px, flags) -> qty;

    private final HftOrderGateway gateway;
    private final FillPolicy fills;
    private final long[] ids;
    private final int[] symbols;
    private final int mask;
    private long count;

    public LoopbackVenue(HftOrderGateway gateway, FillPolicy fills, int capacity) {
        this.gateway = gateway;
        this.fills = fills;
        int cap = Integer.highestOneBit(Math.max(2, capacity - 1)) * 2;
        this.ids = new long[cap];
        this.symbols = new int[cap];
        this.mask = cap - 1;
    }

    /** Never fills; remembers up to 64K orders for cancel-all. */
    public LoopbackVenue(HftOrderGateway gateway) {
        this(gateway, NO_FILLS, 1 << 16);
    }

    @Override
    public void onOrder(long orderId, int symbolId, Side side, long quantity, double price,
                        long timestampNanos) {
        onNewOrder(orderId, symbolId, side, quantity, price, FLAG_NONE, timestampNanos);
    }

    @Override
    public void onNewOrder(long orderId, int symbolId, Side side, long quantity, double price,
                           int flags, long timestampNanos) {
        gateway.onAck(orderId, timestampNanos);
        int i = (int) (count++ & mask);
        ids[i] = orderId;
        symbols[i] = symbolId;
        long q = Math.min(quantity, Math.max(0, fills.fillQuantity(orderId, symbolId, side,
                quantity, price, flags)));
        if (q > 0) {
            gateway.onFill(orderId, q, price, timestampNanos);
        }
        if ((flags & FLAG_IOC) != 0 && q < quantity) {
            gateway.onCanceled(orderId, timestampNanos);
        }
    }

    @Override
    public void onCancel(long orderId, int symbolId, long timestampNanos) {
        if (gateway.isLive(orderId)) {
            gateway.onCanceled(orderId, timestampNanos);
        } else {
            gateway.onCancelReject(orderId, timestampNanos);
        }
    }

    @Override
    public void onReplace(long orderId, int symbolId, Side side, long newQuantity,
                          double newPrice, long timestampNanos) {
        if (gateway.isLive(orderId)) {
            gateway.onReplaced(orderId, newQuantity, newPrice, timestampNanos);
        } else {
            gateway.onCancelReject(orderId, timestampNanos);
        }
    }

    @Override
    public void onCancelAll(int symbolId, long timestampNanos) {
        long n = Math.min(count, ids.length);
        for (long k = 0; k < n; k++) {
            int i = (int) ((count - 1 - k) & mask);
            if ((symbolId == ALL_SYMBOLS || symbols[i] == symbolId) && gateway.isLive(ids[i])) {
                gateway.onCanceled(ids[i], timestampNanos);
            }
        }
    }
}

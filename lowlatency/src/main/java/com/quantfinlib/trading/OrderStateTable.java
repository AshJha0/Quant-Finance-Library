package com.quantfinlib.trading;

import com.quantfinlib.core.Side;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Fixed-capacity, allocation-free table of the fast lane's working orders,
 * keyed by gateway order id ({@code slot = id & mask}). Gateway ids are
 * sequential, so a slot is reused only after {@code capacity} later orders,
 * and only if its previous occupant has reached a terminal state; otherwise
 * the new order is refused ({@link HftRiskGate#REJECT_TOO_MANY_OPEN}).
 *
 * <h2>Threading</h2>
 * <p>Two writers with disjoint phases. The trading thread fills a slot's
 * fields while the slot is empty or terminal and then publishes it with a
 * release store of the state ({@code PENDING_NEW}). After that only the
 * venue-report thread changes the state, quantities and price (acks, fills,
 * cancels, replace confirmations), and the trading thread only reads them
 * with acquire loads. The pending-cancel and pending-replace flags belong
 * to the trading thread, which sets them on request; the report thread
 * clears them on the confirmation. The reserved-exposure column is updated
 * atomically by both.</p>
 */
public final class OrderStateTable {

    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle DOUBLES = MethodHandles.arrayElementVarHandle(double[].class);
    private static final VarHandle BYTES = MethodHandles.arrayElementVarHandle(byte[].class);

    static final byte PENDING_CANCEL = 1;
    static final byte PENDING_REPLACE = 2;
    static final byte ACK_TIMED_OUT = 4;

    final long[] ids;
    final int[] symbols;
    final byte[] sides;            // 0 buy, 1 sell
    final long[] quantities;       // current total order quantity
    final long[] filled;
    final long[] reserved;         // exposure currently reserved in the gate
    final double[] prices;
    final long[] sentNanos;
    final int[] states;
    final byte[] flags;
    private final int mask;

    /** @param requestedCapacity rounded up to a power of two */
    public OrderStateTable(int requestedCapacity) {
        int capacity = Integer.highestOneBit(Math.max(2, requestedCapacity - 1)) * 2;
        this.mask = capacity - 1;
        this.ids = new long[capacity];
        this.symbols = new int[capacity];
        this.sides = new byte[capacity];
        this.quantities = new long[capacity];
        this.filled = new long[capacity];
        this.reserved = new long[capacity];
        this.prices = new double[capacity];
        this.sentNanos = new long[capacity];
        this.states = new int[capacity];
        this.flags = new byte[capacity];
    }

    public int capacity() {
        return mask + 1;
    }

    /** Trading thread: claims the slot for {@code id}; -1 if its occupant is still live. */
    int open(long id, int symbolId, Side side, long quantity, double price, long nanos) {
        int s = (int) (id & mask);
        int st = state(s);
        if (st != OrderStateMachine.EMPTY && !OrderStateMachine.isTerminal(st)) {
            return -1;
        }
        LONGS.setRelease(ids, s, id);
        symbols[s] = symbolId;
        sides[s] = (byte) (side == Side.SELL ? 1 : 0);
        LONGS.setRelease(quantities, s, quantity);
        LONGS.setRelease(filled, s, 0L);
        LONGS.setRelease(reserved, s, quantity);
        DOUBLES.setRelease(prices, s, price);
        sentNanos[s] = nanos;
        BYTES.setRelease(flags, s, (byte) 0);
        INTS.setRelease(states, s, OrderStateMachine.PENDING_NEW);
        return s;
    }

    /** Trading thread: frees a slot claimed by {@link #open} whose publish failed. */
    void abandon(int slot) {
        LONGS.setRelease(reserved, slot, 0L);
        INTS.setRelease(states, slot, OrderStateMachine.EMPTY);
    }

    /** Slot holding {@code id}, or -1 if the id is unknown or its slot was reused. */
    int slotOf(long id) {
        if (id <= 0) {
            return -1;
        }
        int s = (int) (id & mask);
        if ((long) LONGS.getAcquire(ids, s) != id || state(s) == OrderStateMachine.EMPTY) {
            return -1;
        }
        return s;
    }

    int state(int slot) {
        return (int) INTS.getAcquire(states, slot);
    }

    void setState(int slot, int state) {
        INTS.setRelease(states, slot, state);
    }

    byte flags(int slot) {
        return (byte) BYTES.getAcquire(flags, slot);
    }

    void setFlag(int slot, byte flag) {
        BYTES.getAndBitwiseOr(flags, slot, flag);
    }

    void clearFlag(int slot, byte flag) {
        BYTES.getAndBitwiseAnd(flags, slot, (byte) ~flag);
    }

    long quantity(int slot) {
        return (long) LONGS.getAcquire(quantities, slot);
    }

    void setQuantity(int slot, long q) {
        LONGS.setRelease(quantities, slot, q);
    }

    long filled(int slot) {
        return (long) LONGS.getAcquire(filled, slot);
    }

    void addFilled(int slot, long q) {
        LONGS.getAndAdd(filled, slot, q);
    }

    long reserved(int slot) {
        return (long) LONGS.getAcquire(reserved, slot);
    }

    /** Atomically lowers the reservation by up to {@code q}; returns the amount released. */
    long releaseUpTo(int slot, long q) {
        while (true) {
            long r = (long) LONGS.getAcquire(reserved, slot);
            long take = Math.min(r, Math.max(0, q));
            if (take == 0) {
                return 0;
            }
            if (LONGS.compareAndSet(reserved, slot, r, r - take)) {
                return take;
            }
        }
    }

    void addReserved(int slot, long q) {
        LONGS.getAndAdd(reserved, slot, q);
    }

    double price(int slot) {
        return (double) DOUBLES.getAcquire(prices, slot);
    }

    void setPrice(int slot, double p) {
        DOUBLES.setRelease(prices, slot, p);
    }

    Side side(int slot) {
        return sides[slot] == 0 ? Side.BUY : Side.SELL;
    }
}

package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Midpoint-cross dark pool model: hidden resting orders match at the current
 * lit-market midpoint, honoring minimum-execution-quantity constraints (a
 * standard anti-gaming feature). No pre-trade transparency: resting interest
 * is only observable through fills.
 */
public final class DarkPoolSimulator {

    public record Fill(long buyOrderId, long sellOrderId, double price, long quantity) {
    }

    private static final class Resting {
        final long id;
        final long minQty;
        long qty;

        Resting(long id, long qty, long minQty) {
            this.id = id;
            this.qty = qty;
            this.minQty = minQty;
        }
    }

    private final LinkedList<Resting> buys = new LinkedList<>();
    private final LinkedList<Resting> sells = new LinkedList<>();
    private double mid = Double.NaN;
    private long nextId = 1;

    /** Update the lit reference mid; crossing only happens at a valid mid. */
    public void onQuote(double bid, double ask) {
        this.mid = (bid + ask) / 2;
    }

    /**
     * Submits an order: crosses immediately against resting contra interest at
     * the current mid (time priority, skipping resting orders whose
     * min-quantity cannot be honored), then rests the remainder. Returns the
     * fills generated (empty if it fully rested).
     */
    public List<Fill> submit(Side side, long quantity, long minExecutionQty) {
        long id = nextId++;
        List<Fill> fills = new ArrayList<>();
        long remaining = quantity;
        if (!Double.isNaN(mid)) {
            LinkedList<Resting> contra = side == Side.BUY ? sells : buys;
            Iterator<Resting> it = contra.iterator();
            while (it.hasNext() && remaining > 0) {
                Resting r = it.next();
                long fill = Math.min(remaining, r.qty);
                if (fill < minExecutionQty || fill < r.minQty) {
                    continue;   // constraint not satisfiable against this order
                }
                r.qty -= fill;
                remaining -= fill;
                fills.add(side == Side.BUY
                        ? new Fill(id, r.id, mid, fill)
                        : new Fill(r.id, id, mid, fill));
                if (r.qty == 0) {
                    it.remove();
                }
            }
        }
        if (remaining > 0) {
            (side == Side.BUY ? buys : sells).addLast(new Resting(id, remaining, minExecutionQty));
        }
        return fills;
    }

    /** Total hidden resting quantity on a side (for simulation introspection only). */
    public long restingQty(Side side) {
        long total = 0;
        for (Resting r : side == Side.BUY ? buys : sells) {
            total += r.qty;
        }
        return total;
    }
}

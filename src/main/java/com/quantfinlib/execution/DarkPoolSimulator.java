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

    /**
     * Update the lit reference mid. A LOCKED or CROSSED reference (bid >=
     * ask) or a non-positive/non-finite side invalidates the mid: a real
     * midpoint pool is prohibited from executing during a locked/crossed
     * NBBO, so crossing pauses until a valid two-sided market returns —
     * resting interest stays resting.
     */
    public void onQuote(double bid, double ask) {
        this.mid = (bid > 0 && ask > 0 && bid < ask && ask != Double.POSITIVE_INFINITY)
                ? (bid + ask) / 2
                : Double.NaN;
    }

    /**
     * Submits an order: crosses immediately against resting contra interest at
     * the current mid (time priority), then rests the remainder. Returns the
     * fills generated (empty if it fully rested).
     *
     * <p>Minimum-execution-quantity is honored AGGREGATE-first, the common
     * pool semantics: the incoming order's MEQ is checked against the total
     * crossable contra quantity (an order wanting 100 fills against two
     * resting 60s), while each resting order's own MEQ still gates its
     * individual slice.</p>
     */
    public List<Fill> submit(Side side, long quantity, long minExecutionQty) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        if (minExecutionQty < 0) {
            throw new IllegalArgumentException(
                    "minExecutionQty must be >= 0, got " + minExecutionQty);
        }
        long id = nextId++;
        List<Fill> fills = new ArrayList<>();
        long remaining = quantity;
        if (!Double.isNaN(mid)) {
            LinkedList<Resting> contra = side == Side.BUY ? sells : buys;
            // Dry-run the exact time-priority consumption below to learn the
            // aggregate quantity that would actually execute. A static scan
            // against the ORIGINAL remaining overcounts: a later resting
            // order's MEQ can become unsatisfiable once earlier fills shrink
            // the remainder, and crossing on the inflated total would
            // violate the INCOMING order's aggregate MEQ.
            long crossable = 0;
            long dryRemaining = remaining;
            for (Resting r : contra) {
                if (dryRemaining == 0) {
                    break;
                }
                long fill = Math.min(dryRemaining, r.qty);
                if (fill < r.minQty) {
                    continue;
                }
                crossable += fill;
                dryRemaining -= fill;
            }
            if (crossable >= minExecutionQty) {
                Iterator<Resting> it = contra.iterator();
                while (it.hasNext() && remaining > 0) {
                    Resting r = it.next();
                    long fill = Math.min(remaining, r.qty);
                    if (fill < r.minQty) {
                        continue;   // the RESTING order's own constraint
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

package com.quantfinlib.crb;

import com.quantfinlib.util.MathUtils;

/**
 * Inventory-skewed two-way pricing — the central risk book's quoting
 * face. A CRB long inventory shades BOTH quotes down: the ask gets more
 * attractive (sell what we hold), the bid less (stop accumulating).
 * That is the Avellaneda-Stoikov reservation-price intuition applied at
 * book level, without the vol/horizon machinery: skew is linear in
 * inventory as a fraction of the inventory limit, capped so the quote
 * NEVER crosses itself.
 *
 * <pre>  skew = −(inventory/limit)·skewFraction·halfSpread   (clamped ±1)
 *  bid  = mid·(1 + (−halfSpread + skew)/1e4)
 *  ask  = mid·(1 + (+halfSpread + skew)/1e4)</pre>
 *
 * <p>{@code skewFraction} in [0, 1): at 1 a full-limit inventory would
 * quote a zero-width side, so the ctor stops just short. Deterministic,
 * static, research/warm lane.</p>
 */
public final class SkewedQuoter {

    /** A shaded two-way price. */
    public record Quote(double bid, double ask, double skewBps) {
    }

    private SkewedQuoter() {
    }

    /**
     * @param mid            current fair value, &gt; 0
     * @param halfSpreadBps  unskewed half spread in bps, &gt; 0
     * @param inventory      book inventory in the quoted factor's units (signed)
     * @param inventoryLimit inventory limit, &gt; 0 (same units)
     * @param skewFraction   how much of the half spread a full-limit
     *                       inventory shades, in [0, 1)
     */
    public static Quote quote(double mid, double halfSpreadBps, double inventory,
                              double inventoryLimit, double skewFraction) {
        if (!(mid > 0) || mid == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("mid must be positive and finite");
        }
        if (!(halfSpreadBps > 0) || halfSpreadBps == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("halfSpreadBps must be positive and finite");
        }
        if (!(skewFraction >= 0 && skewFraction < 1)) {
            throw new IllegalArgumentException("skewFraction must be in [0, 1)");
        }
        // The worst-case downward shade is halfSpread·(1 + skewFraction):
        // past 10,000 bps that quotes a ZERO or NEGATIVE bid — no market
        // this class serves has 100% half spreads, so reject loudly.
        if (halfSpreadBps * (1 + skewFraction) >= 10_000) {
            throw new IllegalArgumentException("halfSpreadBps " + halfSpreadBps
                    + " with skewFraction " + skewFraction
                    + " could quote a non-positive bid");
        }
        if (!(inventoryLimit > 0) || inventoryLimit == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("inventoryLimit must be positive and finite");
        }
        if (!Double.isFinite(inventory)) {
            throw new IllegalArgumentException("inventory must be finite");
        }
        double load = MathUtils.clamp(inventory / inventoryLimit, -1, 1);
        double skewBps = -load * skewFraction * halfSpreadBps;
        double bid = mid * (1 + (-halfSpreadBps + skewBps) / 1e4);
        double ask = mid * (1 + (halfSpreadBps + skewBps) / 1e4);
        return new Quote(bid, ask, skewBps);
    }
}

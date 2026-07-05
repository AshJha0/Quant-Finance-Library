package com.quantfinlib.microstructure;

import java.util.ArrayList;
import java.util.List;

/**
 * A call auction (open/close/volatility uncross): orders accumulate without
 * trading, then a single clearing price executes the maximum matchable
 * volume — the mechanism behind exchange opens, the close every
 * benchmark-tracking strategy trades, and LULD/volatility-halt reopenings.
 *
 * <p>Price discovery follows the standard exchange rulebook hierarchy:</p>
 * <ol>
 *   <li><b>Maximum executable volume</b> — the price(s) where cumulative
 *       eligible buys meet cumulative eligible sells;</li>
 *   <li><b>Minimum surplus</b> — among volume ties, the price leaving the
 *       smallest unfilled imbalance;</li>
 *   <li><b>Reference proximity</b> — among remaining ties, closest to the
 *       reference price (typically the last traded price).</li>
 * </ol>
 *
 * <p>Market orders are always eligible on their side. The indicative price /
 * volume / imbalance triple — what venues disseminate during the call phase
 * — is exposed via {@link #indicative}, and {@link #uncross} additionally
 * reports the surplus side. Not thread-safe; an auction is single-threaded
 * by nature (one uncross event).</p>
 */
public final class Auction {

    /** Auction outcome: clearing price, matched volume, and leftover imbalance. */
    public record Result(double price, long volume, long imbalance) {

        /** Positive imbalance = buy surplus, negative = sell surplus. */
        public boolean hasBuySurplus() {
            return imbalance > 0;
        }
    }

    private record Order(double limit, long quantity, boolean market) {
    }

    private final List<Order> buys = new ArrayList<>();
    private final List<Order> sells = new ArrayList<>();

    /** Adds a buy limit order to the call book. */
    public Auction addBuy(double limitPrice, long quantity) {
        validate(limitPrice, quantity);
        buys.add(new Order(limitPrice, quantity, false));
        return this;
    }

    /** Adds a sell limit order to the call book. */
    public Auction addSell(double limitPrice, long quantity) {
        validate(limitPrice, quantity);
        sells.add(new Order(limitPrice, quantity, false));
        return this;
    }

    /** Adds a market-on-auction buy (eligible at any clearing price). */
    public Auction addMarketBuy(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        buys.add(new Order(Double.POSITIVE_INFINITY, quantity, true));
        return this;
    }

    /** Adds a market-on-auction sell. */
    public Auction addMarketSell(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        sells.add(new Order(0, quantity, true));
        return this;
    }

    private static void validate(double limitPrice, long quantity) {
        if (limitPrice <= 0 || Double.isNaN(limitPrice) || quantity <= 0) {
            throw new IllegalArgumentException("limit must be > 0 and quantity > 0");
        }
    }

    /**
     * The indicative uncross the venue would disseminate right now, or
     * {@code null} when no price can match any volume (crossed side empty
     * or book entirely uncrossed).
     */
    public Result indicative(double referencePrice) {
        // Candidate clearing prices: every distinct limit in the book — the
        // executable-volume function only changes at these points.
        double[] candidates = distinctLimits();
        if (candidates.length == 0) {
            return null;
        }
        Result best = null;
        for (double p : candidates) {
            long demand = eligibleQuantity(buys, p, true);
            long supply = eligibleQuantity(sells, p, false);
            long volume = Math.min(demand, supply);
            if (volume == 0) {
                continue;
            }
            long imbalance = demand - supply;
            if (best == null || better(p, volume, imbalance, best, referencePrice)) {
                best = new Result(p, volume, imbalance);
            }
        }
        return best;
    }

    /**
     * Runs the auction: the indicative at this instant becomes the print.
     * The book is left untouched (venues re-open continuous trading with
     * residuals; callers can rebuild as needed).
     */
    public Result uncross(double referencePrice) {
        return indicative(referencePrice);
    }

    /** Rulebook ordering: volume max, then |surplus| min, then reference proximity. */
    private static boolean better(double price, long volume, long imbalance,
                                  Result incumbent, double reference) {
        if (volume != incumbent.volume()) {
            return volume > incumbent.volume();
        }
        long absNew = Math.abs(imbalance);
        long absOld = Math.abs(incumbent.imbalance());
        if (absNew != absOld) {
            return absNew < absOld;
        }
        return Math.abs(price - reference) < Math.abs(incumbent.price() - reference);
    }

    /** Quantity willing to trade at price p (buys: limit ≥ p; sells: limit ≤ p). */
    private static long eligibleQuantity(List<Order> orders, double p, boolean buySide) {
        long qty = 0;
        for (Order o : orders) {
            if (o.market() || (buySide ? o.limit() >= p : o.limit() <= p)) {
                qty += o.quantity();
            }
        }
        return qty;
    }

    private double[] distinctLimits() {
        return java.util.stream.Stream.concat(buys.stream(), sells.stream())
                .filter(o -> !o.market())
                .mapToDouble(Order::limit)
                .distinct()
                .sorted()
                .toArray();
    }
}

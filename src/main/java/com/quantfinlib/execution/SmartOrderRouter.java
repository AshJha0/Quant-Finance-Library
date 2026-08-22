package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Smart Order Router: splits a marketable order across venues to minimize
 * the all-in (fee-adjusted) execution price, respecting each venue's
 * displayed/estimated size. Dark venues price at their midpoint; with
 * {@code preferDark} they are swept first regardless of ranking (to minimize
 * information leakage), otherwise every venue competes purely on all-in price.
 */
public final class SmartOrderRouter {

    /** One child order of the routing plan. {@code price} is the quoted (pre-fee) price. */
    public record RouteLeg(String venue, double price, long quantity, boolean dark) {
    }

    public record RoutingPlan(List<RouteLeg> legs, long routedQty, long unroutedQty,
                              double expectedAvgAllInPrice) {
    }

    private record Candidate(VenueQuote quote, double price, double allInPrice, long size) {
    }

    private SmartOrderRouter() {
    }

    public static RoutingPlan route(Side side, long quantity, List<VenueQuote> venues, boolean preferDark) {
        return route(side, quantity, venues, preferDark, sideDefaultLimit(side));
    }

    /**
     * As {@link #route(Side, long, List, boolean)}, but skips any venue
     * whose price would violate {@code limitPrice} (through-the-limit on
     * the relevant side) — the double-price analogue of {@link
     * HftSor#route}'s {@code limitTick}.
     *
     * @param limitPrice worst acceptable raw (pre-fee) price; pass {@code
     *                    Double.POSITIVE_INFINITY} (buy) / {@code
     *                    Double.NEGATIVE_INFINITY} (sell) for pure market
     */
    public static RoutingPlan route(Side side, long quantity, List<VenueQuote> venues, boolean preferDark,
                                    double limitPrice) {
        return route(side, quantity, venues, preferDark, limitPrice, 0, Long.MAX_VALUE);
    }

    /**
     * As {@link #route(Side, long, List, boolean, double)}, additionally
     * excluding any venue whose quote is stale — the same treatment a NaN
     * or zero-size venue already gets, mirroring {@link HftSor#venueDown}
     * as the way a dead/frozen feed is kept from receiving child orders.
     *
     * @param nowNanos         current time, same clock as {@link
     *                         VenueQuote#lastUpdateNanos}
     * @param maxQuoteAgeNanos a venue is excluded when {@code nowNanos -
     *                         quote.lastUpdateNanos() > maxQuoteAgeNanos};
     *                         pass {@link Long#MAX_VALUE} to disable the
     *                         staleness check
     */
    public static RoutingPlan route(Side side, long quantity, List<VenueQuote> venues, boolean preferDark,
                                    double limitPrice, long nowNanos, long maxQuoteAgeNanos) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
        int sign = side.sign();
        List<Candidate> candidates = new ArrayList<>(venues.size());
        for (VenueQuote v : venues) {
            double price = v.dark() ? v.mid() : (side == Side.BUY ? v.ask() : v.bid());
            long size = side == Side.BUY ? v.askSize() : v.bidSize();
            if (size <= 0 || Double.isNaN(price) || !Double.isFinite(v.feeBps())) {
                continue;
            }
            if (nowNanos - v.lastUpdateNanos() > maxQuoteAgeNanos) {
                continue;                    // stale quote, excluded from routing
            }
            if (sign > 0 ? price > limitPrice : price < limitPrice) {
                continue;                    // through the limit
            }
            double allIn = price * (1 + sign * v.feeBps() / 1e4);
            candidates.add(new Candidate(v, price, allIn, size));
        }
        // Best all-in price first: lowest for a buy, highest for a sell.
        Comparator<Candidate> byPrice = Comparator.comparingDouble(c -> sign * c.allInPrice());
        if (preferDark) {
            candidates.sort(Comparator.<Candidate, Boolean>comparing(c -> !c.quote().dark()).thenComparing(byPrice));
        } else {
            candidates.sort(byPrice);
        }

        List<RouteLeg> legs = new ArrayList<>();
        long remaining = quantity;
        double allInNotional = 0;
        for (Candidate c : candidates) {
            if (remaining <= 0) {
                break;
            }
            long take = Math.min(remaining, c.size());
            legs.add(new RouteLeg(c.quote().venue(), c.price(), take, c.quote().dark()));
            allInNotional += take * c.allInPrice();
            remaining -= take;
        }
        long routed = quantity - remaining;
        return new RoutingPlan(legs, routed, remaining,
                routed == 0 ? Double.NaN : allInNotional / routed);
    }

    private static double sideDefaultLimit(Side side) {
        return side == Side.BUY ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }
}

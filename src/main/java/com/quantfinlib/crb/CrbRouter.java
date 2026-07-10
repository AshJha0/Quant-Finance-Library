package com.quantfinlib.crb;

/**
 * The central risk book's order router — internal cross first, dark
 * pools second, lit last, each leg priced honestly:
 *
 * <ul>
 *   <li><b>Internal</b> — crossing against the book's own offsetting
 *       inventory costs ZERO bps and leaks nothing: the CRB itself is
 *       the firm's first and best dark pool. Capped at the crossable
 *       inventory the caller reports;</li>
 *   <li><b>Dark pools</b> — midpoint fills pay no spread, but a venue
 *       whose fills systematically fade is not free: each venue carries
 *       an ADVERSE-SELECTION charge in bps (a post-fill markout
 *       estimate — {@code VenueScorecard} produces exactly this), and
 *       expected liquidity is discounted by fill probability. A dark
 *       venue is only used while its charge undercuts the lit cost;</li>
 *   <li><b>Lit</b> — pays the half spread plus expected impact, but
 *       fills. Whatever the dark legs are not EXPECTED to fill routes
 *       lit as well — hedges that might fill are not hedges.</li>
 * </ul>
 *
 * <p>Allocation is greedy by expected cost, deterministic, allocation
 * only at decision time (arrays sized to the venue count). Research/
 * warm lane; the caller owns venue statistics and their honesty.</p>
 */
public final class CrbRouter {

    /** A dark venue as the router sees it. */
    public record DarkVenue(String name, double expectedLiquidity,
                            double fillProbability, double adverseSelectionBps) {

        public DarkVenue {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("venue must be named");
            }
            if (!(expectedLiquidity >= 0) || expectedLiquidity == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("expectedLiquidity must be >= 0 and finite");
            }
            if (!(fillProbability >= 0 && fillProbability <= 1)) {
                throw new IllegalArgumentException("fillProbability must be in [0, 1]");
            }
            if (!Double.isFinite(adverseSelectionBps) || adverseSelectionBps < 0) {
                throw new IllegalArgumentException("adverseSelectionBps must be >= 0 and finite");
            }
        }
    }

    /**
     * Where the notional went. {@code dark[i]} aligns with the venue
     * array passed in; {@code expectedCostBps} is the blended expected
     * cost of the whole allocation.
     */
    public record Allocation(double internal, double[] dark, double lit,
                             double expectedCostBps) {
    }

    private CrbRouter() {
    }

    /**
     * Routes {@code notional} (positive, in book-currency units).
     *
     * @param notional          amount to execute, &gt; 0
     * @param crossableInternal offsetting book inventory available to
     *                          cross against, ≥ 0
     * @param venues            dark venues with honest statistics
     * @param halfSpreadBps     lit half spread, &gt; 0
     * @param impactBps         expected lit impact for this size, ≥ 0
     *                          (a {@code KylesLambda} estimate slots in)
     */
    public static Allocation route(double notional, double crossableInternal,
                                   DarkVenue[] venues, double halfSpreadBps,
                                   double impactBps) {
        if (!(notional > 0) || notional == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("notional must be positive and finite");
        }
        if (!(crossableInternal >= 0) || crossableInternal == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("crossableInternal must be >= 0 and finite");
        }
        if (!(halfSpreadBps > 0) || halfSpreadBps == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("halfSpreadBps must be positive and finite");
        }
        if (!(impactBps >= 0) || impactBps == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("impactBps must be >= 0 and finite");
        }
        double litCost = halfSpreadBps + impactBps;

        double remaining = notional;
        double internal = Math.min(remaining, crossableInternal);
        remaining -= internal;
        double costWeighted = 0;                        // internal leg costs 0

        // Dark venues in ascending adverse-selection order, used only
        // while they undercut lit; expected fill = liquidity × fillProb.
        int m = venues.length;
        double[] dark = new double[m];
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(
                venues[a].adverseSelectionBps(), venues[b].adverseSelectionBps()));
        for (int k = 0; k < m && remaining > 0; k++) {
            DarkVenue v = venues[order[k]];
            if (v.adverseSelectionBps() >= litCost) {
                break;                                  // ordered: the rest are worse
            }
            double expectedFill = Math.min(remaining,
                    v.expectedLiquidity() * v.fillProbability());
            if (expectedFill <= 0) {
                continue;
            }
            dark[order[k]] = expectedFill;
            costWeighted += expectedFill * v.adverseSelectionBps();
            remaining -= expectedFill;
        }

        double lit = remaining;
        costWeighted += lit * litCost;
        return new Allocation(internal, dark, lit, costWeighted / notional);
    }
}

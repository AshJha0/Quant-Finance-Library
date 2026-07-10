package com.quantfinlib.execution;

/**
 * The post-or-cross decision — the smallest and most repeated choice in
 * execution, made explicit as expected-cost arithmetic instead of
 * habit. Relative to the current mid, for a buy:
 *
 * <pre>  cross now:   pay the half spread                    → h
 *  post at bid: filled (prob p):  earn h, pay adverse selection a,
 *               collect the rebate r                    → a − h − r
 *               unfilled (1−p):   cross later after the
 *               market drifted d against you            → h + d
 *
 *  expected post cost = p·(a − h − r) + (1−p)·(h + d)
 *  POST iff that beats h</pre>
 *
 * <p>The inputs are the honest parts: {@code fillProbability} from
 * {@code microstructure.FillProbabilityModel} (or your own), the
 * adverse-selection cost {@code a} from post-fill markouts (a passive
 * fill happens exactly when the market comes THROUGH you — free money
 * it is not), and the drift {@code d} from your alpha (positive =
 * expected to move against a waiting buyer). All in the same per-unit
 * currency. Static, deterministic, research/warm lane; per-share or
 * per-notional units both work as long as they are consistent.</p>
 */
public final class OrderPlacementPolicy {

    /** The decision plus the arithmetic that made it. */
    public record Placement(boolean post, double expectedPostCost, double crossCost) {
    }

    private OrderPlacementPolicy() {
    }

    /**
     * @param halfSpread          half the touch spread, &gt; 0
     * @param fillProbability     P(passive order fills within the
     *                            horizon), in [0, 1]
     * @param adverseSelection    expected cost WHEN passively filled
     *                            (post-fill markout), ≥ 0
     * @param adverseDrift        expected move against the order while
     *                            waiting unfilled (signed: negative =
     *                            the market is expected to come to you)
     * @param rebate              maker rebate per unit, ≥ 0
     */
    public static Placement decide(double halfSpread, double fillProbability,
                                   double adverseSelection, double adverseDrift,
                                   double rebate) {
        if (!(halfSpread > 0) || halfSpread == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("halfSpread must be positive and finite");
        }
        if (!(fillProbability >= 0 && fillProbability <= 1)) {
            throw new IllegalArgumentException("fillProbability must be in [0, 1]");
        }
        if (!(adverseSelection >= 0) || adverseSelection == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("adverseSelection must be >= 0 and finite");
        }
        if (!Double.isFinite(adverseDrift)) {
            throw new IllegalArgumentException("adverseDrift must be finite");
        }
        if (!(rebate >= 0) || rebate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("rebate must be >= 0 and finite");
        }
        double postCost = fillProbability * (adverseSelection - halfSpread - rebate)
                + (1 - fillProbability) * (halfSpread + adverseDrift);
        return new Placement(postCost < halfSpread, postCost, halfSpread);
    }

    /**
     * The fill-probability REGION where posting beats crossing. A single
     * breakeven scalar cannot carry the answer: {@code postCost(p) =
     * (h+d) − p·(2h+r+d−a)} is linear in p, and when adverse selection
     * exceeds {@code 2h+r+d} the slope flips — posting then pays only
     * BELOW the threshold, not above. The region says it directly.
     * Empty ({@link #isEmpty()}) = never post under these conditions.
     * Boundaries are indifference points (measure zero; {@code decide}
     * crosses on a tie).
     */
    public record PostRegion(double from, double to) {

        public boolean isEmpty() {
            return from > to;
        }

        public boolean contains(double fillProbability) {
            return fillProbability >= from && fillProbability <= to;
        }
    }

    private static final PostRegion NEVER = new PostRegion(1, 0);

    /**
     * The desk's rule for these market conditions: post iff the fill
     * probability lands inside the returned region of [0, 1].
     */
    public static PostRegion postRegion(double halfSpread, double adverseSelection,
                                        double adverseDrift, double rebate) {
        if (!(halfSpread > 0) || halfSpread == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("halfSpread must be positive and finite");
        }
        if (!(adverseSelection >= 0) || adverseSelection == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("adverseSelection must be >= 0 and finite");
        }
        if (!Double.isFinite(adverseDrift)) {
            throw new IllegalArgumentException("adverseDrift must be finite");
        }
        if (!(rebate >= 0) || rebate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("rebate must be >= 0 and finite");
        }
        // postCost(p) = (h + d) − p·coef;  post iff postCost < h  ⇔  d < p·coef.
        double coef = 2 * halfSpread + rebate + adverseDrift - adverseSelection;
        if (coef > 0) {
            double pStar = adverseDrift / coef;
            return pStar >= 1 ? NEVER : new PostRegion(Math.max(0, pStar), 1);
        }
        if (coef < 0) {
            // The slope flipped: posting pays only BELOW the threshold.
            double pStar = adverseDrift / coef;
            return pStar <= 0 ? NEVER : new PostRegion(0, Math.min(1, pStar));
        }
        // Flat in p: the sign of the drift decides for every p at once.
        return adverseDrift < 0 ? new PostRegion(0, 1) : NEVER;
    }
}

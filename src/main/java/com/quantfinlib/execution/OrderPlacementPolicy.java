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
     * The fill probability at which posting and crossing break even —
     * the desk's rule-of-thumb threshold for these market conditions.
     * Returns a value possibly outside [0, 1]: above 1 means posting
     * NEVER pays here (e.g. adverse selection swamps the spread), below
     * 0 means it always does.
     */
    public static double breakevenFillProbability(double halfSpread, double adverseSelection,
                                                  double adverseDrift, double rebate) {
        // Solve p·(a − h − r) + (1−p)(h + d) = h  →  p = d / (2h + r + d − a).
        double denominator = 2 * halfSpread + rebate + adverseDrift - adverseSelection;
        if (denominator == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return adverseDrift / denominator;
    }
}

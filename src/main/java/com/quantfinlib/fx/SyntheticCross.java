package com.quantfinlib.fx;

import com.quantfinlib.fx.CrossRateEngine.Op;

/**
 * Direct-versus-synthetic cross execution arithmetic: an FX cross (EURJPY)
 * can be dealt directly or replicated through its liquid legs (buy EURUSD,
 * buy USDJPY), and the cheaper route changes with every quote — direct
 * cross books are thin outside London hours while the USD legs stay tight.
 * {@link CrossRateEngine} streams the synthetic <em>rates</em>; this class
 * answers the execution question: what does each route cost <b>after
 * crossing every spread involved</b>, and which should this clip take.
 *
 * <p>Spread composition is the whole point: a synthetic buy pays the ask on
 * both legs ({@link Op#MULTIPLY}: askA × askB) or the ask of one and the
 * bid of the other ({@link Op#DIVIDE}: askA ÷ bidB) — two half-spreads
 * against one on the direct route. The synthetic still wins whenever the
 * legs are tight enough, which is exactly what this comparison detects.
 * Static, primitives-only, zero allocation.</p>
 */
public final class SyntheticCross {

    private SyntheticCross() {
    }

    /**
     * All-in synthetic ASK (cost to BUY the cross via the legs).
     * MULTIPLY (A/B × B/C = A/C): buy both legs → askA × askB.
     * DIVIDE (A/C ÷ B/C = A/B): buy leg A, sell leg B → askA ÷ bidB.
     */
    public static double syntheticAsk(Op op, double bidA, double askA,
                                      double bidB, double askB) {
        return op == Op.MULTIPLY ? askA * askB : askA / bidB;
    }

    /**
     * All-in synthetic BID (proceeds of SELLING the cross via the legs):
     * the mirror of {@link #syntheticAsk}.
     */
    public static double syntheticBid(Op op, double bidA, double askA,
                                      double bidB, double askB) {
        return op == Op.MULTIPLY ? bidA * bidB : bidA / askB;
    }

    /**
     * Savings per unit of buying synthetically instead of directly
     * (positive = the legs are cheaper). NaN when either route is unpriced —
     * where "unpriced" means NaN, zero (the Java default and what an empty
     * {@code FxTierBook} tier reads as) or negative — so an unquoted book
     * can never masquerade as an attractive route, including via the
     * divide-by-zero infinity a raw {@link #syntheticBid} would produce.
     */
    public static double buySavings(double directAsk, Op op, double bidA, double askA,
                                    double bidB, double askB) {
        if (!priced(directAsk) || !priced(askA)
                || !priced(op == Op.MULTIPLY ? askB : bidB)) {
            return Double.NaN;
        }
        return directAsk - syntheticAsk(op, bidA, askA, bidB, askB);
    }

    /** Mirror: extra proceeds per unit of selling via the legs (positive = legs win). */
    public static double sellSavings(double directBid, Op op, double bidA, double askA,
                                     double bidB, double askB) {
        if (!priced(directBid) || !priced(bidA)
                || !priced(op == Op.MULTIPLY ? bidB : askB)) {
            return Double.NaN;
        }
        return syntheticBid(op, bidA, askA, bidB, askB) - directBid;
    }

    /** A dealable price: finite and strictly positive. */
    private static boolean priced(double p) {
        return p > 0 && p < Double.POSITIVE_INFINITY;
    }

    /** True when buying through the legs beats the direct ask (NaN-safe: false). */
    public static boolean buySyntheticWins(double directAsk, Op op, double bidA, double askA,
                                           double bidB, double askB) {
        return buySavings(directAsk, op, bidA, askA, bidB, askB) > 0;
    }

    /** True when selling through the legs beats the direct bid (NaN-safe: false). */
    public static boolean sellSyntheticWins(double directBid, Op op, double bidA, double askA,
                                            double bidB, double askB) {
        return sellSavings(directBid, op, bidA, askA, bidB, askB) > 0;
    }
}

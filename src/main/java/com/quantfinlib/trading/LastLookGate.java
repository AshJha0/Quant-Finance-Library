package com.quantfinlib.trading;

/**
 * Maker-side symmetric last-look price check — the mechanism FX liquidity
 * providers apply to incoming deal requests, implemented the way the FX
 * Global Code (Principle 17) says it must be: <b>symmetric</b>. At the end
 * of the hold window the quoted price is compared to the current fair
 * price, and the request is rejected when the market has moved beyond the
 * tolerance in <em>either</em> direction — protecting the maker from being
 * picked off, without free-optioning the taker (accepting only the moves
 * that favor the maker is the asymmetric practice the Code prohibits).
 *
 * <p>This class is the decision arithmetic plus its disclosure statistics
 * (accept/reject counts split by who the reject protected — the numbers an
 * LP publishes and a taker's {@code fx.LpScorecard} measures from the other
 * side). The hold window itself belongs to the caller's timer/session
 * machinery. For the taker-side <em>backtest</em> model of adverse
 * (asymmetric) last look, see {@code backtest.LastLookExecution}.
 * Zero allocation, single writer.</p>
 */
public final class LastLookGate {

    private final double tolerance;

    private long accepts;
    private long rejects;
    private long makerProtectiveRejects;
    private long takerProtectiveRejects;

    /**
     * @param tolerance maximum |current fair − quoted| move, in price units
     *                  (e.g. 0.0001 = 1 pip on EURUSD), beyond which the
     *                  request is rejected — in both directions
     */
    public LastLookGate(double tolerance) {
        if (tolerance <= 0 || Double.isNaN(tolerance)) {
            throw new IllegalArgumentException("tolerance must be positive");
        }
        this.tolerance = tolerance;
    }

    /**
     * The decision at the end of the hold: accept iff the fair price is
     * still within tolerance of the quote. Symmetric — the direction of
     * the move never changes the outcome, only the statistics.
     *
     * @param makerSells   true when the taker is buying (maker sells at the quote)
     * @param quotedPrice  the price the maker showed
     * @param currentFair  the maker's current fair value (e.g. composite mid)
     */
    public boolean accept(boolean makerSells, double quotedPrice, double currentFair) {
        double move = currentFair - quotedPrice;
        if (Math.abs(move) <= tolerance) {
            accepts++;
            return true;
        }
        rejects++;
        // Classification only (the decision is already made): a maker who
        // sells is hurt by a rising fair; falling fair means the reject
        // "protected" a taker who would have overpaid.
        boolean hurtsMaker = makerSells ? move > 0 : move < 0;
        if (hurtsMaker) {
            makerProtectiveRejects++;
        } else {
            takerProtectiveRejects++;
        }
        return false;
    }

    public long accepts() {
        return accepts;
    }

    public long rejects() {
        return rejects;
    }

    /** Rejects where the move was against the maker (the classic pick-off). */
    public long makerProtectiveRejects() {
        return makerProtectiveRejects;
    }

    /**
     * Rejects where the move favored the maker — a symmetric gate produces
     * these in roughly equal measure; their absence in an LP's disclosures
     * is the signature of asymmetric last look.
     */
    public long takerProtectiveRejects() {
        return takerProtectiveRejects;
    }

    /** Reject fraction of all decisions (NaN before any decision). */
    public double rejectRate() {
        long total = accepts + rejects;
        return total == 0 ? Double.NaN : (double) rejects / total;
    }
}

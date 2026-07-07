package com.quantfinlib.fx;

/**
 * Last-look-aware LP router: chooses where to send an FX clip by
 * <em>expected all-in</em> price, not displayed price. The FX-specific
 * insight it encodes: a tight quote from an LP that rejects 20% of the time
 * — and whose rejects are followed by adverse markout — is more expensive
 * than a wider firm quote. Expected cost per LP:
 *
 * <pre>  quoted price  ±  rejectRate × max(postRejectMarkout, 0)</pre>
 *
 * (worse for the taker on either side), with LPs above a configurable
 * reject-rate cap vetoed outright. Quotes come from an {@link FxTierBook}
 * (full-amount tiers), behavior from an {@link LpScorecard}.
 *
 * <p>Full-amount only, one LP per clip — the FX convention that avoids
 * spraying child orders (each child leaks intent to another counterparty's
 * last-look window). For deliberate multi-LP sweeps, use
 * {@link FxTierBook#sweepPlan} directly and accept the signaling.</p>
 *
 * <p>Zero allocation, single writer. The route decision returns the LP
 * index; the prices behind the decision are readable until the next call
 * ({@link #lastQuotedPrice}, {@link #lastExpectedPrice}).</p>
 */
public final class LpRouter {

    private final FxTierBook book;
    private final LpScorecard card;
    private final double maxRejectRate;
    private final double holdUrgencyBpsPerMs;

    private double lastQuotedPrice = Double.NaN;
    private double lastExpectedPrice = Double.NaN;
    private long routeCount;
    private long vetoCount;

    /**
     * <b>Wiring requirement</b>: the scorecard's markout penalty only works
     * if {@code card.onMid} is fed composite mids on the same clock as
     * {@code onReject} — without it markouts never mature, the penalty is
     * silently zero, and routing degrades to displayed-price-plus-veto.
     * Watch {@code card.maturedMarkouts()} in monitoring: zero while
     * rejects accrue means the hook is missing.
     *
     * @param maxRejectRate LPs whose EWMA reject rate exceeds this are
     *                      vetoed regardless of price (e.g. 0.25)
     */
    public LpRouter(FxTierBook book, LpScorecard card, double maxRejectRate) {
        this(book, card, maxRejectRate, 0);
    }

    /**
     * With a hold-time urgency: an LP's last-look hold is FX's latency
     * dimension — while it deliberates, the market drifts against you. A
     * positive {@code holdUrgencyBpsPerMs} charges each LP's EWMA hold
     * time against its quote (bps of price per millisecond held), so a
     * slow-holding LP loses ties exactly like a high-latency venue does in
     * {@code execution.AdaptiveSor}.
     */
    public LpRouter(FxTierBook book, LpScorecard card, double maxRejectRate,
                    double holdUrgencyBpsPerMs) {
        if (book.lpCount() != card.lpCount()) {
            throw new IllegalArgumentException("book and scorecard LP counts differ");
        }
        if (maxRejectRate <= 0 || maxRejectRate > 1) {
            throw new IllegalArgumentException("maxRejectRate must be in (0,1]");
        }
        if (holdUrgencyBpsPerMs < 0) {
            throw new IllegalArgumentException("holdUrgencyBpsPerMs must be >= 0");
        }
        this.book = book;
        this.card = card;
        this.maxRejectRate = maxRejectRate;
        this.holdUrgencyBpsPerMs = holdUrgencyBpsPerMs;
    }

    /**
     * Chooses the LP for a full-amount clip. Returns the LP index, or -1
     * when no eligible LP quotes the size (book too shallow or all vetoed).
     * After a successful call, {@link #lastQuotedPrice} is the LP's raw
     * tier price and {@link #lastExpectedPrice} the reject-adjusted price
     * the decision was made on.
     */
    public int route(boolean buy, double size) {
        routeCount++;
        lastQuotedPrice = Double.NaN;
        lastExpectedPrice = Double.NaN;
        int bestLp = -1;
        double bestExpected = 0;
        double bestQuoted = 0;
        int lps = book.lpCount();
        for (int lp = 0; lp < lps; lp++) {
            double rate = card.rejectRate(lp);
            if (rate > maxRejectRate) {
                vetoCount++;
                continue;
            }
            double quoted = book.fullAmountPrice(lp, buy, size);
            double penalty = rate * Math.max(card.postRejectMarkout(lp), 0);
            if (holdUrgencyBpsPerMs > 0) {
                // Hold time is priced like venue latency: bps/ms of quote.
                penalty += quoted * (card.avgHoldNanos(lp) / 1e6)
                        * holdUrgencyBpsPerMs / 1e4;
            }
            double expected = buy ? quoted + penalty : quoted - penalty;
            if (!Double.isFinite(expected)) {
                continue;    // unquoted LP, or a poisoned stat: never routable
            }
            if (bestLp == -1 || (buy ? expected < bestExpected : expected > bestExpected)) {
                bestLp = lp;
                bestExpected = expected;
                bestQuoted = quoted;
            }
        }
        if (bestLp >= 0) {
            lastQuotedPrice = bestQuoted;
            lastExpectedPrice = bestExpected;
        }
        return bestLp;
    }

    /** Raw quoted price behind the last successful {@link #route}; NaN otherwise. */
    public double lastQuotedPrice() {
        return lastQuotedPrice;
    }

    /** Reject-adjusted price behind the last successful {@link #route}; NaN otherwise. */
    public double lastExpectedPrice() {
        return lastExpectedPrice;
    }

    public long routeCount() {
        return routeCount;
    }

    /** LP-candidate evaluations skipped for exceeding the reject-rate cap. */
    public long vetoCount() {
        return vetoCount;
    }
}

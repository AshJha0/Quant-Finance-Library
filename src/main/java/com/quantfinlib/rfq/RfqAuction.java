package com.quantfinlib.rfq;

/**
 * One request-for-quote auction — how equity derivatives actually trade.
 * Structured products (autocallables, exotic options) have no order
 * book: the buy-side sends an RFQ to a panel of dealers, collects quotes
 * for a window, and deals on the best — while recording the <b>cover</b>
 * (second-best) price, the industry's standard measure of how much the
 * winner's edge was worth and the input every dealer-selection model
 * feeds on.
 *
 * <p>Prices are for ONE unit of the instrument in the client's
 * direction: for a client BUY, lower is better; for a SELL, higher.
 * Anchor the auction with the model fair value (e.g.
 * {@code pricing.Autocallable.price}) and {@link #winnerSpreadToFairBps}
 * reports what was paid versus theory — the number that goes into
 * {@link RfqDealerScorecard}. Fixed dealer panel (dense indices), no
 * allocation after construction, single writer per auction; auctions
 * are cheap and short-lived (one per RFQ).</p>
 */
public final class RfqAuction {

    private final boolean clientBuys;
    private final double fairValue;
    private final double[] quotes;         // NaN = no quote
    private final long[] firstQuoteNanos;  // -1 = never quoted (response time)
    private final long requestNanos;

    /**
     * @param clientBuys   the client's direction (buy = pay the price)
     * @param fairValue    the model anchor price (NaN = no anchor; spreads
     *                     to fair read NaN)
     * @param dealerCount  panel size (dense dealer indices)
     * @param requestNanos when the RFQ went out (response times measure
     *                     from here)
     */
    public RfqAuction(boolean clientBuys, double fairValue, int dealerCount,
                      long requestNanos) {
        if (dealerCount < 1) {
            throw new IllegalArgumentException("need dealerCount >= 1");
        }
        this.clientBuys = clientBuys;
        this.fairValue = fairValue;
        this.quotes = new double[dealerCount];
        this.firstQuoteNanos = new long[dealerCount];
        this.requestNanos = requestNanos;
        java.util.Arrays.fill(quotes, Double.NaN);
        java.util.Arrays.fill(firstQuoteNanos, -1L);
    }

    /**
     * A dealer's quote. A dealer may refresh; the LAST price stands (but
     * {@link #responseNanos} keeps the FIRST response — a dealer who
     * showed up in 50ms and refreshed near the close is a fast responder,
     * not a slow one). Non-positive or non-finite prices are declines —
     * recorded as no quote, never as a tradable level; an explicit pull
     * withdraws a standing quote.
     */
    public void onQuote(int dealer, double price, long timestampNanos) {
        if (!(price > 0) || price == Double.POSITIVE_INFINITY) {
            quotes[dealer] = Double.NaN;
            return;
        }
        quotes[dealer] = price;
        if (firstQuoteNanos[dealer] < 0) {
            firstQuoteNanos[dealer] = timestampNanos;
        }
    }

    /** The winning dealer index, or -1 while nobody has quoted. */
    public int winner() {
        int best = -1;
        for (int d = 0; d < quotes.length; d++) {
            if (Double.isNaN(quotes[d])) {
                continue;
            }
            if (best == -1 || better(quotes[d], quotes[best])) {
                best = d;
            }
        }
        return best;
    }

    /** The best (dealable) price, NaN while nobody has quoted. */
    public double bestPrice() {
        int w = winner();
        return w == -1 ? Double.NaN : quotes[w];
    }

    /**
     * The cover: the second-best price — what the trade would have cost
     * without the winner. NaN with fewer than two quotes. The classic
     * dealer-performance yardstick: a winner far inside the cover left
     * nothing on the table; a winner AT the cover was replaceable.
     */
    public double coverPrice() {
        int w = winner();
        double cover = Double.NaN;
        for (int d = 0; d < quotes.length; d++) {
            if (d == w || Double.isNaN(quotes[d])) {
                continue;
            }
            if (Double.isNaN(cover) || better(quotes[d], cover)) {
                cover = quotes[d];
            }
        }
        return cover;
    }

    /**
     * What the winning quote costs versus the model fair value, in bps of
     * fair — positive = paying over theory (buys) / receiving under it
     * (sells). NaN without a winner or a fair-value anchor.
     */
    public double winnerSpreadToFairBps() {
        return spreadToFairBps(bestPrice());
    }

    /** {@link #winnerSpreadToFairBps} for any price (e.g. a losing quote). */
    public double spreadToFairBps(double price) {
        if (Double.isNaN(price) || !(fairValue > 0)
                || fairValue == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }
        double signed = clientBuys ? price - fairValue : fairValue - price;
        return signed / fairValue * 1e4;
    }

    /**
     * Time from the request to this dealer's FIRST response, or -1 if the
     * dealer never quoted this auction (a later pull does not erase that
     * they showed up).
     */
    public long responseNanos(int dealer) {
        return firstQuoteNanos[dealer] < 0 ? -1 : firstQuoteNanos[dealer] - requestNanos;
    }

    /** A dealer's standing quote (NaN = none/declined/pulled). */
    public double quote(int dealer) {
        return quotes[dealer];
    }

    /** Dealers with a standing quote right now (a small scan — panels are ~10). */
    public int quoteCount() {
        int count = 0;
        for (double q : quotes) {
            if (!Double.isNaN(q)) {
                count++;
            }
        }
        return count;
    }

    public int dealerCount() {
        return quotes.length;
    }

    public boolean clientBuys() {
        return clientBuys;
    }

    private boolean better(double a, double b) {
        return clientBuys ? a < b : a > b;
    }
}

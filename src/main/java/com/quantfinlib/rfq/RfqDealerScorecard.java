package com.quantfinlib.rfq;

import com.quantfinlib.persist.Checkpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Streaming per-dealer RFQ quality — the structured-products sibling of
 * {@code execution.VenueScorecard} and {@code fx.LpScorecard}: over many
 * auctions, which dealers actually show up, how fast, how competitively,
 * and how often they win. This is what decides tomorrow's panel — a
 * dealer who quotes 95% of requests two ticks off fair beats one who
 * wins occasionally and declines the rest, because panel slots are
 * finite and a decline is information you paid for with a revealed
 * intention to trade.
 *
 * <p>Feed each finished {@link RfqAuction} via {@link #onAuction}: every
 * panel dealer records quoted-or-declined, quoters record their response
 * time and spread-to-fair, the winner records the win. All statistics
 * are per-event EWMAs seeded from the first observation (the library's
 * convention: a dealer's first quote is never blended against zero).
 * Zero allocation per auction, single writer; the learned panel quality
 * persists via {@code persist.Checkpoint}.</p>
 */
public final class RfqDealerScorecard {

    private final int dealerCount;
    private final double alpha;

    private final long[] requests;
    private final long[] quotesGiven;
    private final long[] wins;
    private final double[] quoteRateEwma;      // {0,1} per request
    private final double[] responseNanosEwma;  // quoters only
    private final double[] spreadToFairEwma;   // bps, quoters with an anchor only
    private final long[] spreadSamples;

    /**
     * @param dealerCount panel size (dense indices, aligned with the
     *                    auctions you feed)
     * @param alpha       EWMA weight per auction, e.g. 0.05
     */
    public RfqDealerScorecard(int dealerCount, double alpha) {
        if (dealerCount < 1 || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("need dealerCount >= 1, alpha in (0,1]");
        }
        this.dealerCount = dealerCount;
        this.alpha = alpha;
        this.requests = new long[dealerCount];
        this.quotesGiven = new long[dealerCount];
        this.wins = new long[dealerCount];
        this.quoteRateEwma = new double[dealerCount];
        this.responseNanosEwma = new double[dealerCount];
        this.spreadToFairEwma = new double[dealerCount];
        this.spreadSamples = new long[dealerCount];
    }

    /** 5% per-auction weight. */
    public RfqDealerScorecard(int dealerCount) {
        this(dealerCount, 0.05);
    }

    /**
     * Records a finished auction: every dealer on the panel is scored
     * (quoted or declined), quoters add response time and — when the
     * auction carried a fair-value anchor — spread to fair. The panel
     * sizes must match; a mismatched auction throws rather than
     * misattributing dealers.
     */
    public void onAuction(RfqAuction auction) {
        if (auction.dealerCount() != dealerCount) {
            throw new IllegalArgumentException("auction has " + auction.dealerCount()
                    + " dealers, scorecard has " + dealerCount);
        }
        int winner = auction.winner();
        for (int d = 0; d < dealerCount; d++) {
            boolean quoted = !Double.isNaN(auction.quote(d));
            quoteRateEwma[d] = requests[d] == 0
                    ? (quoted ? 1 : 0)
                    : quoteRateEwma[d] + alpha * ((quoted ? 1 : 0) - quoteRateEwma[d]);
            requests[d]++;
            if (!quoted) {
                continue;
            }
            quotesGiven[d]++;
            long response = auction.responseNanos(d);
            responseNanosEwma[d] = quotesGiven[d] == 1
                    ? response
                    : responseNanosEwma[d] + alpha * (response - responseNanosEwma[d]);
            double spread = auction.spreadToFairBps(auction.quote(d));
            if (Double.isFinite(spread)) {
                spreadToFairEwma[d] = spreadSamples[d] == 0
                        ? spread
                        : spreadToFairEwma[d] + alpha * (spread - spreadToFairEwma[d]);
                spreadSamples[d]++;
            }
            if (d == winner) {
                wins[d]++;
            }
        }
    }

    /** EWMA probability this dealer quotes when asked (0 before any request). */
    public double quoteRate(int dealer) {
        return quoteRateEwma[dealer];
    }

    /** Win rate over requests answered — lifetime, not decayed (small counts). */
    public double winRate(int dealer) {
        return quotesGiven[dealer] == 0 ? 0 : (double) wins[dealer] / quotesGiven[dealer];
    }

    /** EWMA response time in nanos (0 before any quote). */
    public double avgResponseNanos(int dealer) {
        return responseNanosEwma[dealer];
    }

    /**
     * EWMA quoted spread to model fair in bps — the competitiveness
     * number: how far off theory this dealer's ink lands, win or lose.
     * 0 before any anchored quote.
     */
    public double avgSpreadToFairBps(int dealer) {
        return spreadToFairEwma[dealer];
    }

    public long requests(int dealer) {
        return requests[dealer];
    }

    public long quotesGiven(int dealer) {
        return quotesGiven[dealer];
    }

    public long wins(int dealer) {
        return wins[dealer];
    }

    public int dealerCount() {
        return dealerCount;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the learned panel quality — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        Checkpoint.writeLongs(out, requests);
        Checkpoint.writeLongs(out, quotesGiven);
        Checkpoint.writeLongs(out, wins);
        Checkpoint.writeLongs(out, spreadSamples);
        Checkpoint.writeDoubles(out, quoteRateEwma);
        Checkpoint.writeDoubles(out, responseNanosEwma);
        Checkpoint.writeDoubles(out, spreadToFairEwma);
    }

    /** Restores the card. Throws on a panel-size or version mismatch. */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "RfqDealerScorecard");
        Checkpoint.readLongsInto(in, requests);
        Checkpoint.readLongsInto(in, quotesGiven);
        Checkpoint.readLongsInto(in, wins);
        Checkpoint.readLongsInto(in, spreadSamples);
        Checkpoint.readDoublesInto(in, quoteRateEwma);
        Checkpoint.readDoublesInto(in, responseNanosEwma);
        Checkpoint.readDoublesInto(in, spreadToFairEwma);
    }
}

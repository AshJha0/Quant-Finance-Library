package com.quantfinlib.trading;

import com.quantfinlib.marketdata.QuoteListener;
import com.quantfinlib.marketdata.StridedSequences;
import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.core.Side;

/**
 * Live position-band auto-hedger on the fast lane, the streaming
 * counterpart of the batch {@code hedging.DeltaHedger}: on every market
 * event it compares the risk gate's position with a band and, on a breach,
 * sends one marketable order for the excess.
 *
 * <pre>
 *   |position| &gt; positionBand  →  IOC for (|position| − positionBand) on the
 *                                  opposite side, priced at the opposite touch
 * </pre>
 *
 * <p>Hedging to the band edge rather than to flat removes the breach with
 * the smallest order and avoids ping-ponging around zero.</p>
 *
 * <h2>Working-hedge tracking</h2>
 * <p>At most one hedge per symbol is working at a time. While it is live
 * (PENDING_NEW, NEW or PARTIALLY_FILLED in the gateway's state machine) no
 * further hedge is sent: the position only moves when fills arrive, and a
 * second order for the same excess would double-hedge. The next hedge is
 * considered only after the venue has finished with the previous one
 * (filled, IOC remainder cancelled, or rejected), and then no sooner than
 * {@code minHedgeIntervalNanos} after the previous send. A hedge that the
 * venue never answers is caught by the gateway's ack timeout, which cancels
 * it.</p>
 *
 * <h2>Pricing</h2>
 * <p>Hedges are immediate-or-cancel limit orders at the opposite touch (sell
 * at the best bid, buy at the best offer) from {@link #onQuote}, so they are
 * marketable when sent and never rest. Without a BBO the last trade price is
 * used and the hedge is counted in {@link #hedgesWithoutTouch()}; such a
 * hedge may not be marketable, but as an IOC it still cannot rest.</p>
 *
 * <p>Register for both trades and quotes on the bus (consumer thread), after
 * the quoter. Single-threaded; zero allocation per event.</p>
 */
public final class AutoHedger implements TickListener, QuoteListener {

    private static final long NEVER = Long.MIN_VALUE;

    private static final int SUBMITTED = 0;
    private static final int REJECTED = 1;
    private static final int HELD = 2;
    private static final int NO_TOUCH = 3;

    private final HftOrderGateway gateway;
    private final HftRiskGate riskGate;
    private final long positionBand;
    private final long minHedgeIntervalNanos;

    private final long[] lastHedgeNanos;
    private final long[] workingHedge;     // gateway id, 0 = none
    private final double[] bestBid;
    private final double[] bestAsk;
    private final StridedSequences counters = new StridedSequences(4);

    /**
     * @param positionBand          absolute position tolerated without hedging (&gt; 0)
     * @param minHedgeIntervalNanos minimum spacing between hedge sends per symbol (&gt;= 0)
     */
    public AutoHedger(HftOrderGateway gateway, int maxSymbols, long positionBand,
                      long minHedgeIntervalNanos) {
        if (positionBand <= 0 || minHedgeIntervalNanos < 0) {
            throw new IllegalArgumentException(
                    "positionBand must be > 0 and cooldown >= 0");
        }
        this.gateway = gateway;
        this.riskGate = gateway.riskGate();
        this.positionBand = positionBand;
        this.minHedgeIntervalNanos = minHedgeIntervalNanos;
        this.lastHedgeNanos = new long[maxSymbols];
        this.workingHedge = new long[maxSymbols];
        this.bestBid = new double[maxSymbols];
        this.bestAsk = new double[maxSymbols];
        // Zero would suppress startup hedges on clocks that begin near 0.
        java.util.Arrays.fill(lastHedgeNanos, NEVER);
        java.util.Arrays.fill(bestBid, Double.NaN);
        java.util.Arrays.fill(bestAsk, Double.NaN);
    }

    @Override
    public void onQuote(int symbolId, double bid, double bidSize, double ask, double askSize,
                        long timestampNanos) {
        bestBid[symbolId] = bid > 0 && bid != Double.POSITIVE_INFINITY ? bid : Double.NaN;
        bestAsk[symbolId] = ask > 0 && ask != Double.POSITIVE_INFINITY ? ask : Double.NaN;
        evaluate(symbolId, Double.NaN, timestampNanos);
    }

    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        evaluate(symbolId, price, timestampNanos);
    }

    private void evaluate(int symbolId, double lastTrade, long timestampNanos) {
        long working = workingHedge[symbolId];
        if (working != 0) {
            if (gateway.isLive(working)) {
                counters.incrementOpaque(HELD);
                return;
            }
            workingHedge[symbolId] = 0;
        }
        long position = riskGate.position(symbolId);
        long excess = Math.abs(position) - positionBand;
        if (excess <= 0) {
            return;
        }
        long last = lastHedgeNanos[symbolId];
        if (last != NEVER && timestampNanos - last < minHedgeIntervalNanos) {
            return;
        }
        Side side = position > 0 ? Side.SELL : Side.BUY;
        double price = side == Side.SELL ? bestBid[symbolId] : bestAsk[symbolId];
        if (Double.isNaN(price)) {
            price = lastTrade;
            if (Double.isNaN(price)) {
                return; // quote update without a usable touch: wait for the next event
            }
            counters.incrementOpaque(NO_TOUCH);
        }
        long id = gateway.submit(symbolId, side, excess, price, OrderListener.FLAG_IOC,
                timestampNanos);
        if (id > 0) {
            workingHedge[symbolId] = id;
            lastHedgeNanos[symbolId] = timestampNanos;
            counters.incrementOpaque(SUBMITTED);
        } else {
            counters.incrementOpaque(REJECTED);
        }
    }

    /** Gateway id of the working hedge (0 if none). */
    public long workingHedgeId(int symbolId) {
        return workingHedge[symbolId];
    }

    /** Hedge orders accepted by the gateway. */
    public long hedgesSubmitted() {
        return counters.getOpaque(SUBMITTED);
    }

    /** Hedge attempts refused by the risk gate, the throttle or a full ring. */
    public long hedgesRejected() {
        return counters.getOpaque(REJECTED);
    }

    /** Evaluations skipped because a previous hedge was still working. */
    public long hedgesHeld() {
        return counters.getOpaque(HELD);
    }

    /** Hedges priced at the last trade because no touch was known. */
    public long hedgesWithoutTouch() {
        return counters.getOpaque(NO_TOUCH);
    }
}

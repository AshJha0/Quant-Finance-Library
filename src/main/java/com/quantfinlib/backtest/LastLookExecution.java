package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.List;

/**
 * Last-look execution model — the missing realism for FX backtests: on ECN
 * and single-dealer FX liquidity, the provider holds your order briefly and
 * may <em>reject</em> it if the price moves against them during the hold.
 * A backtest that fills every FX order unconditionally is fiction; rejects
 * cluster exactly on the flow that was about to be profitable.
 *
 * <p>Bar-level model of the hold window: the order arrives at the bar open
 * and the LP watches the intra-bar move.</p>
 * <ul>
 *   <li>Move in the taker's favor beyond {@code rejectThresholdBps} (price
 *       rising on a buy — adverse to the LP who would sell) → <b>reject</b>;
 *       the parent quantity carries to the next bar, exactly like real
 *       requote-and-chase.</li>
 *   <li>Otherwise → full fill at the open plus the taker pays
 *       {@code spreadBps} half-spread (all-in price).</li>
 * </ul>
 *
 * <p>The asymmetry is the point of last look: benign or LP-favorable moves
 * always fill. Rejection statistics are exposed for TCA — a live desk
 * watches its reject rate per LP for exactly this pattern.</p>
 */
public final class LastLookExecution implements ExecutionModel {

    private final double spreadBps;
    private final double rejectThresholdBps;

    private long fills;
    private long rejects;

    /**
     * @param spreadBps          half-spread paid on accepted fills
     * @param rejectThresholdBps intra-bar move (bps, in the taker's favor)
     *                           beyond which the LP rejects
     */
    public LastLookExecution(double spreadBps, double rejectThresholdBps) {
        if (spreadBps < 0 || rejectThresholdBps <= 0) {
            throw new IllegalArgumentException(
                    "spreadBps must be >= 0 and rejectThresholdBps > 0");
        }
        this.spreadBps = spreadBps;
        this.rejectThresholdBps = rejectThresholdBps;
    }

    @Override
    public List<Execution> execute(Side side, long requestedQty, BarSeries series, int index) {
        if (requestedQty <= 0) {
            return List.of();
        }
        double open = series.open(index);
        double close = series.close(index);
        // Signed move in the TAKER's favor: up for a buy, down for a sell.
        double favorableMoveBps = side.sign() * (close - open) / open * 1e4;
        if (favorableMoveBps > rejectThresholdBps) {
            // The LP saw the market run away and pulled the quote. The
            // engine retries the remainder on later bars — chasing the move.
            rejects++;
            return List.of();
        }
        fills++;
        double allIn = open * (1 + side.sign() * spreadBps / 1e4);
        return List.of(new Execution(series.symbol(), side, allIn, requestedQty,
                series.timestamp(index), "LASTLOOK"));
    }

    /** Accepted fills (parent-bar attempts, not shares). */
    public long fillCount() {
        return fills;
    }

    /** Last-look rejections — the number a real desk tracks per LP. */
    public long rejectCount() {
        return rejects;
    }

    /** Reject rate across all attempts, 0 when nothing was attempted. */
    public double rejectRate() {
        long attempts = fills + rejects;
        return attempts == 0 ? 0 : (double) rejects / attempts;
    }
}

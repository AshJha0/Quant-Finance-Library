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
 *
 * <p><b>Signal-bar handling</b>: when worked through
 * {@link ExecutionAwareBacktester}, the parent is created at the signal
 * bar's CLOSE — so on that first bar there is no hold window left to
 * observe, and filling at that bar's <i>open</i> would credit a price from
 * before the signal existed (intrabar time travel). The model therefore
 * HOLDS on the parent's signal bar (no fill, no reject counted — it is
 * pure latency, the LP has seen nothing yet); the first real attempt is
 * the next bar, whose open is the price standing when the order actually
 * arrived. Direct calls without {@link #onParentOrder} keep the plain
 * arrives-at-the-open semantics.</p>
 * <ul>
 *   <li>Move in the taker's favor beyond {@code rejectThresholdBps} (price
 *       rising on a buy — adverse to the LP who would sell) → <b>reject</b>;
 *       the parent quantity carries to the next bar, exactly like real
 *       requote-and-chase.</li>
 *   <li>Otherwise → full fill at the open plus the taker pays
 *       {@code spreadBps} half-spread (all-in price).</li>
 * </ul>
 *
 * <p>The asymmetry here is deliberate <em>as a taker's worst-case model</em>:
 * it simulates the adverse LP behavior the FX Global Code prohibits but a
 * taker must still budget for. The Code-compliant maker-side mechanism —
 * symmetric rejection in both directions — is
 * {@code com.quantfinlib.trading.LastLookGate}; when calibrating this
 * model's threshold from an LP's published (symmetric) disclosures, note
 * those statistics count rejects in both directions while this model
 * rejects only one. Rejection statistics are exposed for TCA — a live desk
 * watches its reject rate per LP for exactly this pattern.</p>
 */
public final class LastLookExecution implements ExecutionModel {

    private final double spreadBps;
    private final double rejectThresholdBps;

    private long fills;
    private long rejects;
    private int parentSignalBar = -1;

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
    public void onParentOrder(Side side, long totalQuantity, int signalIndex) {
        parentSignalBar = signalIndex;
    }

    @Override
    public double referencePrice(BarSeries series, int index) {
        // Fills anchor to the bar OPEN, not the close: sizing against the
        // close would overdraw cash on any accepted bar that gaps open-high
        // (the engine budgets request * referencePrice * (1 + spread)).
        return series.open(index);
    }

    @Override
    public double worstCaseCostFraction() {
        // Exact: the all-in fill is open * (1 + spread), and referencePrice
        // hands the engine that same open.
        return spreadBps / 1e4;
    }

    @Override
    public List<Execution> execute(Side side, long requestedQty, BarSeries series, int index) {
        if (requestedQty <= 0) {
            return List.of();
        }
        if (index == parentSignalBar) {
            // Order decided at this bar's close: no hold window has elapsed
            // and this bar's open predates the signal. Hold — not a reject.
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

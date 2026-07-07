package com.quantfinlib.microstructure;

import com.quantfinlib.util.MathUtils;

/**
 * Passive-fill probability for a limit order resting AWAY from the touch —
 * the placement question {@link QueueModel} alone can't answer. A resting
 * order fills only if two things happen:
 *
 * <ol>
 *   <li><b>The price reaches the level</b> — under a driftless diffusion
 *       with volatility σ, the probability the price travels a distance
 *       {@code d} within horizon {@code T} is the reflection-principle
 *       barrier-touch probability {@code 2·Φ(−d/(σ√T))} (1 when you're
 *       already at/through the level);</li>
 *   <li><b>The queue at the level clears to you</b> — {@link QueueModel}'s
 *       territory: {@code P = exp(−(qtyAhead + qty)/expectedTraded)}.</li>
 * </ol>
 *
 * {@link #passiveFillProbability} composes the two under an independence
 * approximation — documented honestly: touch and queue-clearing are
 * positively correlated (the flow that moves price also eats queues), so
 * the composition is a mild UNDERestimate; treat it as a conservative
 * placement score, not a calibrated probability.
 *
 * <p>Volatility enters as return-per-√second (what
 * {@code SignalEngine.volPerSqrtSecond} emits), converted to price units
 * against the current price. Static, cross-asset, zero allocation.</p>
 */
public final class FillProbabilityModel {

    private FillProbabilityModel() {
    }

    /**
     * Probability the price touches a level {@code distance} away (in
     * price units, ≥ 0) within {@code horizonSeconds}, given volatility
     * {@code volPerSqrtSecond} (return per √second) at {@code price}.
     * 1 at/through the level; 0 for degenerate inputs (no vol, no time,
     * no price — a dead market never reaches anything).
     */
    public static double touchProbability(double distance, double volPerSqrtSecond,
                                          double horizonSeconds, double price) {
        if (distance <= 0) {
            return 1.0;
        }
        if (!(volPerSqrtSecond > 0) || !(horizonSeconds > 0) || !(price > 0)
                || volPerSqrtSecond == Double.POSITIVE_INFINITY
                || distance == Double.POSITIVE_INFINITY) {
            return 0.0;
        }
        double sigmaAbs = price * volPerSqrtSecond * Math.sqrt(horizonSeconds);
        return MathUtils.clamp(2 * MathUtils.normCdf(-distance / sigmaAbs), 0, 1);
    }

    /**
     * Probability a passive order {@code distance} from the current price
     * fills within the horizon: touch × queue-clear (independence
     * approximation, mildly conservative — see the class doc).
     *
     * @param qtyAhead          shares ahead in the queue at the level (from
     *                          {@code L3BookBuilder.sharesAhead},
     *                          {@link QueuePositionEstimator}, or displayed
     *                          size before joining)
     * @param orderQty          our order size
     * @param expectedTradedQty volume expected to execute at the level over
     *                          the horizon (e.g. from {@link VolumeCurve})
     */
    public static double passiveFillProbability(double distance, double volPerSqrtSecond,
                                                double horizonSeconds, double price,
                                                long qtyAhead, long orderQty,
                                                double expectedTradedQty) {
        double touch = touchProbability(distance, volPerSqrtSecond, horizonSeconds, price);
        return touch * QueueModel.fillProbability(qtyAhead, orderQty, expectedTradedQty);
    }
}

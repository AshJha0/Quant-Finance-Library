package com.quantfinlib.regulatory;

import com.quantfinlib.core.Side;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution-quality analytics of the kind a MiFID II best-execution review
 * starts from: slippage versus arrival mid (simple average per order and
 * quantity-weighted), latency-to-fill distribution, fraction executed at or
 * better than arrival, and per-venue slippage. It does not produce RTS 27
 * or RTS 28 reports. {@link #implementationShortfall} decomposes one
 * order's cost against its decision price (Perold 1988, J. Portfolio
 * Management 14(3), 4-9).
 *
 * <p>Sign convention: slippage and shortfall are costs, positive when the
 * execution was worse than the benchmark for the order's side.</p>
 */
public final class BestExecutionAnalyzer {
    /** Creates an instance; the explicit form of the default constructor. */
    public BestExecutionAnalyzer() {
    }


    /** One parent order outcome. Unfilled orders: {@code filled=false}, price/latency ignored. */
    public record OrderOutcome(String orderId, String venue, Side side, long quantity,
                               double arrivalMid, double executionPrice,
                               long latencyToFillNanos, boolean filled) {
    }

    /**
     * Best-execution summary.
     *
     * @param avgSlippageBps          simple mean over filled orders (NaN when none filled)
     * @param quantityWeightedSlippageBps notional-weighted mean over filled orders
     *                                    (NaN when none filled)
     */
    public record BestExecutionReport(
            int totalOrders,
            double fillRate,
            double avgSlippageBps,
            double quantityWeightedSlippageBps,
            double medianLatencyToFillMillis,
            double atOrBetterThanArrivalPct,
            Map<String, Double> avgSlippageBpsByVenue) {
        public BestExecutionReport {
            avgSlippageBpsByVenue = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(avgSlippageBpsByVenue));
        }
    }

    private final List<OrderOutcome> outcomes = new ArrayList<>();

    public BestExecutionAnalyzer add(OrderOutcome outcome) {
        outcomes.add(outcome);
        return this;
    }

    public BestExecutionReport report() {
        if (outcomes.isEmpty()) {
            throw new IllegalStateException("no order outcomes recorded");
        }
        int filled = 0;
        double slippageSum = 0;
        double weightedSlip = 0;
        double notional = 0;
        int atOrBetter = 0;
        List<Double> latencies = new ArrayList<>();
        Map<String, double[]> venueAgg = new LinkedHashMap<>();   // venue -> [sum, count]

        for (OrderOutcome o : outcomes) {
            if (!o.filled()) {
                continue;
            }
            filled++;
            double slip = o.side().sign() * (o.executionPrice() - o.arrivalMid()) / o.arrivalMid() * 1e4;
            slippageSum += slip;
            double n = (double) o.quantity() * o.arrivalMid();
            weightedSlip += slip * n;
            notional += n;
            if (slip <= 0) {
                atOrBetter++;
            }
            latencies.add(o.latencyToFillNanos() / 1e6);
            venueAgg.computeIfAbsent(o.venue(), k -> new double[2]);
            double[] agg = venueAgg.get(o.venue());
            agg[0] += slip;
            agg[1]++;
        }

        Map<String, Double> byVenue = new LinkedHashMap<>();
        venueAgg.forEach((venue, agg) -> byVenue.put(venue, agg[0] / agg[1]));

        return new BestExecutionReport(
                outcomes.size(),
                (double) filled / outcomes.size(),
                filled == 0 ? Double.NaN : slippageSum / filled,
                notional == 0 ? Double.NaN : weightedSlip / notional,
                median(latencies),
                filled == 0 ? Double.NaN : (double) atOrBetter / filled,
                byVenue);
    }

    /**
     * Perold (1988) implementation shortfall of one order, in currency:
     * <pre>  delay       = side · (arrival − decision) · executedQty
     *  execution   = side · Σ q_i · (p_i − arrival)
     *  opportunity = side · (close − decision) · unfilledQty
     *  total       = delay + execution + opportunity</pre>
     * with side +1 for a buy and −1 for a sell. Total equals the paper
     * portfolio's P&amp;L (full size at the decision price) minus the real one.
     */
    public record ShortfallDecomposition(double delayCost, double executionCost,
                                         double opportunityCost, double total) {
    }

    /**
     * Decomposes implementation shortfall into its components.
     *
     * @param fillQty    executed quantities (positive)
     * @param fillPrice  matching execution prices
     * @param unfilledQty quantity of the order never executed, &ge; 0
     * @param closePrice price at which the unfilled part is marked
     */
    public static ShortfallDecomposition implementationShortfall(Side side, double decisionPrice,
                                                                 double arrivalPrice,
                                                                 double[] fillQty, double[] fillPrice,
                                                                 double unfilledQty,
                                                                 double closePrice) {
        if (fillQty.length != fillPrice.length) {
            throw new IllegalArgumentException("fill quantities and prices must align");
        }
        if (!(unfilledQty >= 0) || !Double.isFinite(decisionPrice) || !Double.isFinite(arrivalPrice)
                || !Double.isFinite(closePrice)) {
            throw new IllegalArgumentException("prices must be finite and unfilledQty >= 0");
        }
        int sign = side.sign();
        double executed = 0;
        double execution = 0;
        for (int i = 0; i < fillQty.length; i++) {
            if (!(fillQty[i] > 0) || !Double.isFinite(fillPrice[i])) {
                throw new IllegalArgumentException("fill " + i + " must have qty > 0 and a finite price");
            }
            executed += fillQty[i];
            execution += fillQty[i] * (fillPrice[i] - arrivalPrice);
        }
        double delay = sign * (arrivalPrice - decisionPrice) * executed;
        double exec = sign * execution;
        double opportunity = sign * (closePrice - decisionPrice) * unfilledQty;
        return new ShortfallDecomposition(delay, exec, opportunity, delay + exec + opportunity);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        return com.quantfinlib.stats.Stats.median(values.stream().mapToDouble(Double::doubleValue).toArray());
    }
}

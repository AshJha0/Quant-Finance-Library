package com.quantfinlib.regulatory;

import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiFID II-style best execution analytics (RTS 27/28 spirit): slippage versus
 * arrival mid, latency-to-fill distribution, fraction executed at or better
 * than arrival, and per-venue slippage breakdown.
 */
public final class BestExecutionAnalyzer {

    /** One parent order outcome. Unfilled orders: {@code filled=false}, price/latency ignored. */
    public record OrderOutcome(String orderId, String venue, Side side, long quantity,
                               double arrivalMid, double executionPrice,
                               long latencyToFillNanos, boolean filled) {
    }

    public record BestExecutionReport(
            int totalOrders,
            double fillRate,
            double avgSlippageBps,
            double medianLatencyToFillMillis,
            double atOrBetterThanArrivalPct,
            Map<String, Double> avgSlippageBpsByVenue) {
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
                median(latencies),
                filled == 0 ? Double.NaN : (double) atOrBetter / filled,
                byVenue);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }
}

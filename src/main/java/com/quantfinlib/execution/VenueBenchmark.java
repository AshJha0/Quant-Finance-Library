package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Venue benchmarking from execution outcomes: fill rate, latency-to-fill,
 * effective spread paid, and post-trade markout (adverse selection) per
 * venue, ranked by execution quality.
 */
public final class VenueBenchmark {

    /**
     * One routing outcome. For unfilled attempts set {@code filled=false};
     * price/mid/latency fields are then ignored.
     */
    public record Sample(String venue, Side side, double price, long quantity,
                         double midAtExec, double midAfterHorizon, long latencyToFillNanos,
                         boolean filled) {
    }

    public record VenueStats(String venue, int attempts, double fillRate,
                             double avgEffectiveSpreadBps, double avgMarkoutBps,
                             double avgLatencyToFillMillis) {
    }

    private final List<Sample> samples = new ArrayList<>();

    public VenueBenchmark add(Sample sample) {
        samples.add(sample);
        return this;
    }

    /**
     * Per-venue statistics, best execution quality first (lowest effective
     * spread net of markout). Markout is signed so positive = the price moved
     * in our favor after the fill (negative = adverse selection).
     */
    public List<VenueStats> rank() {
        Map<String, List<Sample>> byVenue = new LinkedHashMap<>();
        for (Sample s : samples) {
            byVenue.computeIfAbsent(s.venue(), k -> new ArrayList<>()).add(s);
        }
        List<VenueStats> out = new ArrayList<>();
        byVenue.forEach((venue, list) -> {
            int attempts = list.size();
            int fills = 0;
            double effSpread = 0, markout = 0, latency = 0;
            for (Sample s : list) {
                if (!s.filled()) {
                    continue;
                }
                fills++;
                int sign = s.side().sign();
                effSpread += 2.0 * sign * (s.price() - s.midAtExec()) / s.midAtExec() * 1e4;
                markout += sign * (s.midAfterHorizon() - s.price()) / s.price() * 1e4;
                latency += s.latencyToFillNanos() / 1e6;
            }
            out.add(new VenueStats(venue, attempts,
                    (double) fills / attempts,
                    fills == 0 ? Double.NaN : effSpread / fills,
                    fills == 0 ? Double.NaN : markout / fills,
                    fills == 0 ? Double.NaN : latency / fills));
        });
        out.sort(Comparator.comparingDouble(v ->
                Double.isNaN(v.avgEffectiveSpreadBps()) ? Double.MAX_VALUE
                        : v.avgEffectiveSpreadBps() - v.avgMarkoutBps()));
        return out;
    }
}

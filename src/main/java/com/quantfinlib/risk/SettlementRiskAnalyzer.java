package com.quantfinlib.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settlement (Herstatt) risk: the exposure created when you pay away one
 * currency before receiving the other leg. Named for Bankhaus Herstatt (1974),
 * which was closed after receiving DEM but before paying out USD.
 */
public final class SettlementRiskAnalyzer {

    /** One settlement instruction pair: we pay one leg and receive the other. */
    public record SettlementLeg(String counterparty,
                                String payCurrency, double payAmount, long payTimeMillis,
                                String receiveCurrency, double receiveAmount, long receiveTimeMillis) {

        /** True when we pay before we receive — the Herstatt window. */
        public boolean hasHerstattWindow() {
            return payTimeMillis < receiveTimeMillis;
        }
    }

    private SettlementRiskAnalyzer() {
    }

    /**
     * Total at-risk receive amounts per counterparty: sum of legs where
     * payment goes out before the countervalue arrives.
     */
    public static Map<String, Double> herstattExposure(List<SettlementLeg> legs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (SettlementLeg leg : legs) {
            if (leg.hasHerstattWindow()) {
                out.merge(leg.counterparty(), leg.receiveAmount(), Double::sum);
            }
        }
        return out;
    }

    /**
     * Peak intraday settlement exposure to one counterparty: the maximum
     * total receive-amount outstanding (paid but not yet received) at any
     * point in time.
     */
    public static double peakExposure(List<SettlementLeg> legs, String counterparty) {
        record Event(long time, double delta) {
        }
        List<Event> events = new ArrayList<>();
        for (SettlementLeg leg : legs) {
            if (!leg.counterparty().equals(counterparty) || !leg.hasHerstattWindow()) {
                continue;
            }
            events.add(new Event(leg.payTimeMillis(), leg.receiveAmount()));
            events.add(new Event(leg.receiveTimeMillis(), -leg.receiveAmount()));
        }
        // At equal timestamps apply receipts (negative deltas) before payments.
        events.sort(java.util.Comparator.comparingLong(Event::time)
                .thenComparingDouble(Event::delta));
        double outstanding = 0, peak = 0;
        for (Event e : events) {
            outstanding += e.delta();
            peak = Math.max(peak, outstanding);
        }
        return peak;
    }
}

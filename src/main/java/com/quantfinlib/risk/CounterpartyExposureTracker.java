package com.quantfinlib.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counterparty credit exposure modeling with netting:
 * <ul>
 *   <li><b>Current exposure</b> — max(0, net mark-to-market) per netting set.</li>
 *   <li><b>Potential future exposure</b> — notional add-ons by tenor bucket
 *       (BIS current-exposure-method style FX factors: &lt;1y 1%, 1–5y 5%,
 *       &gt;5y 7.5%).</li>
 * </ul>
 */
public final class CounterpartyExposureTracker {

    public record CounterpartyTrade(String counterparty, String product, double notional,
                                    double markToMarket, double tenorYears) {
    }

    private final Map<String, List<CounterpartyTrade>> byCounterparty = new LinkedHashMap<>();

    public CounterpartyExposureTracker addTrade(CounterpartyTrade trade) {
        byCounterparty.computeIfAbsent(trade.counterparty(), k -> new ArrayList<>()).add(trade);
        return this;
    }

    /** Net current exposure (MTM netted within the counterparty netting set, floored at 0). */
    public double currentExposure(String counterparty) {
        double net = 0;
        for (CounterpartyTrade t : trades(counterparty)) {
            net += t.markToMarket();
        }
        return Math.max(0, net);
    }

    /**
     * Potential future exposure with the CEM net-to-gross adjustment:
     * {@code PFE = (0.4 + 0.6 * NGR) * sum |notional| * addOn}, where
     * NGR = net current exposure / gross positive MTM. A well-hedged
     * netting set (offsetting MTMs) earns up to a 60% add-on reduction —
     * the gross add-on alone materially over-states exposure for exactly
     * the books that hedge, which is what the netting agreement is FOR.
     * NGR is 1 (no relief) when there is no positive MTM to net against.
     */
    public double potentialFutureExposure(String counterparty) {
        double grossAddOn = 0;
        double grossPositiveMtm = 0;
        for (CounterpartyTrade t : trades(counterparty)) {
            grossAddOn += Math.abs(t.notional()) * addOnFactor(t.tenorYears());
            grossPositiveMtm += Math.max(0, t.markToMarket());
        }
        double ngr = grossPositiveMtm > 0
                ? currentExposure(counterparty) / grossPositiveMtm
                : 1.0;
        return (0.4 + 0.6 * ngr) * grossAddOn;
    }

    /** Total exposure = current + potential future. */
    public double totalExposure(String counterparty) {
        return currentExposure(counterparty) + potentialFutureExposure(counterparty);
    }

    /** Total exposure per counterparty (insertion order preserved). */
    public Map<String, Double> allExposures() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String cp : byCounterparty.keySet()) {
            out.put(cp, totalExposure(cp));
        }
        return out;
    }

    /** BIS CEM-style FX add-on factor by residual tenor. */
    public static double addOnFactor(double tenorYears) {
        if (tenorYears < 1) {
            return 0.01;
        }
        if (tenorYears <= 5) {
            return 0.05;
        }
        return 0.075;
    }

    private List<CounterpartyTrade> trades(String counterparty) {
        return byCounterparty.getOrDefault(counterparty, List.of());
    }
}

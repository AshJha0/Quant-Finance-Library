package com.quantfinlib.risk;

import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-trade risk gate: validates every order against configured limits before
 * it reaches the market — order size, notional, resulting position, price
 * collar versus a reference mid, restricted symbols, and counterparty credit
 * headroom. Designed to be evaluated synchronously on the order path.
 */
public final class PreTradeLimitChecker {

    public record OrderRequest(String symbol, Side side, long quantity, double price,
                               String counterparty) {
    }

    public record CheckResult(boolean approved, List<String> violations) {
    }

    private long maxOrderQuantity = Long.MAX_VALUE;
    private double maxOrderNotional = Double.MAX_VALUE;
    private long maxPositionQuantity = Long.MAX_VALUE;
    private double priceCollarPct = Double.MAX_VALUE;
    private final Map<String, Double> counterpartyLimits = new HashMap<>();
    private final Set<String> restrictedSymbols = new HashSet<>();

    public PreTradeLimitChecker maxOrderQuantity(long qty) {
        this.maxOrderQuantity = qty;
        return this;
    }

    public PreTradeLimitChecker maxOrderNotional(double notional) {
        this.maxOrderNotional = notional;
        return this;
    }

    /** Cap on |position| after the order would fully fill. */
    public PreTradeLimitChecker maxPositionQuantity(long qty) {
        this.maxPositionQuantity = qty;
        return this;
    }

    /** Reject prices more than this fraction away from the reference mid (fat-finger guard). */
    public PreTradeLimitChecker priceCollarPct(double pct) {
        this.priceCollarPct = pct;
        return this;
    }

    public PreTradeLimitChecker counterpartyLimit(String counterparty, double limit) {
        counterpartyLimits.put(counterparty, limit);
        return this;
    }

    public PreTradeLimitChecker restrictSymbol(String symbol) {
        restrictedSymbols.add(symbol);
        return this;
    }

    /**
     * @param referenceMid          current market mid for the collar check (NaN skips it)
     * @param currentPositionQty    signed current position in the symbol
     * @param counterpartyExposure  current total exposure to the order's counterparty
     */
    public CheckResult check(OrderRequest order, double referenceMid,
                             long currentPositionQty, double counterpartyExposure) {
        List<String> violations = new ArrayList<>();

        if (restrictedSymbols.contains(order.symbol())) {
            violations.add("RESTRICTED_SYMBOL: " + order.symbol());
        }
        if (order.quantity() <= 0) {
            violations.add("INVALID_QUANTITY: " + order.quantity());
        }
        if (order.quantity() > maxOrderQuantity) {
            violations.add("MAX_ORDER_QTY: " + order.quantity() + " > " + maxOrderQuantity);
        }
        double notional = order.quantity() * order.price();
        if (notional > maxOrderNotional) {
            violations.add("MAX_NOTIONAL: " + notional + " > " + maxOrderNotional);
        }
        long newPosition = currentPositionQty + order.side().sign() * order.quantity();
        // Risk-reducing orders pass even from an over-limit position — a
        // gate that rejects the trade that shrinks the breach wedges the
        // book at its worst point. But "reducing" means smaller AND the
        // same sign: flipping through zero to an over-cap position on the
        // other side is a new breach, not a hedge (same rule as
        // HftRiskGate).
        if (Math.abs(newPosition) > maxPositionQuantity
                && (Math.abs(newPosition) >= Math.abs(currentPositionQty)
                        || (newPosition ^ currentPositionQty) < 0)) {
            violations.add("MAX_POSITION: |" + newPosition + "| > " + maxPositionQuantity);
        }
        if (!Double.isNaN(referenceMid) && referenceMid > 0 && priceCollarPct != Double.MAX_VALUE) {
            double deviation = Math.abs(order.price() - referenceMid) / referenceMid;
            if (deviation > priceCollarPct) {
                violations.add(String.format(java.util.Locale.ROOT,
                        "PRICE_COLLAR: %.4f%% > %.4f%%", deviation * 100, priceCollarPct * 100));
            }
        }
        Double cpLimit = counterpartyLimits.get(order.counterparty());
        if (cpLimit != null && counterpartyExposure + notional > cpLimit) {
            violations.add("COUNTERPARTY_LIMIT: " + order.counterparty()
                    + " exposure " + (counterpartyExposure + notional) + " > " + cpLimit);
        }
        return new CheckResult(violations.isEmpty(), violations);
    }
}

package com.quantfinlib.risk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-asset portfolio of positions with live price updates.
 * Thread-safe: prices may be updated concurrently from a market data feed
 * while analytics read valuations.
 */
public final class Portfolio {

    public record Position(String symbol, double quantity, double price) {
        public double value() {
            return quantity * price;
        }
    }

    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    public Portfolio addPosition(String symbol, double quantity, double price) {
        positions.merge(symbol, new Position(symbol, quantity, price),
                (old, add) -> new Position(symbol, old.quantity() + add.quantity(), add.price()));
        return this;
    }

    /** Updates the mark price of an existing position (no-op for unknown symbols). */
    public void updatePrice(String symbol, double price) {
        positions.computeIfPresent(symbol, (k, p) -> new Position(k, p.quantity(), price));
    }

    public double totalValue() {
        double v = 0;
        for (Position p : positions.values()) {
            v += p.value();
        }
        return v;
    }

    /** Current weights by symbol (insertion order not guaranteed). */
    public Map<String, Double> weights() {
        double total = totalValue();
        Map<String, Double> w = new LinkedHashMap<>();
        for (Position p : positions.values()) {
            w.put(p.symbol(), total == 0 ? 0 : p.value() / total);
        }
        return w;
    }

    public Position position(String symbol) {
        return positions.get(symbol);
    }

    public Collection<Position> positions() {
        return positions.values();
    }

    public int size() {
        return positions.size();
    }
}

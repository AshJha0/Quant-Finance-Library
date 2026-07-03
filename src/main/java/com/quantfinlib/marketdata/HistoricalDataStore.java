package com.quantfinlib.marketdata;

import com.quantfinlib.core.Bar;
import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory historical market data store keyed by symbol. Bars may be appended
 * incrementally (e.g. from a live aggregation) and materialized into an
 * immutable {@link BarSeries} for analytics.
 */
public final class HistoricalDataStore {

    private final Map<String, List<Bar>> bars = new ConcurrentHashMap<>();

    public void addBar(String symbol, Bar bar) {
        bars.computeIfAbsent(symbol, k -> new ArrayList<>()).add(bar);
    }

    public void putSeries(BarSeries series) {
        List<Bar> list = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            list.add(series.bar(i));
        }
        bars.put(series.symbol(), list);
    }

    public BarSeries series(String symbol) {
        List<Bar> list = bars.get(symbol);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("no data for symbol: " + symbol);
        }
        return BarSeries.fromBars(symbol, list);
    }

    public boolean contains(String symbol) {
        return bars.containsKey(symbol);
    }

    public Set<String> symbols() {
        return bars.keySet();
    }

    public int barCount(String symbol) {
        List<Bar> list = bars.get(symbol);
        return list == null ? 0 : list.size();
    }
}

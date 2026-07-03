package com.quantfinlib.screener;

import com.quantfinlib.core.BarSeries;

/** One screening candidate: symbol, price history, and fundamentals. */
public record StockSnapshot(String symbol, BarSeries series, Fundamentals fundamentals) {

    public double lastClose() {
        return series.lastClose();
    }
}

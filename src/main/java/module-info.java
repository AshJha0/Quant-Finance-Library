/**
 * Quant-Finance-Library: pricing, market risk, alpha research and
 * backtesting, plus a reference low-latency trading lane.
 *
 * <p>Every package that was public in 2.0 is exported, the reference
 * packages ({@code fix}, {@code sbe}, {@code feed}, {@code marketdata},
 * {@code orderbook}, {@code trading}, {@code util}) included; their
 * stability tier is set by {@code docs/STABILITY.md}, not by this
 * descriptor. {@code com.quantfinlib.examples} is compiled with the library
 * but left out of the library jar and is not exported; it ships as the
 * {@code examples} classifier jar.</p>
 *
 * <p>{@code jdk.httpserver} backs {@code trading.TradingDashboard} and
 * {@code java.net.http} the HTTP bar fetcher and WebSocket feed. JSpecify
 * is a compile-time dependency only ({@code requires static}).</p>
 */
module com.quantfinlib {
    requires java.net.http;
    requires jdk.httpserver;
    requires static org.jspecify;

    exports com.quantfinlib.alpha;
    exports com.quantfinlib.backtest;
    exports com.quantfinlib.backtest.portfolio;
    exports com.quantfinlib.backtest.strategies;
    exports com.quantfinlib.backtest.tick;
    exports com.quantfinlib.backtest.validation;
    exports com.quantfinlib.cli;
    exports com.quantfinlib.commodities;
    exports com.quantfinlib.core;
    exports com.quantfinlib.crb;
    exports com.quantfinlib.credit;
    exports com.quantfinlib.data;
    exports com.quantfinlib.dsl;
    exports com.quantfinlib.execution;
    exports com.quantfinlib.feed;
    exports com.quantfinlib.fix;
    exports com.quantfinlib.fx;
    exports com.quantfinlib.hedging;
    exports com.quantfinlib.indicators;
    exports com.quantfinlib.marketdata;
    exports com.quantfinlib.markets;
    exports com.quantfinlib.microstructure;
    exports com.quantfinlib.ml;
    exports com.quantfinlib.optimization;
    exports com.quantfinlib.orderbook;
    exports com.quantfinlib.persist;
    exports com.quantfinlib.pricing;
    exports com.quantfinlib.rates;
    exports com.quantfinlib.regulatory;
    exports com.quantfinlib.report;
    exports com.quantfinlib.rfq;
    exports com.quantfinlib.risk;
    exports com.quantfinlib.sbe;
    exports com.quantfinlib.screener;
    exports com.quantfinlib.simulation;
    exports com.quantfinlib.stats;
    exports com.quantfinlib.trading;
    exports com.quantfinlib.util;
    exports com.quantfinlib.volatility;
}

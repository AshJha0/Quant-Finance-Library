/**
 * Quant-Finance-Library, stable tier: pricing, rates, credit, market risk,
 * alpha research, backtesting and execution analytics.
 *
 * <p>Every exported package carries the stable-tier promise of
 * {@code docs/STABILITY.md}: no binary-incompatible change within a major
 * version, checked by japicmp. The reference-tier low-latency trading lane
 * ships separately as module {@code com.quantfinlib.lowlatency} (artifact
 * {@code quant-finance-library-lowlatency}), which requires this module
 * transitively.</p>
 *
 * <p>{@code com.quantfinlib.internal} holds shared implementation helpers
 * and is exported only to the low-latency module; it is not API.
 * {@code java.net.http} backs {@code data.HttpBarFetcher}. JSpecify is a
 * compile-time dependency only ({@code requires static}).</p>
 */
// "module": the qualified export names com.quantfinlib.lowlatency, which is
// compiled after this module, so javac warns that it is not found yet.
@SuppressWarnings("module")
module com.quantfinlib {
    requires transitive java.net.http;
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
    exports com.quantfinlib.fx;
    exports com.quantfinlib.hedging;
    exports com.quantfinlib.indicators;
    exports com.quantfinlib.marketdata.tick;
    exports com.quantfinlib.markets;
    exports com.quantfinlib.microstructure;
    exports com.quantfinlib.ml;
    exports com.quantfinlib.optimization;
    exports com.quantfinlib.persist;
    exports com.quantfinlib.pricing;
    exports com.quantfinlib.rates;
    exports com.quantfinlib.regulatory;
    exports com.quantfinlib.report;
    exports com.quantfinlib.rfq;
    exports com.quantfinlib.risk;
    exports com.quantfinlib.screener;
    exports com.quantfinlib.simulation;
    exports com.quantfinlib.stats;
    exports com.quantfinlib.volatility;

    exports com.quantfinlib.internal to com.quantfinlib.lowlatency;
}

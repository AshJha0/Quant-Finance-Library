/**
 * Quant-Finance-Library, stable tier: pricing, rates, credit, market risk,
 * alpha research, backtesting and execution analytics.
 *
 * <p>Every exported package carries the stable-tier promise of
 * {@code docs/STABILITY.md}: no binary-incompatible change within a major
 * version, checked by japicmp. Since 4.0 the module requires nothing but
 * {@code java.base} (and JSpecify at compile time only, {@code requires
 * static}): file and HTTP I/O is in {@code com.quantfinlib.io}, the strategy
 * DSL in {@code com.quantfinlib.research}, and the reference-tier trading
 * lane in {@code com.quantfinlib.lowlatency}. Each of those requires this
 * module transitively; see docs/MIGRATION_4.0.md.</p>
 *
 * <p>{@code com.quantfinlib.internal} holds shared implementation helpers
 * and is exported only to the low-latency module; it is not API.</p>
 */
// "module": the qualified export names com.quantfinlib.lowlatency, which is
// compiled after this module, so javac warns that it is not found yet.
@SuppressWarnings("module")
module com.quantfinlib {
    requires static org.jspecify;

    exports com.quantfinlib.alpha;
    exports com.quantfinlib.backtest;
    exports com.quantfinlib.backtest.portfolio;
    exports com.quantfinlib.backtest.validation;
    exports com.quantfinlib.commodities;
    exports com.quantfinlib.core;
    exports com.quantfinlib.crb;
    exports com.quantfinlib.credit;
    exports com.quantfinlib.data;
    exports com.quantfinlib.execution;
    exports com.quantfinlib.fx;
    exports com.quantfinlib.hedging;
    exports com.quantfinlib.indicators;
    exports com.quantfinlib.markets;
    exports com.quantfinlib.microstructure;
    exports com.quantfinlib.ml;
    exports com.quantfinlib.optimization;
    exports com.quantfinlib.persist;
    exports com.quantfinlib.pricing;
    exports com.quantfinlib.rates;
    exports com.quantfinlib.regulatory;
    exports com.quantfinlib.risk;
    exports com.quantfinlib.screener;
    exports com.quantfinlib.simulation;
    exports com.quantfinlib.stats;
    exports com.quantfinlib.volatility;

    exports com.quantfinlib.internal to com.quantfinlib.lowlatency;
}

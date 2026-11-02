/**
 * Quant-Finance-Library, reference tier: the low-latency trading lane
 * (order books, FIX and SBE codecs, feed handlers, market-data bus, trading
 * gateways and risk gates, entitlements, regulatory transaction reporting)
 * and the latency and threading helpers it uses.
 *
 * <p>The tier rules of {@code docs/STABILITY.md} apply: these packages are
 * tested reference implementations, not certified venue or regulator
 * connectivity, and their wire and file formats may change in a minor
 * release. The module requires {@code com.quantfinlib} transitively, so a
 * lane user declares only {@code requires com.quantfinlib.lowlatency}.</p>
 *
 * <p>Since 4.0 the module also holds {@code rfq}, {@code marketdata.tick}
 * and {@code backtest.tick}, moved from the stable module, and
 * {@code marketdata.AggregatedBook}, which replaced {@code fx.AggregatedBook}.</p>
 *
 * <p>{@code com.quantfinlib.examples}, {@code com.quantfinlib.cli} and
 * {@code com.quantfinlib.backtest.strategies} are compiled here but left out
 * of the module jar and not exported; they ship as the {@code examples}
 * classifier jar, which also needs the io and research jars (the
 * {@code requires static} lines). {@code java.net.http} backs {@code feed.WebSocketFeed} and the GLEIF registry client, whose constructor takes an {@code HttpClient} (transitive; since 4.0 the stable module no longer passes it on), {@code jdk.httpserver} backs {@code trading.TradingDashboard} and
 * {@code java.xml} the FIRDS reference-data parser.</p>
 */
module com.quantfinlib.lowlatency {
    requires transitive com.quantfinlib;
    // Only the examples jar (cli, backtest.strategies, examples) uses these.
    requires static com.quantfinlib.io;
    requires static com.quantfinlib.research;
    requires transitive java.net.http;
    requires java.xml;
    requires jdk.httpserver;
    requires static org.jspecify;

    exports com.quantfinlib.entitlements;
    exports com.quantfinlib.feed;
    exports com.quantfinlib.fix;
    exports com.quantfinlib.backtest.tick;
    exports com.quantfinlib.marketdata;
    exports com.quantfinlib.marketdata.tick;
    exports com.quantfinlib.orderbook;
    exports com.quantfinlib.regreporting;
    exports com.quantfinlib.regreporting.cat;
    exports com.quantfinlib.regreporting.emir;
    exports com.quantfinlib.regreporting.registry;
    exports com.quantfinlib.rfq;
    exports com.quantfinlib.sbe;
    exports com.quantfinlib.trading;
    exports com.quantfinlib.util;
}

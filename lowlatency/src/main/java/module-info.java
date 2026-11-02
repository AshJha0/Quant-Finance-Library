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
 * <p>{@code com.quantfinlib.examples} is compiled here but left out of the
 * module jar and not exported; it ships as the {@code examples} classifier
 * jar. {@code jdk.httpserver} backs {@code trading.TradingDashboard} and
 * {@code java.xml} the FIRDS reference-data parser.</p>
 */
module com.quantfinlib.lowlatency {
    requires transitive com.quantfinlib;
    requires java.xml;
    requires jdk.httpserver;
    requires static org.jspecify;

    exports com.quantfinlib.entitlements;
    exports com.quantfinlib.feed;
    exports com.quantfinlib.fix;
    exports com.quantfinlib.marketdata;
    exports com.quantfinlib.orderbook;
    exports com.quantfinlib.regreporting;
    exports com.quantfinlib.regreporting.cat;
    exports com.quantfinlib.regreporting.emir;
    exports com.quantfinlib.regreporting.registry;
    exports com.quantfinlib.sbe;
    exports com.quantfinlib.trading;
    exports com.quantfinlib.util;
}

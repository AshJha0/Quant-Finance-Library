/**
 * Quant-Finance-Library, incubating tier: file and network I/O.
 * {@code com.quantfinlib.io} loads CSV bars and universes and fetches CSV
 * over HTTP; {@code com.quantfinlib.report} writes HTML reports and SVG
 * charts. New in 4.0; the packages kept their 3.x names.
 *
 * <p>Incubating (docs/STABILITY.md): semantic versioning applies, but a minor
 * release may change this module's API with one minor of notice. It requires
 * the stable module transitively, so a user declares only
 * {@code requires com.quantfinlib.io}. {@code java.net.http} backs
 * {@code io.HttpBarFetcher} and is required here, not by the stable module.</p>
 */
module com.quantfinlib.io {
    requires transitive com.quantfinlib;
    requires java.net.http;
    requires static org.jspecify;

    exports com.quantfinlib.io;
    exports com.quantfinlib.report;
}

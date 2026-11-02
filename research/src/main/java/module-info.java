/**
 * Quant-Finance-Library, incubating tier: research tools.
 * {@code com.quantfinlib.dsl} composes indicator rules into backtestable
 * strategies. New in 4.0; the package kept its 3.x name.
 *
 * <p>Incubating (docs/STABILITY.md): semantic versioning applies, but a minor
 * release may change this module's API with one minor of notice. It requires
 * the stable module transitively, so a user declares only
 * {@code requires com.quantfinlib.research}.</p>
 */
module com.quantfinlib.research {
    requires transitive com.quantfinlib;
    requires static org.jspecify;

    exports com.quantfinlib.dsl;
}

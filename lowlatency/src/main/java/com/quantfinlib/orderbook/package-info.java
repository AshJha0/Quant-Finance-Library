/**
 * Limit order book models: a readable research book and a zero-allocation
 * matching book. In detail:
 * {@link com.quantfinlib.orderbook.OrderBook} is the research model
 * (price-time-priority matching with cancels, partial fills, queue-position
 * queries and order-to-trade counters; readable TreeMap/object internals);
 * {@link com.quantfinlib.orderbook.HftOrderBook} is the zero-allocation
 * matching core (dense integer-tick price ladder with occupancy bitmaps,
 * pooled intrusive order nodes, primitive open-addressing id map, owner ids
 * with self-trade prevention, modify with priority rules, market-order
 * protection bands and trade sequence numbers; throughput measured by
 * {@code examples.HftBookBenchmark}, results in docs/BENCHMARKS.md).
 * {@link com.quantfinlib.orderbook.BookAnalytics} adds spread, microprice,
 * depth imbalance and non-destructive sweep simulation. Correctness is
 * checked by a 100k-operation model-based fuzz test on the reference book
 * and by an equivalence test that drives both books with identical random
 * operation streams, so the readable book is the executable specification
 * of the fast one.
 *
 * <p>Since 3.0 this package is in the artifact
 * {@code quant-finance-library-lowlatency} (module {@code com.quantfinlib.lowlatency});
 * the package name did not change, so a 2.x user changes the dependency, not the
 * imports. See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.orderbook;

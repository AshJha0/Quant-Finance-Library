/**
 * Limit order book modelling, in two lanes:
 * {@link com.quantfinlib.orderbook.OrderBook} is the research model
 * (price-time-priority matching with cancels, partial fills, queue-position
 * queries and order-to-trade counters; readable TreeMap/object internals);
 * {@link com.quantfinlib.orderbook.HftOrderBook} is the zero-allocation
 * matching core (dense integer-tick price ladder with occupancy bitmaps,
 * pooled intrusive order nodes, primitive open-addressing id map, owner ids
 * with self-trade prevention, modify with priority rules, market-order
 * protection bands and trade sequence numbers; throughput measured by
 * {@code examples.HftBookBenchmark}).
 * {@link com.quantfinlib.orderbook.BookAnalytics} adds spread, microprice,
 * depth imbalance and non-destructive sweep simulation. Correctness is
 * checked by a 100k-operation model-based fuzz test on the reference book
 * and by an equivalence test that drives both books with identical random
 * operation streams, so the readable book is the executable specification
 * of the fast one.
 */
package com.quantfinlib.orderbook;

/**
 * Limit order book modeling, in two deliberate lanes:
 * {@link com.quantfinlib.orderbook.OrderBook} is the research-grade model
 * (price-time-priority matching with cancels, partial fills, queue-position
 * queries and order-to-trade counters — readable TreeMap/object internals);
 * {@link com.quantfinlib.orderbook.HftOrderBook} is the venue-grade core
 * (dense integer-tick price ladder with occupancy bitmaps, pooled intrusive
 * order nodes, primitive open-addressing id map, zero allocation —
 * ~204 ns/op, 10M+ fills/sec measured by {@code HftBookBenchmark}).
 * {@link com.quantfinlib.orderbook.BookAnalytics} adds spread, microprice,
 * depth imbalance and non-destructive sweep simulation. Correctness is
 * enforced by a 100k-operation model-based fuzz test on the reference book,
 * and by an equivalence test that drives BOTH books with identical random
 * operation streams — the readable book is the executable specification of
 * the fast one.
 */
package com.quantfinlib.orderbook;

/**
 * Limit order book modeling: {@link com.quantfinlib.orderbook.OrderBook}
 * (price-time-priority matching with cancels, partial fills, queue-position
 * queries and order-to-trade counters) and
 * {@link com.quantfinlib.orderbook.BookAnalytics} (spread, microprice, depth
 * imbalance, non-destructive sweep simulation). A research/microstructure
 * model — correctness is enforced by a 100k-operation model-based fuzz test;
 * for a production matching engine you would use flat price-ladder arrays.
 */
package com.quantfinlib.orderbook;

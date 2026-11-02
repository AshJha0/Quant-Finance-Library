package com.quantfinlib.marketdata;

/**
 * All-primitive tick callback for the HFT hot path: no event object, no
 * boxing, no allocation. Invoked on the bus consumer thread — implementations
 * must be fast and must not block.
 */
@FunctionalInterface
public interface TickListener {

    void onTick(int symbolId, double price, double size, long timestampNanos);
}

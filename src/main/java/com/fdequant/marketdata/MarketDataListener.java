package com.fdequant.marketdata;

/** Callback for market data events. Invoked on the processor's consumer thread. */
@FunctionalInterface
public interface MarketDataListener {

    void onEvent(MarketDataEvent event);
}

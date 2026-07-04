package com.quantfinlib.backtest.tick;

/**
 * An event-driven strategy for the {@link TickBacktester}: sees every tick in
 * sequence and trades through the {@link TickTradingContext}. Symbol ids
 * arrive with the ticks (defined by the tick file), so resolve instruments
 * lazily in {@code onTick} rather than in {@code init}.
 */
public interface TickStrategy {

    String name();

    void init(TickTradingContext context);

    void onTick(int symbolId, double price, double size, long timestampNanos);
}

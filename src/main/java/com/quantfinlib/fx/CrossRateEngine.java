package com.quantfinlib.fx;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.TickListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming cross-rate derivation on the HFT tick path: maintains synthetic
 * crosses (EURJPY from EURUSD × USDJPY, EURGBP from EURUSD ÷ GBPUSD) live
 * from their leg ticks, with zero allocation per tick.
 *
 * <p>Two compositions cover every triangulation:</p>
 * <ul>
 *   <li>{@link Op#MULTIPLY} — legs share the middle currency on opposite
 *       sides: A/B × B/C = A/C (EURUSD × USDJPY = EURJPY);</li>
 *   <li>{@link Op#DIVIDE} — legs share the quote currency: A/C ÷ B/C = A/B
 *       (EURUSD ÷ GBPUSD = EURGBP).</li>
 * </ul>
 *
 * <p><b>Threading.</b> Cross updates are delivered synchronously on the bus
 * consumer thread through a caller-supplied {@link TickListener} — they are
 * deliberately <em>not</em> re-published onto the bus, whose ring is
 * single-producer (the feed thread); publishing from the consumer thread
 * would break that contract. Chain downstream logic (streaming indicators, a
 * quoter) off the listener exactly as off a native symbol. The cross symbol
 * is still registered on the bus so it owns a dense id, usable with
 * {@code SymbolRegistry} lookups and the order gateway.</p>
 *
 * <p>Setup ({@link #addCross}) allocates; the per-tick path touches only
 * primitive arrays captured at setup. Static one-shot math (mid from two leg
 * quotes) lives in {@code pricing.TriangularArbitrage}; this class is its
 * streaming counterpart.</p>
 */
public final class CrossRateEngine {

    /** How the two leg prices compose into the cross. */
    public enum Op {
        /** A/B × B/C = A/C (shared middle currency). */
        MULTIPLY,
        /** A/C ÷ B/C = A/B (shared quote currency). */
        DIVIDE
    }

    private final HftMarketDataBus bus;
    private final List<String> crossSymbols = new ArrayList<>();

    public CrossRateEngine(HftMarketDataBus bus) {
        this.bus = bus;
    }

    /**
     * Registers a synthetic cross. Both legs and the cross symbol are
     * registered on the bus; the returned dense id identifies the cross in
     * {@code listener} callbacks. Call before {@code bus.start()}.
     *
     * @param listener receives the derived cross tick (cross id, price, the
     *                 triggering leg's size, the triggering leg's timestamp)
     *                 on the bus consumer thread — must not block
     */
    public int addCross(String legA, String legB, String crossSymbol, Op op,
                        TickListener listener) {
        int idA = bus.registerSymbol(legA);
        int idB = bus.registerSymbol(legB);
        if (idA == idB) {
            throw new IllegalArgumentException("legs must differ: " + legA);
        }
        int crossId = bus.registerSymbol(crossSymbol);
        crossSymbols.add(crossSymbol);

        // Per-cross mutable state: one slot per leg, NaN until first tick.
        // Captured arrays keep the tick path free of any allocation.
        double[] legPrices = {Double.NaN, Double.NaN};
        boolean multiply = op == Op.MULTIPLY;

        bus.subscribe(idA, (symbolId, price, size, timestampNanos) -> {
            legPrices[0] = price;
            emit(legPrices, multiply, crossId, size, timestampNanos, listener);
        });
        bus.subscribe(idB, (symbolId, price, size, timestampNanos) -> {
            legPrices[1] = price;
            emit(legPrices, multiply, crossId, size, timestampNanos, listener);
        });
        return crossId;
    }

    /** Emits the cross when both legs have printed at least once. */
    private static void emit(double[] legPrices, boolean multiply, int crossId,
                             double size, long timestampNanos, TickListener listener) {
        double a = legPrices[0];
        double b = legPrices[1];
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return; // waiting for the first print on the other leg
        }
        double cross = multiply ? a * b : a / b;
        listener.onTick(crossId, cross, size, timestampNanos);
    }

    /** Symbols of all registered crosses, in registration order. */
    public List<String> crossSymbols() {
        return List.copyOf(crossSymbols);
    }
}

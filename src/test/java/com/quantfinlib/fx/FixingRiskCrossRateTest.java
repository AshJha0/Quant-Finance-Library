package com.quantfinlib.fx;

import com.quantfinlib.marketdata.HftMarketDataBus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixing-window analytics and the streaming cross-rate engine (multiply and
 * divide compositions, first-print gating, live derivation off the bus).
 */
class FixingRiskCrossRateTest {

    // ------------------------------------------------------------------
    // FixingRisk
    // ------------------------------------------------------------------

    @Test
    void windowAveragesAndSlippage() {
        double[] prices = {1.0850, 1.0852, 1.0854};
        double[] sizes = {1, 1, 2};
        assertEquals(1.0852, FixingRisk.windowTwap(prices), 1e-12);
        // VWAP tilts toward the heavy print.
        assertEquals((1.0850 + 1.0852 + 2 * 1.0854) / 4, FixingRisk.windowVwap(prices, sizes), 1e-12);
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        assertEquals(2.0, FixingRisk.slippageVsFix(eurusd, 1.0852, 1.0850), 1e-9);
    }

    @Test
    void trackingErrorFollowsTheTwapVarianceLaw() {
        // σ²T/3 law: doubling the window scales the std by √2.
        double oneX = FixingRisk.trackingErrorStd(0.0001, 5);
        double twoX = FixingRisk.trackingErrorStd(0.0001, 10);
        assertEquals(Math.sqrt(2), twoX / oneX, 1e-12);
        assertEquals(0.0001 * Math.sqrt(5.0 / 3), oneX, 1e-15);
        assertEquals(0.25, FixingRisk.participationRate(25, 100), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> FixingRisk.windowTwap(new double[0]));
        assertThrows(IllegalArgumentException.class,
                () -> FixingRisk.windowVwap(new double[]{1}, new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> FixingRisk.trackingErrorStd(0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> FixingRisk.participationRate(1, 0));
    }

    // ------------------------------------------------------------------
    // CrossRateEngine
    // ------------------------------------------------------------------

    @Test
    void multiplyCrossDerivesLiveFromBothLegs() throws Exception {
        try (HftMarketDataBus bus = new HftMarketDataBus(1024, 16, false)) {
            CrossRateEngine engine = new CrossRateEngine(bus);
            double[] lastCross = {Double.NaN};
            AtomicInteger emissions = new AtomicInteger();
            int crossId = engine.addCross("EURUSD", "USDJPY", "EURJPY",
                    CrossRateEngine.Op.MULTIPLY,
                    (symbolId, price, size, ts) -> {
                        assertEquals(crossIdOf(bus, "EURJPY"), symbolId);
                        lastCross[0] = price;
                        emissions.incrementAndGet();
                    });
            assertEquals(bus.symbolId("EURJPY"), crossId);
            bus.start();
            int eurusd = bus.symbolId("EURUSD");
            int usdjpy = bus.symbolId("USDJPY");
            // First leg alone must NOT emit (other leg has no print yet).
            bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
            awaitProcessed(bus, 1);
            assertEquals(0, emissions.get());
            // Second leg completes the triangle.
            bus.publish(usdjpy, 155.00, 1_000_000, System.nanoTime());
            awaitProcessed(bus, 2);
            awaitTrue(() -> emissions.get() == 1);
            assertEquals(1.0850 * 155.00, lastCross[0], 1e-9);
            // A leg update re-derives the cross.
            bus.publish(eurusd, 1.0900, 1_000_000, System.nanoTime());
            awaitProcessed(bus, 3);
            awaitTrue(() -> emissions.get() == 2);
            assertEquals(1.0900 * 155.00, lastCross[0], 1e-9);
        }
    }

    @Test
    void divideCrossSharesTheQuoteCurrency() throws Exception {
        try (HftMarketDataBus bus = new HftMarketDataBus(1024, 16, false)) {
            CrossRateEngine engine = new CrossRateEngine(bus);
            double[] lastCross = {Double.NaN};
            engine.addCross("EURUSD", "GBPUSD", "EURGBP", CrossRateEngine.Op.DIVIDE,
                    (symbolId, price, size, ts) -> lastCross[0] = price);
            assertEquals(java.util.List.of("EURGBP"), engine.crossSymbols());
            bus.start();
            bus.publish(bus.symbolId("EURUSD"), 1.0850, 1_000_000, System.nanoTime());
            bus.publish(bus.symbolId("GBPUSD"), 1.2700, 1_000_000, System.nanoTime());
            awaitProcessed(bus, 2);
            awaitTrue(() -> !Double.isNaN(lastCross[0]));
            assertEquals(1.0850 / 1.2700, lastCross[0], 1e-12);
        }
    }

    @Test
    void identicalLegsAreRejected() {
        try (HftMarketDataBus bus = new HftMarketDataBus(1024, 16, false)) {
            CrossRateEngine engine = new CrossRateEngine(bus);
            assertThrows(IllegalArgumentException.class,
                    () -> engine.addCross("EURUSD", "EURUSD", "X", CrossRateEngine.Op.MULTIPLY,
                            (a, b, c, d) -> {
                            }));
        }
    }

    private static int crossIdOf(HftMarketDataBus bus, String symbol) {
        return bus.symbolId(symbol);
    }

    /** Consumer-thread dispatch is async: bound the wait, never sleep blind. */
    private static void awaitProcessed(HftMarketDataBus bus, long count) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (bus.processedCount() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("bus did not process " + count + " ticks in time");
            }
            Thread.sleep(1);
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(1);
        }
    }
}

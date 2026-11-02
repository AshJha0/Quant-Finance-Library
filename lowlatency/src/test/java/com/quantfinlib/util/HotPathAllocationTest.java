package com.quantfinlib.util;

import com.quantfinlib.crb.CentralRiskBook;
import com.quantfinlib.crb.CrbAutoHedger;
import com.quantfinlib.crb.InternalizationEngine;
import com.quantfinlib.marketdata.tick.TickFileWriter;
import com.quantfinlib.feed.MoldUdp64Receiver;
import com.quantfinlib.fix.FixOrderEncoder;
import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.microstructure.Vpin;
import com.quantfinlib.core.Side;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.trading.GlobalRiskAggregator;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftRiskGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exactly-zero-byte allocation checks for the steady-state paths fixed in the
 * hot-path audit. Each block runs many iterations, so a per-event allocation
 * of even one object shows up as a non-zero count in every window.
 */
class HotPathAllocationTest {

    private static final int N = 10_000;

    private static void zero(Runnable block) {
        AllocationProbe.assertZeroAllocation(block, block, 5);
    }

    @Test
    void idleStrategiesDoNotAllocate() {
        IdleStrategy spin = IdleStrategy.busySpin();
        IdleStrategy backoff = IdleStrategy.backoff(100, 2, 1, 1);   // reset every 64 passes: never parks
        zero(() -> {
            for (int i = 0; i < N; i++) {
                spin.idle(i & 1);
                backoff.idle(0);
                if ((i & 63) == 0) {
                    backoff.idle(1);
                }
            }
        });
    }

    @Test
    void backoffSpinsThenYieldsThenParksWithDoublingPeriod() {
        IdleStrategy.Backoff b = (IdleStrategy.Backoff) IdleStrategy.backoff(2, 1, 1_000, 8_000);
        b.idle();                      // spin 1
        b.idle();                      // spin 2
        b.idle();                      // yield
        assertEquals(1_000, b.currentParkNanos());
        b.idle();                      // park 1 us
        assertEquals(2_000, b.currentParkNanos());
        b.idle();
        b.idle();
        b.idle();
        assertEquals(8_000, b.currentParkNanos(), "period capped");
        b.idle(5);                     // work resets
        assertEquals(1_000, b.currentParkNanos());
        assertThrows(IllegalArgumentException.class, () -> IdleStrategy.backoff(0, 0, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> IdleStrategy.parking(0));
    }

    @Test
    void threadPlacementRunsHookOnTheNewThread() throws InterruptedException {
        Thread[] seen = new Thread[1];
        Thread t = ThreadPlacement.daemon("placed", th -> seen[0] = th).newThread(() -> { });
        assertTrue(t.isDaemon());
        assertEquals("placed", t.getName());
        t.start();
        t.join();
        assertEquals(t, seen[0], "hook runs on the created thread before its task");
    }

    @Test
    void fixEncoderReadOutDoesNotAllocate() {
        FixOrderEncoder enc = new FixOrderEncoder("QFL", "VENUE", 4, 512).registerSymbol(0, "EURUSD");
        byte[] dst = new byte[512];
        ByteBuffer direct = ByteBuffer.allocateDirect(512);
        zero(() -> {
            for (int i = 0; i < N; i++) {
                enc.encodeLimit(i, i, 0, Side.BUY, 1_000_000, 108_505, 5, 1_800_000_000_000L);
                enc.copyInto(dst, 0);
                direct.clear();
                enc.writeTo(direct);
                if (enc.byteAt(0) != '8') {
                    throw new AssertionError("message must start with 8=FIX");
                }
            }
        });
        byte[] copy = enc.toByteArray();
        assertEquals(enc.length(), copy.length);
        for (int i = 0; i < copy.length; i++) {
            assertEquals(copy[i], dst[i]);
        }
    }

    @Test
    void moldUdpHeaderEncodingDoesNotAllocate() {
        byte[] buf = new byte[64];
        zero(() -> {
            for (int i = 0; i < N; i++) {
                MoldUdp64Receiver.encodeHeader(buf, 0, "SESSION1", i + 1L, 1);
            }
        });
        assertEquals('S', buf[0]);
        assertEquals(' ', buf[9], "session is space padded to 10 bytes");
        assertThrows(IllegalArgumentException.class,
                () -> MoldUdp64Receiver.encodeHeader(buf, 0, "ELEVENCHARS", 1, 1));
    }

    @Test
    void tickFileWriterSteadyStateDoesNotAllocate(@TempDir Path dir) throws IOException {
        try (TickFileWriter w = new TickFileWriter(dir.resolve("ticks.qflt"))) {
            w.defineSymbol(0, "EURUSD");
            Runnable block = () -> {
                try {
                    // 29 bytes per tick: 10k ticks cross several 64 KiB block flushes.
                    for (int i = 0; i < N; i++) {
                        w.write(0, 1.0850 + i * 1e-5, 1_000_000, i);
                    }
                    w.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            zero(block);
            assertTrue(w.tickCount() >= 2L * N);
        }
    }

    @Test
    void centralRiskBookBookingDoesNotAllocateAfterWarmUp() {
        CentralRiskBook book = new CentralRiskBook();
        int desk = book.deskId("cash-desk");
        int aapl = book.equityHandle("AAPL");
        int eurusd = book.fxPairHandle("EURUSD");
        zero(() -> {
            for (int i = 0; i < N; i++) {
                book.bookCashEquity(desk, aapl, 100, 190.0);
                book.bookFxSpot(desk, eurusd, 1_000_000, 1.085);
                book.bookEquityOption(desk, aapl, OptionType.CALL, 1, 100, 190, 200, 0.04, 0.0,
                        0.25, 0.5);
                // The String-keyed overloads resolve through the same handles.
                book.bookCashEquity("cash-desk", "AAPL", -100, 190.0);
            }
        });
        assertTrue(book.flowsBooked() >= 4L * N);
        assertTrue(book.desks().contains("cash-desk"));
    }

    @Test
    void crbHandlesKeepFactorOrderOfFirstBooking() {
        CentralRiskBook book = new CentralRiskBook();
        book.equityHandle("MSFT");               // a handle alone registers nothing
        book.fxPairHandle("USDJPY");
        assertEquals(0, book.factors().size());
        book.bookFxSpot("fx", "EURUSD", 1_000_000, 1.085);
        book.bookCashEquity("eq", "MSFT", 10, 400);
        assertEquals("CCY:EUR", book.factors().name(0));
        assertEquals("CCY:USD", book.factors().name(1));
        assertEquals("EQ:MSFT", book.factors().name(2));
        assertEquals(3, book.factors().size());
        book.deskId("idle-desk");                // never books: not listed
        assertEquals(java.util.Set.of("eq", "fx"), book.desks());
    }

    @Test
    void vpinAndAutoHedgerIdleCheckDoNotAllocate() {
        Vpin vpin = new Vpin(1_000, 50);
        CrbAutoHedger hedger = new CrbAutoHedger(new double[] {10_000_000}, 0.5, 0);
        double[] exposures = {1_000_000};
        double[][] cov = {{1e-4}};
        double[][] loadings = {{1}};
        double[] cost = {0.0001};
        zero(() -> {
            for (int i = 0; i < N; i++) {
                vpin.onTrade(300 + (i & 7), (i & 1) == 0);
                if (hedger.check(exposures, cov, loadings, cost, 1, i).length != 0) {
                    throw new AssertionError("inside limits: no hedge");
                }
            }
        });
        assertTrue(vpin.ready());
    }

    @Test
    void globalRiskAggregatorReadDoesNotAllocate() {
        HftRiskGate gate = new HftRiskGate(8);
        try (GlobalRiskAggregator agg = GlobalRiskAggregator.start(List.of(gate), 1e9, 0.9,
                1_000_000_000L, ThreadPlacement.daemon("agg-test"))) {
            zero(() -> {
                for (int i = 0; i < N; i++) {
                    if (agg.grossNotional() != 0) {
                        throw new AssertionError("empty gate has no exposure");
                    }
                }
            });
        }
    }

    @Test
    void internalizationPrimitivePathMatchesDecideAndDoesNotAllocate() {
        InternalizationEngine a = new InternalizationEngine(5_000_000, 0.5);
        InternalizationEngine b = new InternalizationEngine(5_000_000, 0.5);
        InternalizationEngine.Decision d = a.decide(-3_000_000, 4_000_000, 2.0);
        assertEquals(d.internalized(), b.internalize(-3_000_000, 4_000_000, 2.0), 0);
        assertEquals(d.routed(), b.lastRouted(), 0);
        assertEquals(d.improvementBps(), b.lastImprovementBps(), 0);
        zero(() -> {
            for (int i = 0; i < N; i++) {
                b.internalize((i & 1) == 0 ? 1e6 : -1e6, 250_000, 1.5);
            }
        });
    }

    @Test
    void streamingIndicatorsDoNotAllocate() {
        StreamingIndicators.Sma sma = new StreamingIndicators.Sma(20);
        StreamingIndicators.Ema ema = new StreamingIndicators.Ema(20);
        StreamingIndicators.Rsi rsi = new StreamingIndicators.Rsi(14);
        StreamingIndicators.Macd macd = new StreamingIndicators.Macd(12, 26, 9);
        StreamingIndicators.Vwap vwap = new StreamingIndicators.Vwap();
        zero(() -> {
            for (int i = 0; i < N; i++) {
                double px = 100 + (i % 17) * 0.01;
                sma.update(px);
                ema.update(px);
                rsi.update(px);
                macd.update(px);
                vwap.update(px, 100 + (i & 15));
            }
        });
        assertTrue(Double.isFinite(macd.histogram()));
    }

    @Test
    void pollingComponentsUseTheSuppliedThreadFactoryAndIdleStrategy() throws Exception {
        java.util.concurrent.atomic.AtomicInteger made = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ThreadFactory counting = task -> {
            made.incrementAndGet();
            return ThreadPlacement.daemon("custom").newThread(task);
        };
        HftOrderGateway gw = new HftOrderGateway(64, new HftRiskGate(4),
                IdleStrategy.backoff(10, 1, 1_000, 100_000), 64, null, 1_000_000_000L)
                .threadFactory(counting);
        HftMarketDataBus bus = new HftMarketDataBus(64, 4, IdleStrategy.yielding()).threadFactory(counting);
        gw.start();
        bus.start();
        java.util.concurrent.CountDownLatch tick = new java.util.concurrent.CountDownLatch(1);
        String[] consumer = new String[1];
        bus.subscribeAll((id, px, sz, ts) -> {
            consumer[0] = Thread.currentThread().getName();
            tick.countDown();
        });
        bus.publish(0, 1.0, 1.0, 1L);
        assertTrue(tick.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("custom", consumer[0]);
        assertEquals(2, made.get());
        bus.close();
        gw.close();
    }

    @Test
    void latencyRecorderRecordDoesNotAllocate() {
        LatencyRecorder r = new LatencyRecorder();
        zero(() -> {
            for (int i = 0; i < N; i++) {
                r.record(i * 37L);
            }
        });
        assertTrue(r.count() >= N);
    }
}

package com.quantfinlib.sbe;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftRiskGate;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Pipe;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SbeCodecTest {

    // ---- Header, integer prices, layout ------------------------------------

    @Test
    void tradeCarriesTheSbeHeaderAndExactIntegerPrices() {
        ByteBuffer buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        new TradeFlyweight().wrap(buffer, 16).encode(7, 108_515L, -5, 1_000_000_5L, -1, 123_456_789L);

        // Standard SBE header: blockLength, templateId, schemaId, version (uint16 LE each).
        assertEquals(32, buffer.getShort(16));
        assertEquals(1, buffer.getShort(18));
        assertEquals(0x5146, buffer.getShort(20));
        assertEquals(2, buffer.getShort(22));

        TradeFlyweight decoded = new TradeFlyweight().wrap(buffer, 16);
        assertEquals(7, decoded.symbolId());
        assertEquals(108_515L, decoded.priceMantissa());
        assertEquals(-5, decoded.priceExponent());
        assertEquals(1.08515, decoded.price(), 0.0);
        assertEquals(1_000_000.5, decoded.size(), 0.0);
        assertEquals(123_456_789L, decoded.timestampNanos());
    }

    @Test
    void orderLayoutPutsEvery64BitFieldOnAnEightByteBoundary() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        new OrderFlyweight().wrap(buffer, 0)
                .encode(42L, 3, Side.SELL, 5_000, 10_150L, -2, 999L);
        // Raw reads at the documented offsets: version 1 had these at 4, 20, 28, 36.
        assertEquals(42L, buffer.getLong(8));
        assertEquals(5_000L, buffer.getLong(16));
        assertEquals(10_150L, buffer.getLong(24));
        assertEquals(999L, buffer.getLong(32));
        assertEquals(3, buffer.getInt(40));
        assertEquals(1, buffer.get(44));
        assertEquals(-2, buffer.get(45));
        assertEquals(48, OrderFlyweight.ENCODED_LENGTH);
        assertEquals(101.5, new OrderFlyweight().wrap(buffer, 0).price(), 0.0);
    }

    @Test
    void marketOrdersCarryTheSbeNullPrice() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        OrderFlyweight order = new OrderFlyweight().wrap(buffer, 0)
                .encode(1L, 0, Side.BUY, 10, Double.NaN, -4, 5L);
        assertTrue(order.isMarket());
        assertEquals(Long.MIN_VALUE, order.priceMantissa());
        assertTrue(Double.isNaN(order.price()));
    }

    @Test
    void decimalConversionIsExactOnTheGridAndRefusesOffGridValues() {
        assertEquals(108_505L, Decimal64.toMantissa(1.08505, -5));
        assertEquals(-25L, Decimal64.toMantissa(-0.25, -2));      // negative prices round-trip
        assertEquals(12L, Decimal64.toMantissa(1200.0, 2));
        assertEquals(1.08505, Decimal64.toDouble(108_505L, -5), 0.0);
        assertEquals(0.1, Decimal64.toDouble(1L, -1), 0.0);
        assertThrows(IllegalArgumentException.class, () -> Decimal64.toMantissa(1.08505, -4));
        assertThrows(IllegalArgumentException.class,
                () -> Decimal64.toMantissa(Double.POSITIVE_INFINITY, -2));
        assertThrows(IllegalArgumentException.class, () -> Decimal64.toMantissa(1e30, -2));
    }

    @Test
    void codecsAllocateNothingSteadyState() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        TradeFlyweight trade = new TradeFlyweight().wrap(buffer, 0);
        OrderFlyweight order = new OrderFlyweight().wrap(buffer, 0);
        long allocated = minAllocatedBytes(() -> {
            long blackhole = 0;
            for (int i = 0; i < 200_000; i++) {
                trade.encode(i, 108_500L + i, -5, i, 0, i);
                blackhole += trade.priceMantissa() + trade.symbolId() + trade.timestampNanos();
                order.encode(i, i, Side.BUY, i, 108_500L + i, -5, i);
                blackhole += order.priceMantissa() + order.orderId();
            }
            return blackhole;
        });
        assertEquals(0, allocated, "codecs allocated in steady state");
    }

    // ---- Binary market data into the bus ----------------------------------

    @Test
    void binaryMarketDataFlowsIntoTheBusAcrossFragmentedWrites() throws Exception {
        Pipe pipe = Pipe.open();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 12, 8, false)) {
            int eur = bus.registerSymbol("EURUSD");
            int gbp = bus.registerSymbol("GBPUSD");
            bus.start();

            try (BinaryMarketDataClient client = new BinaryMarketDataClient(pipe.source(), bus)) {
                client.start();
                ByteBuffer wire = ByteBuffer.allocate(2 * TradeFlyweight.ENCODED_LENGTH)
                        .order(ByteOrder.LITTLE_ENDIAN);
                TradeFlyweight trade = new TradeFlyweight();
                trade.wrap(wire, 0).encode(eur, 1.0851, -4, 1_000_000, 0, 111L);
                trade.wrap(wire, TradeFlyweight.ENCODED_LENGTH)
                        .encode(gbp, 1.2703, -4, 500_000, 0, 222L);
                // Three deliberately misaligned chunks, including a split header.
                writeChunk(pipe, wire, 0, 5);
                writeChunk(pipe, wire, 5, 40);
                writeChunk(pipe, wire, 45, wire.capacity() - 45);

                awaitTrue(() -> client.messagesDecoded() == 2, "frames not decoded");
                awaitTrue(() -> bus.processedCount() == 2, "bus did not dispatch");
                assertEquals(1.0851, bus.latestPrice(eur), 0.0);
                assertEquals(1.2703, bus.latestPrice(gbp), 0.0);
                assertNull(client.failureReason());
            }
        }
    }

    @Test
    void versionOneTradeFramesAreStillDecoded() throws Exception {
        Pipe pipe = Pipe.open();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 4, false)) {
            int eur = bus.registerSymbol("EURUSD");
            bus.start();
            try (BinaryMarketDataClient client = new BinaryMarketDataClient(pipe.source(), bus)) {
                client.start();
                ByteBuffer v1 = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                v1.putInt(0, 1).putInt(4, eur).putDouble(8, 1.0851)
                        .putDouble(16, 1_000_000).putLong(24, 77L);
                writeChunk(pipe, v1, 0, 32);
                awaitTrue(() -> client.messagesDecoded() == 1, "v1 frame not decoded");
                awaitTrue(() -> bus.processedCount() == 1, "bus did not dispatch");
                assertEquals(1.0851, bus.latestPrice(eur), 0.0);
            }
        }
    }

    @Test
    void unknownTemplatesAreSkippedAndForeignSchemasStopTheStream() throws Exception {
        Pipe pipe = Pipe.open();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 4, false)) {
            int eur = bus.registerSymbol("EURUSD");
            bus.start();
            try (BinaryMarketDataClient client = new BinaryMarketDataClient(pipe.source(), bus)) {
                client.start();
                // A future template 9 with a 16-byte block, then a trade: skip, then decode.
                ByteBuffer wire = ByteBuffer.allocate(24 + TradeFlyweight.ENCODED_LENGTH)
                        .order(ByteOrder.LITTLE_ENDIAN);
                MessageHeader.write(wire, 0, 16, 9);
                new TradeFlyweight().wrap(wire, 24).encode(eur, 1.5, -1, 1, 0, 1L);
                writeChunk(pipe, wire, 0, wire.capacity());
                awaitTrue(() -> client.messagesDecoded() == 1, "trade after unknown template lost");
                assertEquals(1, client.skippedMessages());

                ByteBuffer junk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                junk.putInt(0, 99);   // reads as schema id 0
                writeChunk(pipe, junk, 0, 8);
                awaitTrue(() -> client.failureReason() != null, "corruption not detected");
                assertTrue(client.failureReason().contains("unknown schema id 0"),
                        client.failureReason());
            }
        }
    }

    @Test
    void fullBusDropsAreCountedAndRaiseTheGapFlag() throws Exception {
        Pipe pipe = Pipe.open();
        // Ring of 4 and the bus never started: the fifth publish finds it full.
        try (HftMarketDataBus bus = new HftMarketDataBus(4, 4, false)) {
            int eur = bus.registerSymbol("EURUSD");
            try (BinaryMarketDataClient client = new BinaryMarketDataClient(pipe.source(), bus)
                    .withPublishRetryNanos(0)) {
                client.start();
                ByteBuffer wire = ByteBuffer.allocate(6 * TradeFlyweight.ENCODED_LENGTH)
                        .order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 6; i++) {
                    new TradeFlyweight().wrap(wire, i * TradeFlyweight.ENCODED_LENGTH)
                            .encode(eur, 1.0 + i, 0, 1, 0, i);
                }
                writeChunk(pipe, wire, 0, wire.capacity());
                awaitTrue(() -> client.messagesDecoded() + client.droppedTicks() == 6,
                        "frames not processed");
                assertEquals(4, client.messagesDecoded());
                assertEquals(2, client.droppedTicks());
                assertTrue(client.consumeGap(), "a dropped tick must raise the gap flag");
                assertFalse(client.consumeGap(), "consumeGap clears the flag");
            }
        }
    }

    // ---- Order channel -------------------------------------------------------

    @Test
    void orderChannelReassemblesSplitFramesReadsV1AndDetectsCorruption() throws Exception {
        Pipe pipe = Pipe.open();
        List<double[]> received = new ArrayList<>();
        BinaryOrderReceiver receiver = new BinaryOrderReceiver(pipe.source(),
                (orderId, symbolId, side, qty, price, ts) -> {
                    synchronized (received) {
                        received.add(new double[]{orderId, symbolId, qty, price});
                    }
                });
        receiver.start();

        ByteBuffer frame = ByteBuffer.allocate(OrderFlyweight.ENCODED_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        new OrderFlyweight().wrap(frame, 0).encode(7L, 2, Side.BUY, 500, 101.5, -2, 42L);
        writeChunk(pipe, frame, 0, 10);
        writeChunk(pipe, frame, 10, OrderFlyweight.ENCODED_LENGTH - 10);
        awaitTrue(() -> receiver.ordersReceived() == 1, "split frame not reassembled");

        ByteBuffer v1 = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        v1.putInt(0, 2).putLong(4, 8L).putInt(12, 3).put(16, (byte) 1)
                .putLong(20, 700L).putDouble(28, 99.25).putLong(36, 43L);
        writeChunk(pipe, v1, 0, 44);
        awaitTrue(() -> receiver.ordersReceived() == 2, "v1 order not decoded");
        synchronized (received) {
            assertEquals(2, received.size(), "exactly two orders, never a duplicate");
            assertEquals(7.0, received.get(0)[0]);
            assertEquals(500.0, received.get(0)[2]);
            assertEquals(101.5, received.get(0)[3]);
            assertEquals(8.0, received.get(1)[0]);
            assertEquals(99.25, received.get(1)[3]);
        }

        ByteBuffer junk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        junk.putInt(0, 99);
        writeChunk(pipe, junk, 0, 8);
        awaitTrue(() -> receiver.failureReason() != null, "corruption not detected");
        assertTrue(receiver.failureReason().contains("unknown schema id"));
        assertEquals(2, receiver.ordersReceived(), "no orders fabricated from junk");
        receiver.close();
    }

    @Test
    void gatewayOrdersFlowOverTheBinaryChannelInOrder() throws Exception {
        Pipe pipe = Pipe.open();
        List<long[]> received = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        BinaryOrderReceiver receiver = new BinaryOrderReceiver(pipe.source(),
                (orderId, symbolId, side, qty, price, ts) -> {
                    synchronized (received) {
                        received.add(new long[]{orderId, symbolId, qty, ts});
                        prices.add(price);
                    }
                });
        receiver.start();

        HftRiskGate gate = new HftRiskGate(4).maxOrderQuantity(1_000_000);
        try (BinaryOrderPublisher publisher = new BinaryOrderPublisher(pipe.sink());
             HftOrderGateway gateway = new HftOrderGateway(256, gate, false)) {
            gateway.addOrderListener(publisher);
            gateway.start();

            long id1 = gateway.submit(0, Side.BUY, 1_000, 1.0851, 111L);
            long id2 = gateway.submit(1, Side.SELL, 2_000, 1.2703, 222L);
            assertTrue(id1 > 0 && id2 > 0);

            awaitTrue(() -> receiver.ordersReceived() == 2, "orders not received");
            synchronized (received) {
                assertEquals(id1, received.get(0)[0]);
                assertEquals(1_000, received.get(0)[2]);
                assertEquals(111L, received.get(0)[3]);
                assertEquals(1.0851, prices.get(0), 0.0);
                assertEquals(id2, received.get(1)[0]);
                assertEquals(1.2703, prices.get(1), 0.0);
            }
            assertEquals(2, publisher.ordersSent());
            assertFalse(publisher.isTripped());
            assertNull(receiver.failureReason());
        } finally {
            receiver.close();
        }
    }

    /** A venue whose TCP window is shut: accepts nothing, never blocks. */
    private static final class StalledChannel implements WritableByteChannel {
        @Override
        public int write(ByteBuffer src) {
            return 0;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void stalledVenueTripsTheBreakerInsteadOfBlockingTheVenueThread() throws Exception {
        long[] now = {0};
        LongSupplier clock = () -> now[0];
        List<String> trips = new ArrayList<>();
        BinaryOrderPublisher publisher = new BinaryOrderPublisher(new StalledChannel(),
                3 * OrderFlyweight.ENCODED_LENGTH, 1_000_000L, clock)
                .withBreakerListener(trips::add);

        for (int i = 1; i <= 3; i++) {
            publisher.onOrder(i, 0, Side.BUY, 100, 1.5, i);   // queued, not blocking
        }
        assertEquals(3, publisher.ordersSent());
        assertEquals(3 * OrderFlyweight.ENCODED_LENGTH, publisher.pendingBytes());
        assertFalse(publisher.isTripped());

        publisher.onOrder(4, 0, Side.BUY, 100, 1.5, 4);       // no room: trip
        assertTrue(publisher.isTripped());
        assertTrue(publisher.tripReason().contains("outbound buffer full"), publisher.tripReason());
        publisher.onOrder(5, 0, Side.BUY, 100, 1.5, 5);
        assertEquals(2, publisher.ordersDropped());
        assertEquals(1, trips.size(), "the breaker listener is told once");
    }

    @Test
    void queuedBytesWithoutProgressTripAfterTheStallTimeout() throws Exception {
        long[] now = {0};
        BinaryOrderPublisher publisher = new BinaryOrderPublisher(new StalledChannel(),
                BinaryOrderPublisher.DEFAULT_BUFFER_BYTES, 1_000_000L, () -> now[0]);
        publisher.onOrder(1, 0, Side.BUY, 100, 1.5, 1);
        now[0] = 999_999;
        publisher.flush();
        assertFalse(publisher.isTripped(), "within the stall timeout");
        now[0] = 1_000_001;
        publisher.flush();
        assertTrue(publisher.isTripped());
        assertTrue(publisher.tripReason().contains("stalled"));
    }

    @Test
    void offGridPriceTripsRatherThanSendingARoundedPrice() throws Exception {
        BinaryOrderPublisher publisher = new BinaryOrderPublisher(new StalledChannel(),
                4096, 1_000_000_000L, System::nanoTime).withPriceExponent(0, -2);
        publisher.onOrder(1, 0, Side.BUY, 100, 1.005, 1);
        assertTrue(publisher.isTripped());
        assertEquals(0, publisher.ordersSent());
    }

    // ------------------------------------------------------------------

    /**
     * Smallest thread allocation over five runs of {@code body} after one
     * warm-up run: a single noisy run (JIT, TLAB refill) cannot fail the
     * zero-allocation claim, but steady-state allocation shows in every run.
     */
    static long minAllocatedBytes(LongSupplier body) {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported());
        mx.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        long sink = body.getAsLong();
        long min = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long before = mx.getThreadAllocatedBytes(tid);
            sink += body.getAsLong();
            long after = mx.getThreadAllocatedBytes(tid);
            min = Math.min(min, after - before);
        }
        assertTrue(sink != 42, "keeps the work alive");
        return min;
    }

    private static void writeChunk(Pipe pipe, ByteBuffer source, int offset, int length)
            throws Exception {
        ByteBuffer chunk = source.duplicate().position(offset).limit(offset + length);
        while (chunk.hasRemaining()) {
            pipe.sink().write(chunk);
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(5);
        }
    }
}

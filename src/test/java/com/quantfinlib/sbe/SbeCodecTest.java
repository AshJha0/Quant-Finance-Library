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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SbeCodecTest {

    // ---- Flyweight codecs ------------------------------------------------

    @Test
    void tradeFlyweightRoundTripsExactly() {
        ByteBuffer buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        TradeFlyweight trade = new TradeFlyweight();
        trade.wrap(buffer, 16).encode(7, 1.08515, 1_000_000.5, 123_456_789L);

        assertEquals(TradeFlyweight.MESSAGE_TYPE, TradeFlyweight.typeAt(buffer, 16));
        TradeFlyweight decoded = new TradeFlyweight().wrap(buffer, 16);
        assertEquals(7, decoded.symbolId());
        assertEquals(1.08515, decoded.price(), 0.0);
        assertEquals(1_000_000.5, decoded.size(), 0.0);
        assertEquals(123_456_789L, decoded.timestampNanos());
    }

    @Test
    void orderFlyweightRoundTripsExactlyIncludingMarketOrders() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        OrderFlyweight order = new OrderFlyweight();
        order.wrap(buffer, 0).encode(42L, 3, Side.SELL, 5_000, Double.NaN, 999L);

        OrderFlyweight decoded = new OrderFlyweight().wrap(buffer, 0);
        assertEquals(42L, decoded.orderId());
        assertEquals(3, decoded.symbolId());
        assertEquals(Side.SELL, decoded.side());
        assertEquals(5_000, decoded.quantity());
        assertTrue(Double.isNaN(decoded.price()));   // market order sentinel survives
        assertEquals(999L, decoded.timestampNanos());
    }

    @Test
    void codecsAllocateNothingSteadyState() {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported());
        mx.setThreadAllocatedMemoryEnabled(true);

        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        TradeFlyweight trade = new TradeFlyweight().wrap(buffer, 0);
        double blackhole = 0;
        for (int i = 0; i < 100_000; i++) {           // warmup (JIT)
            trade.encode(i, i * 1.5, i, i);
            blackhole += trade.price();
        }
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            trade.encode(i, i * 1.5, i, i);
            blackhole += trade.price() + trade.symbolId() + trade.timestampNanos();
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "codec allocated " + allocated + " bytes over 500k round trips (bh=" + blackhole + ")");
    }

    // ---- Binary market data into the bus, with TCP-style fragmentation ------

    @Test
    void binaryMarketDataFlowsIntoTheBusAcrossFragmentedWrites() throws Exception {
        Pipe pipe = Pipe.open();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 12, 8, false)) {
            int eur = bus.registerSymbol("EURUSD");
            int gbp = bus.registerSymbol("GBPUSD");
            bus.start();

            try (BinaryMarketDataClient client = new BinaryMarketDataClient(pipe.source(), bus)) {
                client.start();

                // Encode two trades back to back, then deliver them in three
                // deliberately misaligned chunks (10 + 30 + rest bytes).
                ByteBuffer wire = ByteBuffer.allocate(2 * TradeFlyweight.BLOCK_LENGTH)
                        .order(ByteOrder.LITTLE_ENDIAN);
                TradeFlyweight trade = new TradeFlyweight();
                trade.wrap(wire, 0).encode(eur, 1.0851, 1_000_000, 111L);
                trade.wrap(wire, TradeFlyweight.BLOCK_LENGTH).encode(gbp, 1.2703, 500_000, 222L);
                writeChunk(pipe, wire, 0, 10);
                writeChunk(pipe, wire, 10, 30);
                writeChunk(pipe, wire, 40, wire.capacity() - 40);

                awaitTrue(() -> client.messagesDecoded() == 2, "frames not decoded");
                awaitTrue(() -> bus.processedCount() == 2, "bus did not dispatch");
                assertEquals(1.0851, bus.latestPrice(eur), 0.0);
                assertEquals(1.2703, bus.latestPrice(gbp), 0.0);
                assertNull(client.failureReason());
            }
        }
    }

    @Test
    void unknownMessageTypeStopsTheStreamWithDiagnostics() throws Exception {
        Pipe pipe = Pipe.open();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 4, false)) {
            bus.start();
            try (BinaryMarketDataClient client = new BinaryMarketDataClient(pipe.source(), bus)) {
                client.start();
                ByteBuffer junk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                junk.putInt(0, 99);   // no such message type
                writeChunk(pipe, junk, 0, 8);
                awaitTrue(() -> client.failureReason() != null, "corruption not detected");
                assertTrue(client.failureReason().contains("unknown message type 99"));
            }
        }
    }

    // ---- Binary order entry off the gateway's venue seam ---------------------

    @Test
    void gatewayOrdersFlowOverTheBinaryChannelInOrder() throws Exception {
        Pipe pipe = Pipe.open();
        List<long[]> received = new ArrayList<>();
        List<Side> sides = new ArrayList<>();
        BinaryOrderReceiver receiver = new BinaryOrderReceiver(pipe.source(),
                (orderId, symbolId, side, qty, price, ts) -> {
                    synchronized (received) {
                        received.add(new long[]{orderId, symbolId, qty, ts});
                        sides.add(side);
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
                assertEquals(0, received.get(0)[1]);
                assertEquals(1_000, received.get(0)[2]);
                assertEquals(111L, received.get(0)[3]);
                assertEquals(Side.BUY, sides.get(0));
                assertEquals(id2, received.get(1)[0]);
                assertEquals(Side.SELL, sides.get(1));
            }
            assertEquals(2, publisher.ordersSent());
            assertNull(receiver.failureReason());
        } finally {
            receiver.close();
        }
    }

    // ------------------------------------------------------------------

    private static void writeChunk(Pipe pipe, ByteBuffer source, int offset, int length)
            throws Exception {
        ByteBuffer chunk = source.duplicate().position(offset).limit(offset + length);
        while (chunk.hasRemaining()) {
            pipe.sink().write(chunk);
        }
        Thread.sleep(20);   // let the reader observe the partial frame
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(10);
        }
    }
}

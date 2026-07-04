package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixSessionTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private FixSession client;
    private FixSession venue;
    private ServerSocket server;

    /** Collects callbacks with latches for the interesting events. */
    private static final class Recorder implements FixSession.Listener {
        final CountDownLatch logon = new CountDownLatch(1);
        final CountDownLatch logoutLatch = new CountDownLatch(1);
        final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();
        final List<NewOrderSingle> orders = new CopyOnWriteArrayList<>();
        final List<FixMessage> messages = new CopyOnWriteArrayList<>();
        final AtomicLong gapExpected = new AtomicLong(-1);
        final AtomicLong gapReceived = new AtomicLong(-1);
        final CountDownLatch gap = new CountDownLatch(1);
        volatile FixSession session;

        @Override
        public void onLogon(FixSession s) {
            session = s;
            logon.countDown();
        }

        @Override
        public void onLogout(FixSession s) {
            logoutLatch.countDown();
        }

        @Override
        public void onExecutionReport(FixSession s, ExecutionReport report) {
            reports.add(report);
        }

        @Override
        public void onNewOrderSingle(FixSession s, NewOrderSingle order) {
            orders.add(order);
        }

        @Override
        public void onMessage(FixSession s, FixMessage message) {
            messages.add(message);
        }

        @Override
        public void onSequenceGap(long expected, long received) {
            gapExpected.set(expected);
            gapReceived.set(received);
            gap.countDown();
        }
    }

    private final Recorder clientEvents = new Recorder();
    private final Recorder venueEvents = new Recorder();

    private void connect(int heartbeatSeconds) throws Exception {
        server = new ServerSocket(0);
        Future<FixSession> accepting = executor.submit(() -> FixSession.accept(server,
                new FixSession.Config("VENUE", "CLIENT", heartbeatSeconds), venueEvents));
        client = FixSession.initiate("127.0.0.1", server.getLocalPort(),
                new FixSession.Config("CLIENT", "VENUE", heartbeatSeconds), clientEvents);
        venue = accepting.get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (venue != null) {
            venue.close();
        }
        if (server != null) {
            server.close();
        }
        executor.shutdownNow();
    }

    @Test
    void logonHandshakeEstablishesBothSides() throws Exception {
        connect(30);
        assertTrue(clientEvents.logon.await(5, TimeUnit.SECONDS));
        assertTrue(venueEvents.logon.await(5, TimeUnit.SECONDS));
        assertTrue(client.isEstablished());
        assertTrue(venue.isEstablished());
    }

    @Test
    void orderFlowRoundTripsOverTheWire() throws Exception {
        connect(30);
        client.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 1_000_000, 1.0851,
                NewOrderSingle.TIF_DAY);

        awaitTrue(() -> !venueEvents.orders.isEmpty(), "order not received");
        NewOrderSingle order = venueEvents.orders.getFirst();
        assertEquals("ord-1", order.clOrdId());
        assertEquals("EURUSD", order.symbol());
        assertEquals(Side.BUY, order.side());
        assertEquals(1_000_000, order.quantity());
        assertEquals(1.0851, order.price(), 0.0);
        assertFalse(order.isMarket());

        venue.sendExecutionReport(ExecutionReport.accepted(order, "X-1", "E-1"));
        venue.sendExecutionReport(ExecutionReport.filled(order, "X-1", "E-2", 1.0851));

        awaitTrue(() -> clientEvents.reports.size() >= 2, "execution reports not received");
        ExecutionReport ack = clientEvents.reports.get(0);
        ExecutionReport fill = clientEvents.reports.get(1);
        assertEquals(ExecutionReport.ORD_STATUS_NEW, ack.ordStatus());
        assertEquals("ord-1", ack.clOrdId());
        assertTrue(fill.isFill());
        assertEquals(ExecutionReport.ORD_STATUS_FILLED, fill.ordStatus());
        assertEquals(1.0851, fill.lastPrice(), 0.0);
        assertEquals(1_000_000, fill.lastQty(), 0.0);
        assertEquals(0, fill.leavesQty(), 0.0);
    }

    @Test
    void marketOrdersCarryNoPrice() throws Exception {
        connect(30);
        client.sendNewOrderSingle("mkt-1", "GBPUSD", Side.SELL, 500, Double.NaN,
                NewOrderSingle.TIF_IOC);
        awaitTrue(() -> !venueEvents.orders.isEmpty(), "order not received");
        NewOrderSingle order = venueEvents.orders.getFirst();
        assertTrue(order.isMarket());
        assertTrue(Double.isNaN(order.price()));
        assertEquals(NewOrderSingle.TIF_IOC, order.timeInForce());
        assertEquals(Side.SELL, order.side());
    }

    @Test
    void testRequestIsAnsweredWithMatchingHeartbeat() throws Exception {
        connect(30);
        venue.send(FixMessage.builder(FixMessage.TEST_REQUEST)
                .field(FixMessage.TEST_REQ_ID, "ping-42"));
        awaitTrue(() -> venueEvents.messages.stream().anyMatch(m ->
                        FixMessage.HEARTBEAT.equals(m.msgType())
                                && "ping-42".equals(m.getString(FixMessage.TEST_REQ_ID, ""))),
                "no heartbeat reply with TestReqID");
    }

    @Test
    void heartbeatsFlowAndKeepTheSessionAlive() throws Exception {
        connect(1);
        Thread.sleep(2_500);
        assertTrue(client.isEstablished(), "client dropped");
        assertTrue(venue.isEstablished(), "venue dropped");
        long clientHeartbeats = clientEvents.messages.stream()
                .filter(m -> FixMessage.HEARTBEAT.equals(m.msgType())).count();
        assertTrue(clientHeartbeats >= 1, "no heartbeats received: " + clientHeartbeats);
    }

    @Test
    void sequenceGapsAreDetected() throws Exception {
        connect(30);
        venue.forceOutgoingSeq(50);   // skip ahead
        venue.send(FixMessage.builder(FixMessage.HEARTBEAT));
        assertTrue(clientEvents.gap.await(5, TimeUnit.SECONDS), "gap not detected");
        assertEquals(50, clientEvents.gapReceived.get());
        assertTrue(clientEvents.gapExpected.get() < 50);
        // Session recovers and continues at the new sequence.
        assertEquals(51, client.expectedIncomingSeqNum());
    }

    @Test
    void logoutHandshakeClosesBothSidesCleanly() throws Exception {
        connect(30);
        client.logout();
        assertTrue(venueEvents.logoutLatch.await(5, TimeUnit.SECONDS), "venue missed logout");
        assertTrue(clientEvents.logoutLatch.await(5, TimeUnit.SECONDS), "client missed logout ack");
        assertFalse(client.isEstablished());
        assertFalse(venue.isEstablished());
        assertThrows(IllegalStateException.class,
                () -> client.sendNewOrderSingle("x", "EURUSD", Side.BUY, 1, 1.0, '0'));
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

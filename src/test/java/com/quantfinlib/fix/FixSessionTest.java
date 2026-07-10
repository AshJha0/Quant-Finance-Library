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
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile String disconnectReason;
        final CountDownLatch resendServed = new CountDownLatch(1);
        final AtomicLong resendBegin = new AtomicLong(-1);
        final AtomicLong resendEnd = new AtomicLong(-1);
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

        @Override
        public void onResendServed(long beginSeqNo, long endSeqNo) {
            resendBegin.set(beginSeqNo);
            resendEnd.set(endSeqNo);
            resendServed.countDown();
        }

        @Override
        public void onDisconnect(String reason) {
            disconnectReason = reason;
            disconnected.countDown();
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
        // The UPPER bound matters too: heartbeating far above the
        // negotiated 1s interval is a venue-disconnect offense that
        // "at least one" would never catch. Generous (a slow CI cannot
        // flake it) but an interval-unit bug fails instantly.
        assertTrue(clientHeartbeats <= 8,
                "heartbeat spam at a 1s interval over ~2.5s: " + clientHeartbeats);
    }

    @Test
    void deadPeerEscalatesToTestRequestThenDisconnects() throws Exception {
        // A HALF-DEAD peer: TCP established and reading, application
        // silent — the failure mode that hangs sessions forever when the
        // TestRequest/timeout escalation regresses.
        server = new ServerSocket(0);
        Future<FixSession> accepting = executor.submit(() -> FixSession.accept(server,
                new FixSession.Config("VENUE", "CLIENT", 1), venueEvents));
        try (java.net.Socket raw = new java.net.Socket("127.0.0.1", server.getLocalPort())) {
            raw.getOutputStream().write(FixMessage.builder(FixMessage.LOGON)
                    .field(FixMessage.HEART_BT_INT, 1L)
                    .encode("CLIENT", "VENUE", 1, "20260710-12:00:00.000"));
            raw.getOutputStream().flush();
            venue = accepting.get(5, TimeUnit.SECONDS);
            assertTrue(venue.isEstablished());

            // Read everything the venue sends (its writes never block),
            // but never write again.
            java.io.InputStream in = raw.getInputStream();
            StringBuilder wire = new StringBuilder();
            byte[] chunk = new byte[1024];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline && venueEvents.disconnectReason == null) {
                if (in.available() > 0) {
                    int n = in.read(chunk);
                    for (int i = 0; i < n; i++) {
                        wire.append((char) chunk[i]);
                    }
                } else {
                    Thread.sleep(20);
                }
            }
            assertTrue(wire.toString().contains("35=1"),
                    "silence must escalate to a TestRequest before the kill: " + wire);
            assertTrue(venueEvents.disconnectReason != null,
                    "a peer that ignores the TestRequest gets disconnected");
            assertTrue(!venue.isEstablished(), "the session is DOWN, not zombified");
        }
    }

    @Test
    void sequenceGapsAreDetected() throws Exception {
        connect(30);
        venue.forceOutgoingSeq(50);   // skip ahead
        venue.send(FixMessage.builder(FixMessage.HEARTBEAT));
        assertTrue(clientEvents.gap.await(5, TimeUnit.SECONDS), "gap not detected");
        assertEquals(50, clientEvents.gapReceived.get());
        assertTrue(clientEvents.gapExpected.get() < 50);
        // Recovery: the heartbeat run is gap-filled and the sequence catches up.
        awaitTrue(() -> client.expectedIncomingSeqNum() == 51, "gap not recovered");
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

    @Test
    void gapRecoveryRedeliversMissedApplicationMessages() throws Exception {
        connect(30);
        ExecutionReport report = new ExecutionReport("X-9", "E-9",
                ExecutionReport.EXEC_TYPE_TRADE, ExecutionReport.ORD_STATUS_FILLED,
                "ord-9", "EURUSD", Side.BUY, 100, 1.09, 100, 0, 1.09);

        // Venue jumps its outbound seq to 50: 2..49 never existed on the wire.
        venue.forceOutgoingSeq(50);
        venue.sendExecutionReport(report);

        // Client detects the gap, asks for a resend; venue gap-fills 2..49 and
        // replays the report at seq 50 with PossDup — exactly one delivery.
        assertTrue(clientEvents.gap.await(5, TimeUnit.SECONDS), "gap not detected");
        awaitTrue(() -> clientEvents.reports.size() == 1, "report not recovered");
        assertEquals("ord-9", clientEvents.reports.getFirst().clOrdId());
        assertTrue(venueEvents.resendServed.await(5, TimeUnit.SECONDS), "resend not serviced");
        assertEquals(2, venueEvents.resendBegin.get());
        assertEquals(50, venueEvents.resendEnd.get());
        awaitTrue(() -> client.expectedIncomingSeqNum() == 51, "sequence not recovered");
        assertTrue(client.isEstablished() && venue.isEstablished());

        // Session continues normally after recovery.
        venue.sendExecutionReport(report);
        awaitTrue(() -> clientEvents.reports.size() == 2, "post-recovery message lost");
    }

    @Test
    void servicedResendsAreSuppressedAsDuplicatesByThePeer() throws Exception {
        connect(30);
        client.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 100, 1.0851,
                NewOrderSingle.TIF_DAY);
        awaitTrue(() -> venueEvents.orders.size() == 1, "order not received");

        // Venue explicitly asks for everything again.
        venue.send(FixMessage.builder(FixMessage.RESEND_REQUEST)
                .field(FixMessage.BEGIN_SEQ_NO, 2)
                .field(FixMessage.END_SEQ_NO, 0));

        assertTrue(clientEvents.resendServed.await(5, TimeUnit.SECONDS), "resend not serviced");
        assertEquals(2, clientEvents.resendBegin.get());
        Thread.sleep(300);   // give the PossDup replay time to arrive
        // The replayed order is recognized as a duplicate, not a new order.
        assertEquals(1, venueEvents.orders.size());
        assertTrue(venue.isEstablished());
    }

    @Test
    void tooLowSequenceWithoutPossDupDisconnects() throws Exception {
        connect(30);
        venue.forceOutgoingSeq(1);   // repeats an already-used seqnum
        venue.send(FixMessage.builder(FixMessage.HEARTBEAT));

        assertTrue(clientEvents.disconnected.await(5, TimeUnit.SECONDS), "no disconnect");
        assertTrue(clientEvents.disconnectReason.contains("too low"),
                clientEvents.disconnectReason);
        assertFalse(client.isEstablished());
    }

    @Test
    void hardSequenceResetJumpsExpectedSeqNum() throws Exception {
        connect(30);
        venue.send(FixMessage.builder(FixMessage.SEQUENCE_RESET)
                .field(FixMessage.GAP_FILL_FLAG, "N")
                .field(FixMessage.NEW_SEQ_NO, 100));
        awaitTrue(() -> client.expectedIncomingSeqNum() == 100, "reset not applied");
        assertTrue(client.isEstablished());
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

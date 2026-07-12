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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResetSeqNumFlag(141): the clean-slate reconnect. The scenario that
 * matters is a fresh store on one side against a durable store on the
 * other — without 141=Y the fresh peer's seq 1 is "too low" and the
 * session dies on sight (proved by the control test); with it, both sides
 * restart at 1 and trade flows with no resend storm and no disconnect.
 */
class FixResetSeqNumTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private FixSession client;
    private FixSession venue;
    private ServerSocket server;

    private static final class Recorder implements FixSession.Listener {
        final CountDownLatch logon = new CountDownLatch(1);
        final List<NewOrderSingle> orders = new CopyOnWriteArrayList<>();
        final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();
        final AtomicLong gapExpected = new AtomicLong(-1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile String disconnectReason;

        @Override
        public void onLogon(FixSession s) {
            logon.countDown();
        }

        @Override
        public void onNewOrderSingle(FixSession s, NewOrderSingle order) {
            orders.add(order);
        }

        @Override
        public void onExecutionReport(FixSession s, ExecutionReport report) {
            reports.add(report);
        }

        @Override
        public void onSequenceGap(long expected, long received) {
            gapExpected.set(expected);
        }

        @Override
        public void onDisconnect(String reason) {
            disconnectReason = reason;
            disconnected.countDown();
        }
    }

    private final Recorder clientEvents = new Recorder();
    private final Recorder venueEvents = new Recorder();

    /** A venue store left behind by a long-lived previous session. */
    private static FixSessionStore staleStore() {
        FixSessionStore store = FixSessionStore.inMemory();
        store.saveOutgoingSeq(55);
        store.saveIncomingSeq(55);
        return store;
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
    void resetOnLogonReconnectsAFreshStoreAgainstAStaleOneWithoutDisconnect() throws Exception {
        server = new ServerSocket(0);
        FixSessionStore venueStore = staleStore();
        Future<FixSession> accepting = executor.submit(() -> FixSession.accept(server,
                new FixSession.Config("VENUE", "CLIENT", 30), venueEvents, venueStore));
        // Fresh in-memory store on the client + 141=Y: both sides restart at 1.
        client = FixSession.initiate("127.0.0.1", server.getLocalPort(),
                new FixSession.Config("CLIENT", "VENUE", 30).withResetOnLogon(), clientEvents);
        venue = accepting.get(5, TimeUnit.SECONDS);

        assertTrue(clientEvents.logon.await(5, TimeUnit.SECONDS), "client not logged on");
        assertTrue(venueEvents.logon.await(5, TimeUnit.SECONDS), "venue not logged on");
        assertTrue(client.isEstablished() && venue.isEstablished());

        // Trade flows on the reset numbering: order out, report back.
        client.sendNewOrderSingle("rst-1", "EURUSD", Side.BUY, 100, 1.0851,
                NewOrderSingle.TIF_DAY);
        awaitTrue(() -> venueEvents.orders.size() == 1, "order not received");
        assertEquals("rst-1", venueEvents.orders.getFirst().clOrdId());
        venue.sendExecutionReport(ExecutionReport.accepted(
                venueEvents.orders.getFirst(), "X-1", "E-1"));
        awaitTrue(() -> clientEvents.reports.size() == 1, "report not received");

        // The reset actually took: logon was 1, order was 2 — the venue now
        // expects 3, nowhere near the stale 55; ditto the client after the
        // venue's logon (1) and report (2). No gaps, no disconnects.
        assertEquals(3, venue.expectedIncomingSeqNum());
        assertEquals(3, client.expectedIncomingSeqNum());
        assertEquals(-1, clientEvents.gapExpected.get(), "client saw a phantom gap");
        assertEquals(-1, venueEvents.gapExpected.get(), "venue saw a phantom gap");
        assertTrue(venueEvents.disconnectReason == null, venueEvents.disconnectReason);
        assertTrue(clientEvents.disconnectReason == null, clientEvents.disconnectReason);
        // And the venue's durable store carries the reset numbering forward.
        assertEquals(3, venueStore.nextOutgoingSeq());
        assertEquals(3, venueStore.expectedIncomingSeq());
    }

    @Test
    void withoutTheFlagAFreshStoreTripsTheTooLowDisconnect() throws Exception {
        // The control: same stale venue store, plain logon. The client's
        // seq 1 is below the venue's expected 55 with no PossDup — the
        // session must die exactly the way 141=Y exists to prevent.
        server = new ServerSocket(0);
        Future<FixSession> accepting = executor.submit(() -> FixSession.accept(server,
                new FixSession.Config("VENUE", "CLIENT", 30), venueEvents, staleStore()));
        Future<FixSession> initiating = executor.submit(() -> FixSession.initiate(
                "127.0.0.1", server.getLocalPort(),
                new FixSession.Config("CLIENT", "VENUE", 30), clientEvents));

        assertTrue(venueEvents.disconnected.await(5, TimeUnit.SECONDS), "no disconnect");
        assertTrue(venueEvents.disconnectReason.contains("too low"),
                venueEvents.disconnectReason);
        venue = quiet(accepting);
        client = quiet(initiating);
    }

    private FixSession quiet(Future<FixSession> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;   // a failed/aborted handshake is this test's point
        }
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

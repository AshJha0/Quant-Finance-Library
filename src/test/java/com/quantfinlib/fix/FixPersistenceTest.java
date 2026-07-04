package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixPersistenceTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static final class Recorder implements FixSession.Listener {
        final List<NewOrderSingle> orders = new CopyOnWriteArrayList<>();
        final CountDownLatch resendServed = new CountDownLatch(1);
        final AtomicLong gaps = new AtomicLong();

        @Override
        public void onNewOrderSingle(FixSession s, NewOrderSingle order) {
            orders.add(order);
        }

        @Override
        public void onResendServed(long begin, long end) {
            resendServed.countDown();
        }

        @Override
        public void onSequenceGap(long expected, long received) {
            gaps.incrementAndGet();
        }
    }

    @Test
    void fileStoreRoundTripsAcrossReopen(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(1, store.nextOutgoingSeq());
            assertEquals(1, store.expectedIncomingSeq());
            store.saveOutgoingSeq(7);
            store.saveIncomingSeq(4);
            store.storeMessage(2, new FixSessionStore.StoredMessage(
                    "D", "11=ord-155=EURUSD", "20260704-10:00:00.000"));
            store.storeMessage(5, new FixSessionStore.StoredMessage(
                    "8", "37=X-1", "20260704-10:00:01.000"));
        }
        try (FileSessionStore reopened = new FileSessionStore(dir)) {
            assertEquals(7, reopened.nextOutgoingSeq());
            assertEquals(4, reopened.expectedIncomingSeq());
            assertEquals("D", reopened.retrieve(2).msgType());
            assertTrue(reopened.retrieve(2).body().contains("11=ord-1"));
            assertEquals("8", reopened.retrieve(5).msgType());
            assertNull(reopened.retrieve(3));
        }
    }

    @Test
    void sessionsContinueSequenceNumbersAcrossReconnect(@TempDir Path dir) throws Exception {
        Path clientDir = dir.resolve("client");
        Path venueDir = dir.resolve("venue");
        FixSession.Config clientCfg = new FixSession.Config("CLIENT", "VENUE", 30);
        FixSession.Config venueCfg = new FixSession.Config("VENUE", "CLIENT", 30);

        try (ServerSocket server = new ServerSocket(0)) {
            // ---- Session 1: logon, one order, clean logout ----
            Recorder venueEvents1 = new Recorder();
            Future<FixSession> accepting1 = executor.submit(() ->
                    FixSession.accept(server, venueCfg, venueEvents1, new FileSessionStore(venueDir)));
            FixSession client1 = FixSession.initiate("127.0.0.1", server.getLocalPort(),
                    clientCfg, new Recorder(), new FileSessionStore(clientDir));
            FixSession venue1 = accepting1.get(5, TimeUnit.SECONDS);

            client1.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 100, 1.0851,
                    NewOrderSingle.TIF_DAY);
            awaitTrue(() -> venueEvents1.orders.size() == 1, "order not received");
            client1.logout();
            venue1.close();

            // ---- Session 2: same stores, fresh processes ----
            Recorder venueEvents2 = new Recorder();
            Recorder clientEvents2 = new Recorder();
            Future<FixSession> accepting2 = executor.submit(() ->
                    FixSession.accept(server, venueCfg, venueEvents2, new FileSessionStore(venueDir)));
            FixSession client2 = FixSession.initiate("127.0.0.1", server.getLocalPort(),
                    clientCfg, clientEvents2, new FileSessionStore(clientDir));
            FixSession venue2 = accepting2.get(5, TimeUnit.SECONDS);

            // Continuation: no gaps, both established, seqs resumed above 1.
            assertTrue(client2.isEstablished() && venue2.isEstablished());
            assertEquals(0, clientEvents2.gaps.get());
            assertEquals(0, venueEvents2.gaps.get());
            assertTrue(venue2.expectedIncomingSeqNum() > 2,
                    "venue expects " + venue2.expectedIncomingSeqNum());

            // New order flows on the continued sequence.
            client2.sendNewOrderSingle("ord-2", "EURUSD", Side.SELL, 50, 1.0860,
                    NewOrderSingle.TIF_DAY);
            awaitTrue(() -> venueEvents2.orders.size() == 1, "post-reconnect order lost");
            assertEquals("ord-2", venueEvents2.orders.getFirst().clOrdId());

            // Resend across the restart: ord-1 (from session 1!) is replayed
            // from the reopened file store and correctly suppressed as PossDup.
            venue2.send(FixMessage.builder(FixMessage.RESEND_REQUEST)
                    .field(FixMessage.BEGIN_SEQ_NO, 2)
                    .field(FixMessage.END_SEQ_NO, 0));
            assertTrue(clientEvents2.resendServed.await(5, TimeUnit.SECONDS),
                    "resend from persistent store not serviced");
            Thread.sleep(300);
            assertEquals(1, venueEvents2.orders.size(), "PossDup replay not suppressed");
            assertTrue(venue2.isEstablished());

            client2.logout();
            venue2.close();
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

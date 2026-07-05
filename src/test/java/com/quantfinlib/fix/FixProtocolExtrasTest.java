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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Username/Password logon, cancel/replace (35=F/G), and session Reject (35=3). */
class FixProtocolExtrasTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private FixSession client;
    private FixSession venue;
    private ServerSocket server;

    private static final class Recorder implements FixSession.Listener {
        final List<NewOrderSingle> orders = new CopyOnWriteArrayList<>();
        final List<OrderCancelRequest> cancels = new CopyOnWriteArrayList<>();
        final List<OrderCancelReplaceRequest> replaces = new CopyOnWriteArrayList<>();
        final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();
        final AtomicReference<FixMessage> logonMessage = new AtomicReference<>();
        final AtomicLong rejectRefSeq = new AtomicLong(-1);
        final AtomicReference<String> rejectText = new AtomicReference<>();
        final CountDownLatch rejected = new CountDownLatch(1);

        @Override
        public void onNewOrderSingle(FixSession s, NewOrderSingle order) {
            orders.add(order);
        }

        @Override
        public void onOrderCancelRequest(FixSession s, OrderCancelRequest request) {
            cancels.add(request);
        }

        @Override
        public void onOrderCancelReplace(FixSession s, OrderCancelReplaceRequest request) {
            replaces.add(request);
        }

        @Override
        public void onExecutionReport(FixSession s, ExecutionReport report) {
            reports.add(report);
        }

        @Override
        public void onMessage(FixSession s, FixMessage message) {
            if (FixMessage.LOGON.equals(message.msgType())) {
                logonMessage.set(message);
            }
        }

        @Override
        public void onReject(FixSession s, long refSeqNum, String text) {
            rejectRefSeq.set(refSeqNum);
            rejectText.set(text);
            rejected.countDown();
        }
    }

    private final Recorder clientEvents = new Recorder();
    private final Recorder venueEvents = new Recorder();

    private void connect(FixSession.Config clientConfig) throws Exception {
        server = new ServerSocket(0);
        Future<FixSession> accepting = executor.submit(() -> FixSession.accept(server,
                new FixSession.Config("VENUE", "CLIENT", 30), venueEvents));
        client = FixSession.initiate("127.0.0.1", server.getLocalPort(), clientConfig, clientEvents);
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
    void credentialsTravelOnTheLogon() throws Exception {
        connect(new FixSession.Config("CLIENT", "VENUE", 30)
                .withCredentials("trader7", "s3cret"));
        awaitTrue(() -> venueEvents.logonMessage.get() != null, "venue missed logon");
        FixMessage logon = venueEvents.logonMessage.get();
        assertEquals("trader7", logon.getString(FixMessage.USERNAME));
        assertEquals("s3cret", logon.getString(FixMessage.PASSWORD));
        // Without credentials the tags are absent.
        assertTrue(client.isEstablished());
    }

    @Test
    void cancelRequestRoundTripsWithCanceledReport() throws Exception {
        connect(new FixSession.Config("CLIENT", "VENUE", 30));
        client.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 1_000, 1.0851,
                NewOrderSingle.TIF_DAY);
        awaitTrue(() -> venueEvents.orders.size() == 1, "order lost");

        client.sendOrderCancelRequest("cxl-1", "ord-1", "EURUSD", Side.BUY, 1_000);
        awaitTrue(() -> venueEvents.cancels.size() == 1, "cancel lost");
        OrderCancelRequest cancel = venueEvents.cancels.getFirst();
        assertEquals("cxl-1", cancel.clOrdId());
        assertEquals("ord-1", cancel.origClOrdId());
        assertEquals(1_000, cancel.quantity());

        venue.sendExecutionReport(ExecutionReport.canceled(cancel, "X-1", "E-2", 0));
        awaitTrue(() -> !clientEvents.reports.isEmpty(), "canceled report lost");
        ExecutionReport report = clientEvents.reports.getFirst();
        assertEquals(ExecutionReport.EXEC_TYPE_CANCELED, report.execType());
        assertEquals(ExecutionReport.ORD_STATUS_CANCELED, report.ordStatus());
        assertEquals("cxl-1", report.clOrdId());
    }

    @Test
    void cancelReplaceRoundTripsWithReplacedReport() throws Exception {
        connect(new FixSession.Config("CLIENT", "VENUE", 30));
        client.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 1_000, 1.0851,
                NewOrderSingle.TIF_DAY);
        awaitTrue(() -> venueEvents.orders.size() == 1, "order lost");

        client.sendOrderCancelReplace("rpl-1", "ord-1", "EURUSD", Side.BUY, 2_000, 1.0845,
                NewOrderSingle.TIF_DAY);
        awaitTrue(() -> venueEvents.replaces.size() == 1, "replace lost");
        OrderCancelReplaceRequest replace = venueEvents.replaces.getFirst();
        assertEquals("ord-1", replace.origClOrdId());
        assertEquals(2_000, replace.quantity());
        assertEquals(1.0845, replace.price(), 0.0);

        venue.sendExecutionReport(ExecutionReport.replaced(replace, "X-1", "E-3", 0));
        awaitTrue(() -> !clientEvents.reports.isEmpty(), "replaced report lost");
        ExecutionReport report = clientEvents.reports.getFirst();
        assertEquals(ExecutionReport.EXEC_TYPE_REPLACED, report.execType());
        assertEquals(2_000, report.leavesQty(), 0.0);
    }

    @Test
    void explicitRejectReachesThePeer() throws Exception {
        connect(new FixSession.Config("CLIENT", "VENUE", 30));
        venue.sendReject(5, "unsupported instrument");
        assertTrue(clientEvents.rejected.await(5, TimeUnit.SECONDS), "reject lost");
        assertEquals(5, clientEvents.rejectRefSeq.get());
        assertEquals("unsupported instrument", clientEvents.rejectText.get());
        assertTrue(client.isEstablished());   // session survives a reject
    }

    @Test
    void malformedApplicationMessageIsAutoRejectedNotFatal() throws Exception {
        connect(new FixSession.Config("CLIENT", "VENUE", 30));
        // An ExecutionReport missing required tags: dispatch fails on the client,
        // which must answer with a session Reject instead of disconnecting.
        venue.send(FixMessage.builder(FixMessage.EXECUTION_REPORT)
                .field(FixMessage.ORDER_ID, "X-1"));
        assertTrue(venueEvents.rejected.await(5, TimeUnit.SECONDS), "no auto-reject");
        assertTrue(venueEvents.rejectRefSeq.get() > 0);
        assertTrue(client.isEstablished() && venue.isEstablished());

        // Session keeps working after the reject.
        client.sendNewOrderSingle("ord-2", "GBPUSD", Side.SELL, 10, 1.27,
                NewOrderSingle.TIF_DAY);
        awaitTrue(() -> venueEvents.orders.size() == 1, "session broken after reject");
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

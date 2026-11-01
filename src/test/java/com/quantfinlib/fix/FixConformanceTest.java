package com.quantfinlib.fix;

import com.quantfinlib.fix.FixTestKit.Events;
import com.quantfinlib.fix.FixTestKit.Link;
import com.quantfinlib.fix.FixTestKit.Scripted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static com.quantfinlib.fix.FixTestKit.logon;
import static com.quantfinlib.fix.FixTestKit.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session-layer scenarios after the FIX 4.4 Volume 2 "Session Level Test
 * Cases" (logon, sequence handling, resend, PossDup, reject, heartbeat).
 * Every scenario runs on one thread against a manual clock.
 */
class FixConformanceTest {

    private static final FixSession.Config VENUE = new FixSession.Config("VENUE", "CLIENT", 30);
    private static final FixSession.Config CLIENT = new FixSession.Config("CLIENT", "VENUE", 30);

    private static Scripted acceptor() {
        return new Scripted(VENUE, true, FixSessionStore.inMemory(), new Events());
    }

    private static Scripted establishedAcceptor() {
        Scripted s = acceptor();
        s.deliver(logon(30), 1);
        s.sent();   // drop the Logon reply
        assertTrue(s.session.isEstablished());
        return s;
    }

    // ---- Logon (test cases 1B-1F) -------------------------------------------

    @Test
    void applicationMessageBeforeLogonDisconnectsWithoutDispatch() {
        Scripted s = acceptor();
        s.deliver(order("ord-1"), 1);
        assertTrue(s.events.orders.isEmpty(), "an order before Logon must never reach the app");
        assertNotNull(s.events.disconnect);
        assertTrue(s.events.disconnect.contains("not Logon"), s.events.disconnect);
        assertTrue(s.sent().isEmpty(), "nothing is answered to a peer that has not logged on");
    }

    @Test
    void logonRequestHookRejectsBadCredentialsBeforeTheSessionIsLive() {
        Scripted s = acceptor();
        s.events.decision = FixSession.LogonDecision.reject("bad password");
        s.deliver(logon(30).field(FixMessage.USERNAME, "u").field(FixMessage.PASSWORD, "x"), 1);
        List<FixMessage> out = s.sent();
        assertEquals(1, out.size());
        assertEquals(FixMessage.LOGOUT, out.getFirst().msgType());
        assertEquals("bad password", out.getFirst().getString(FixMessage.TEXT));
        assertEquals(0, s.events.logons);
        assertFalse(s.session.isEstablished());
        assertTrue(s.events.disconnect.contains("bad password"));
    }

    @Test
    void logonReplyPrecedesTheResendRequestWhenThePeerIsAhead() {
        Scripted s = acceptor();
        s.deliver(logon(30), 5);   // initiator's store is ahead: seqs 1-4 unknown here
        List<FixMessage> out = s.sent();
        assertEquals(2, out.size());
        assertEquals(FixMessage.LOGON, out.get(0).msgType(), "the Logon reply must come first");
        assertEquals(FixMessage.RESEND_REQUEST, out.get(1).msgType());
        assertEquals("1", out.get(1).getString(FixMessage.BEGIN_SEQ_NO));
        assertEquals("0", out.get(1).getString(FixMessage.END_SEQ_NO));
        assertTrue(s.session.isEstablished());
    }

    @Test
    void acceptorAdoptsTheInitiatorsHeartbeatInterval() {
        Scripted s = acceptor();
        s.deliver(logon(7), 1);
        FixMessage reply = s.sent().getFirst();
        assertEquals("7", reply.getString(FixMessage.HEART_BT_INT));
        assertEquals(7, s.session.heartbeatIntervalSeconds());
    }

    @Test
    void logonWithWrongCompIdIsRefused() {
        FixSession.Config venue = new FixSession.Config("VENUE", "SOMEONE-ELSE", 30);
        Scripted s = new Scripted(venue, true, FixSessionStore.inMemory(), new Events());
        s.feed(logon(30).encode("CLIENT", "VENUE", 1, s.clock.utc()));
        assertEquals(FixMessage.LOGOUT, s.sent().getFirst().msgType());
        assertFalse(s.session.isEstablished());
        assertTrue(s.events.disconnect.contains("CompID"));
    }

    // ---- Message validation (2a-2t) -----------------------------------------

    @Test
    void compIdMismatchAfterLogonIsRejectedAndLoggedOut() {
        Scripted s = establishedAcceptor();
        s.feed(order("o").encode("INTRUDER", "VENUE", 2, s.clock.utc()));
        List<FixMessage> out = s.sent();
        assertEquals(FixMessage.REJECT, out.get(0).msgType());
        assertEquals("9", out.get(0).getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals(FixMessage.LOGOUT, out.get(1).msgType());
        assertTrue(s.events.orders.isEmpty());
        assertNotNull(s.events.disconnect);
    }

    @Test
    void sendingTimeOutsideToleranceIsRejectedAndLoggedOut() {
        Scripted s = establishedAcceptor();
        s.feed(order("o").encode("CLIENT", "VENUE", 2, "20260710-11:57:59.000"));   // 121 s old
        List<FixMessage> out = s.sent();
        assertEquals("10", out.get(0).getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals(FixMessage.LOGOUT, out.get(1).msgType());
        assertTrue(s.events.orders.isEmpty());
    }

    @Test
    void sendingTimeInsideToleranceIsAccepted() {
        Scripted s = establishedAcceptor();
        s.feed(order("o").encode("CLIENT", "VENUE", 2, "20260710-11:58:01.000"));   // 119 s old
        assertEquals(1, s.events.orders.size());
    }

    @Test
    void tooLowSequenceWithoutPossDupLogsOutAndDisconnects() {
        Scripted s = establishedAcceptor();
        s.deliver(order("o"), 1);
        List<FixMessage> out = s.sent();
        assertEquals(FixMessage.LOGOUT, out.getFirst().msgType());
        assertTrue(out.getFirst().getString(FixMessage.TEXT).contains("too low"));
        assertNotNull(s.events.disconnect);
    }

    @Test
    void possDupBelowExpectedIsIgnoredAndAtExpectedIsDeliveredFlagged() {
        Scripted s = establishedAcceptor();
        s.deliver(FixMessage.builder(FixMessage.EXECUTION_REPORT)
                .field(FixMessage.ORDER_ID, "X1").field(FixMessage.EXEC_ID, "E1")
                .field(FixMessage.EXEC_TYPE, 'F').field(FixMessage.ORD_STATUS, '2')
                .field(FixMessage.SYMBOL, "EURUSD").field(FixMessage.SIDE, '1'), 2);
        // The same report replayed under its old number: a duplicate, ignored.
        s.deliverPossDup(FixMessage.builder(FixMessage.EXECUTION_REPORT)
                .field(FixMessage.ORDER_ID, "X1").field(FixMessage.EXEC_ID, "E1")
                .field(FixMessage.EXEC_TYPE, 'F').field(FixMessage.ORD_STATUS, '2')
                .field(FixMessage.SYMBOL, "EURUSD").field(FixMessage.SIDE, '1'), 2,
                "20260710-11:59:59.000");
        assertEquals(1, s.events.reports.size());
        assertFalse(s.events.reports.getFirst().possDup());
        // A PossDup at the expected number is new to us: delivered, marked.
        s.deliverPossDup(FixMessage.builder(FixMessage.EXECUTION_REPORT)
                .field(FixMessage.ORDER_ID, "X1").field(FixMessage.EXEC_ID, "E2")
                .field(FixMessage.EXEC_TYPE, 'F').field(FixMessage.ORD_STATUS, '2')
                .field(FixMessage.SYMBOL, "EURUSD").field(FixMessage.SIDE, '1'), 3,
                "20260710-11:59:59.000");
        assertEquals(2, s.events.reports.size());
        assertTrue(s.events.reports.get(1).possDup());
        assertTrue(s.session.isEstablished());
    }

    @Test
    void possDupWithoutOrigSendingTimeIsRejectedAndConsumesTheNumber() {
        Scripted s = establishedAcceptor();
        s.deliverPossDup(order("o"), 2, null);
        FixMessage reject = s.sent().getFirst();
        assertEquals(FixMessage.REJECT, reject.msgType());
        assertEquals("1", reject.getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals("122", reject.getString(FixMessage.REF_TAG_ID));
        assertTrue(s.events.orders.isEmpty());
        assertEquals(3, s.session.expectedIncomingSeqNum());
        assertTrue(s.session.isEstablished());
    }

    @Test
    void origSendingTimeAfterSendingTimeIsRejectedAndLoggedOut() {
        Scripted s = establishedAcceptor();
        s.deliverPossDup(order("o"), 2, "20260710-12:00:05.000");
        List<FixMessage> out = s.sent();
        assertEquals("10", out.get(0).getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals(FixMessage.LOGOUT, out.get(1).msgType());
    }

    @Test
    void wellFramedMessageWithBadTagIsRejectedNotDisconnected() {
        Scripted s = establishedAcceptor();
        String body = "35=D\u000149=CLIENT\u000156=VENUE\u000134=2\u000152=" + s.clock.utc()
                + "\u0001abc=1\u000111=o\u0001";
        s.feed(frame(body));
        FixMessage reject = s.sent().getFirst();
        assertEquals(FixMessage.REJECT, reject.msgType());
        assertEquals("2", reject.getString(FixMessage.REF_SEQ_NUM));
        assertEquals("0", reject.getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals(3, s.session.expectedIncomingSeqNum(), "a rejected message consumes its number");
        assertTrue(s.session.isEstablished());
        s.deliver(order("next"), 3);
        assertEquals(1, s.events.orders.size(), "session keeps working after the reject");
    }

    @Test
    void garbledChecksumIsIgnoredWithoutConsumingTheNumber() {
        Scripted s = establishedAcceptor();
        byte[] bytes = order("o").encode("CLIENT", "VENUE", 2, s.clock.utc());
        bytes[bytes.length - 2] = (byte) (bytes[bytes.length - 2] == '9' ? '0' : bytes[bytes.length - 2] + 1);
        s.feed(bytes);
        assertEquals(1, s.events.garbled.size());
        assertTrue(s.sent().isEmpty(), "a garbled message is not answered");
        assertEquals(2, s.session.expectedIncomingSeqNum());
        s.deliver(order("o"), 2);
        assertEquals(1, s.events.orders.size());
    }

    // ---- SequenceReset (test cases 10, 11) -----------------------------------

    @Test
    void gapFillMovesForwardButARegressingGapFillIsRejected() {
        Scripted s = establishedAcceptor();
        s.deliver(FixMessage.builder(FixMessage.SEQUENCE_RESET)
                .field(FixMessage.GAP_FILL_FLAG, "Y").field(FixMessage.NEW_SEQ_NO, 2), 2);
        FixMessage reject = s.sent().getFirst();
        assertEquals("5", reject.getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals(3, s.session.expectedIncomingSeqNum());

        s.deliver(FixMessage.builder(FixMessage.SEQUENCE_RESET)
                .field(FixMessage.GAP_FILL_FLAG, "Y").field(FixMessage.NEW_SEQ_NO, 10), 3);
        assertEquals(10, s.session.expectedIncomingSeqNum());
    }

    @Test
    void resetModeIgnoresMsgSeqNumAndRejectsNewSeqNoBelowExpected() {
        Scripted s = establishedAcceptor();
        s.deliver(FixMessage.builder(FixMessage.SEQUENCE_RESET)
                .field(FixMessage.GAP_FILL_FLAG, "N").field(FixMessage.NEW_SEQ_NO, 100), 77);
        assertEquals(100, s.session.expectedIncomingSeqNum());
        s.deliver(FixMessage.builder(FixMessage.SEQUENCE_RESET)
                .field(FixMessage.NEW_SEQ_NO, 50), 100);
        FixMessage reject = s.sent().getFirst();
        assertEquals("5", reject.getString(FixMessage.SESSION_REJECT_REASON));
        assertEquals(100, s.session.expectedIncomingSeqNum(), "a lower NewSeqNo changes nothing");
        assertTrue(s.session.isEstablished());
    }

    // ---- Heartbeat and TestRequest (test cases 4, 6) -------------------------

    @Test
    void heartbeatStateMachineRunsOnTheInjectedClock() {
        FixSession.Config cfg = new FixSession.Config("VENUE", "CLIENT", 10);
        Scripted s = new Scripted(cfg, true, FixSessionStore.inMemory(), new Events());
        s.deliver(logon(10), 1);
        s.sent();

        s.clock.advanceMillis(9_999);
        s.session.onTimer();
        assertTrue(s.sent().isEmpty(), "nothing due before one interval");
        s.clock.advanceMillis(1);
        s.session.onTimer();
        List<FixMessage> out = s.sent();
        assertEquals(1, out.size());
        assertEquals(FixMessage.HEARTBEAT, out.getFirst().msgType());

        s.clock.advanceMillis(5_000);   // 15 s of inbound silence = 1.5 intervals
        s.session.onTimer();
        FixMessage probe = s.sent().getFirst();
        assertEquals(FixMessage.TEST_REQUEST, probe.msgType());
        String id = probe.getString(FixMessage.TEST_REQ_ID);
        assertEquals(id, s.session.pendingTestReqId());

        // A heartbeat with some other TestReqID does not answer the probe.
        s.deliver(FixMessage.builder(FixMessage.HEARTBEAT).field(FixMessage.TEST_REQ_ID, "other"), 2);
        assertEquals(id, s.session.pendingTestReqId());
        s.deliver(FixMessage.builder(FixMessage.HEARTBEAT).field(FixMessage.TEST_REQ_ID, id), 3);
        assertNull(s.session.pendingTestReqId());

        // Silence again: probe at 15 s, disconnect at 25 s.
        s.clock.advanceMillis(15_000);
        s.session.onTimer();
        s.clock.advanceMillis(9_999);
        s.session.onTimer();
        assertNull(s.events.disconnect);
        s.clock.advanceMillis(1);
        s.session.onTimer();
        assertNotNull(s.events.disconnect);
        assertTrue(s.events.disconnect.contains("heartbeat timeout"));
        assertFalse(s.session.isEstablished());
    }

    @Test
    void testRequestIsAnsweredWithItsId() {
        Scripted s = establishedAcceptor();
        s.deliver(FixMessage.builder(FixMessage.TEST_REQUEST).field(FixMessage.TEST_REQ_ID, "abc"), 2);
        FixMessage hb = s.sent().getFirst();
        assertEquals(FixMessage.HEARTBEAT, hb.msgType());
        assertEquals("abc", hb.getString(FixMessage.TEST_REQ_ID));
    }

    // ---- Resend: real replay versus gap fill ---------------------------------

    private static Link link(FixSessionStore clientStore, FixSessionStore venueStore,
                             Events clientEvents, Events venueEvents) {
        Link link = new Link();
        link.a = FixSession.overStream(CLIENT, clientEvents, false, clientStore, link.aOut, link.clock);
        link.b = FixSession.overStream(VENUE, venueEvents, true, venueStore, link.bOut, link.clock);
        link.a.sendLogon();
        link.pump();
        assertTrue(link.a.isEstablished() && link.b.isEstablished());
        return link;
    }

    @Test
    void resendReplaysApplicationMessagesAndGapFillsAdminOnes() {
        Events clientEvents = new Events();
        Events venueEvents = new Events();
        Link link = link(FixSessionStore.inMemory(), FixSessionStore.inMemory(),
                clientEvents, venueEvents);
        link.a.sendNewOrderSingle("o1", "EURUSD", com.quantfinlib.orderbook.Side.BUY, 1, 1.5, '0');
        link.a.send(FixMessage.builder(FixMessage.HEARTBEAT));
        link.a.sendNewOrderSingle("o2", "EURUSD", com.quantfinlib.orderbook.Side.BUY, 1, 1.5, '0');
        link.pump();
        assertEquals(2, venueEvents.orders.size());

        link.aToB.clear();
        link.b.send(FixMessage.builder(FixMessage.RESEND_REQUEST)
                .field(FixMessage.BEGIN_SEQ_NO, 2).field(FixMessage.END_SEQ_NO, 0));
        link.pump();
        // seq 2 = o1 replayed, seq 3 = heartbeat as GapFill, seq 4 = o2 replayed.
        assertEquals(3, link.aToB.size());
        assertEquals(FixMessage.NEW_ORDER_SINGLE, link.aToB.get(0).msgType());
        assertEquals("Y", link.aToB.get(0).getString(FixMessage.POSS_DUP_FLAG));
        assertTrue(link.aToB.get(0).has(FixMessage.ORIG_SENDING_TIME));
        assertEquals(FixMessage.SEQUENCE_RESET, link.aToB.get(1).msgType());
        assertEquals("Y", link.aToB.get(1).getString(FixMessage.GAP_FILL_FLAG));
        assertEquals("4", link.aToB.get(1).getString(FixMessage.NEW_SEQ_NO));
        assertEquals("o2", link.aToB.get(2).getString(FixMessage.CL_ORD_ID));
        assertEquals(2, venueEvents.orders.size(), "replays below expected are suppressed");
        assertTrue(link.b.isEstablished());
    }

    @Test
    void resendAfterACrashWithATornJournalTailReplaysTheRealOrders(@TempDir Path dir)
            throws Exception {
        Path clientDir = dir.resolve("client");
        Events venueEvents = new Events();
        // Session 1: three orders, then the client process dies (no Logout).
        FileSessionStore clientStore = new FileSessionStore(clientDir);
        Link first = link(clientStore, FixSessionStore.inMemory(), new Events(), venueEvents);
        for (int i = 1; i <= 3; i++) {
            first.a.sendNewOrderSingle("ord-" + i, "EURUSD", com.quantfinlib.orderbook.Side.BUY,
                    100, 1.5, '0');
        }
        clientStore.close();
        // The crash tore a half-written record onto the journal's tail.
        Files.write(clientDir.resolve("messages.journal"), new byte[]{0, 0, 0, 90, 0, 0, 0},
                StandardOpenOption.APPEND);

        // The venue never saw the orders: it still expects the client's seq 2.
        FixSessionStore venueStore = FixSessionStore.inMemory();
        venueStore.saveIncomingSeq(2);
        venueStore.saveOutgoingSeq(2);
        FileSessionStore reopened = new FileSessionStore(clientDir);
        assertEquals(7, reopened.truncatedBytes(), "the torn tail is cut off, not fatal");
        assertEquals(5, reopened.nextOutgoingSeq());

        Events venue2 = new Events();
        Link second = new Link();
        second.a = FixSession.overStream(CLIENT, new Events(), false, reopened, second.aOut, second.clock);
        second.b = FixSession.overStream(VENUE, venue2, true, venueStore, second.bOut, second.clock);
        second.a.sendLogon();   // seq 5
        second.pump();
        assertTrue(second.b.isEstablished());
        assertEquals(3, venue2.orders.size(), "the orders are resent, not gap-filled away");
        assertEquals("ord-1", venue2.orders.get(0).clOrdId());
        assertEquals("ord-3", venue2.orders.get(2).clOrdId());
        assertEquals(6, second.b.expectedIncomingSeqNum());
        reopened.close();
    }

    @Test
    void resendBelowTheRetentionWindowIsReported() {
        Events clientEvents = new Events();
        Link link = link(FixSessionStore.inMemory(2), FixSessionStore.inMemory(),
                clientEvents, new Events());
        for (int i = 1; i <= 4; i++) {
            link.a.sendNewOrderSingle("o" + i, "EURUSD", com.quantfinlib.orderbook.Side.BUY, 1, 1.5, '0');
        }
        link.pump();
        link.b.send(FixMessage.builder(FixMessage.RESEND_REQUEST)
                .field(FixMessage.BEGIN_SEQ_NO, 2).field(FixMessage.END_SEQ_NO, 0));
        link.pump();
        assertEquals(1, clientEvents.unavailable.size());
        assertEquals(2, clientEvents.unavailable.getFirst()[0]);
        assertEquals(3, clientEvents.unavailable.getFirst()[1]);
    }

    @Test
    void batchSendSharesOneStoreSync() {
        int[] syncs = {0};
        FixSessionStore store = new DelegatingStore(FixSessionStore.inMemory(100)) {
            @Override
            public void sync() {
                syncs[0]++;
            }
        };
        Events venueEvents = new Events();
        Link link = link(store, FixSessionStore.inMemory(), new Events(), venueEvents);
        int before = syncs[0];
        link.a.sendBatch(List.of(order("b1"), order("b2"), order("b3")));
        assertEquals(before + 1, syncs[0]);
        link.pump();
        assertEquals(3, venueEvents.orders.size());
    }

    @Test
    void resetSeqNumLogonRollsTheAcceptorsStore(@TempDir Path dir) throws Exception {
        FileSessionStore venueStore = new FileSessionStore(dir);
        venueStore.saveIncomingSeq(40);
        venueStore.saveOutgoingSeq(30);
        Events venueEvents = new Events();
        Link link = new Link();
        link.a = FixSession.overStream(CLIENT.withResetOnLogon(), new Events(), false,
                FixSessionStore.inMemory(), link.aOut, link.clock);
        link.b = FixSession.overStream(VENUE, venueEvents, true, venueStore, link.bOut, link.clock);
        link.a.sendLogon();
        link.pump();
        assertTrue(link.a.isEstablished() && link.b.isEstablished());
        assertEquals("Y", link.bToA.getFirst().getString(FixMessage.RESET_SEQ_NUM_FLAG));
        assertEquals("1", link.bToA.getFirst().getString(FixMessage.MSG_SEQ_NUM));
        assertEquals(2, link.b.expectedIncomingSeqNum());
        assertTrue(venueEvents.gaps.isEmpty());
        venueStore.close();
    }

    // ------------------------------------------------------------------

    private static byte[] frame(String afterLength) {
        String head = "8=FIX.4.4\u00019=" + afterLength.length() + "\u0001";
        byte[] payload = (head + afterLength).getBytes(StandardCharsets.ISO_8859_1);
        int sum = 0;
        for (byte b : payload) {
            sum += b & 0xFF;
        }
        return (head + afterLength + String.format("10=%03d\u0001", sum % 256))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Store wrapper for counting calls. */
    private static class DelegatingStore implements FixSessionStore {
        private final FixSessionStore d;

        DelegatingStore(FixSessionStore d) {
            this.d = d;
        }

        @Override
        public long nextOutgoingSeq() {
            return d.nextOutgoingSeq();
        }

        @Override
        public long expectedIncomingSeq() {
            return d.expectedIncomingSeq();
        }

        @Override
        public void saveOutgoingSeq(long nextSeq) {
            d.saveOutgoingSeq(nextSeq);
        }

        @Override
        public void saveIncomingSeq(long expectedSeq) {
            d.saveIncomingSeq(expectedSeq);
        }

        @Override
        public void storeMessage(long seq, StoredMessage message) {
            d.storeMessage(seq, message);
        }

        @Override
        public StoredMessage retrieve(long seq) {
            return d.retrieve(seq);
        }
    }
}

package com.quantfinlib.fix;

import com.quantfinlib.fix.FixTestKit.Events;
import com.quantfinlib.fix.FixTestKit.Link;
import com.quantfinlib.fix.FixTestKit.ManualClock;
import com.quantfinlib.fix.FixTestKit.Sink;
import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session schedules (daily, weekly, overnight, DST) and the Logon
 * extension NextExpectedMsgSeqNum(789), driven by the manual clock.
 * Expected instants are worked by hand from the zone rules: London is UTC+1
 * in July (BST), New York UTC-4 until 2026-11-01 02:00 local, UTC-5 after.
 */
class FixSessionScheduleTest {

    private static final FixSession.Config VENUE = new FixSession.Config("VENUE", "CLIENT", 30);
    private static final FixSession.Config CLIENT = new FixSession.Config("CLIENT", "VENUE", 30);
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---- schedule arithmetic -------------------------------------------------

    @Test
    void dailyLondonWindow() {
        FixSessionSchedule s = FixSessionSchedule.daily(LocalTime.of(7, 0), LocalTime.of(17, 30), LONDON);
        assertFalse(s.isInSession(t("2026-07-10T05:59:59Z")));
        assertTrue(s.isInSession(t("2026-07-10T06:00:00Z")));          // 07:00 BST
        assertTrue(s.isInSession(t("2026-07-10T16:29:59.999Z")));
        assertFalse(s.isInSession(t("2026-07-10T16:30:00Z")));         // 17:30 BST
        assertEquals(t("2026-07-10T06:00:00Z"), s.sessionStartMillis(t("2026-07-10T20:00:00Z")));
        assertEquals(t("2026-07-09T06:00:00Z"), s.sessionStartMillis(t("2026-07-10T05:00:00Z")));
        assertEquals(t("2026-07-10T16:30:00Z"), s.sessionEndMillis(t("2026-07-10T09:00:00Z")));
        assertFalse(s.isWeekly());
        assertTrue(s.toString().startsWith("daily"));
    }

    @Test
    void overnightFxDayRollsAtFivePmNewYork() {
        FixSessionSchedule s = FixSessionSchedule.daily(LocalTime.of(17, 0), LocalTime.of(17, 0), NEW_YORK);
        long before = t("2026-07-10T20:59:59Z");
        long after = t("2026-07-10T21:00:00Z");
        assertEquals(t("2026-07-09T21:00:00Z"), s.sessionStartMillis(before));
        assertEquals(t("2026-07-10T21:00:00Z"), s.sessionStartMillis(after));
        assertTrue(s.isInSession(before) && s.isInSession(after), "a 24h session is always open");
        assertFalse(s.isSameSession(before, after));
        assertTrue(s.isSameSession(after, t("2026-07-11T20:00:00Z")));
    }

    @Test
    void dstChangeMakesTheNewYorkDay25HoursLong() {
        FixSessionSchedule s = FixSessionSchedule.daily(LocalTime.of(17, 0), LocalTime.of(17, 0), NEW_YORK);
        long sat = t("2026-10-31T22:00:00Z");
        assertEquals(t("2026-10-31T21:00:00Z"), s.sessionStartMillis(sat));   // 17:00 EDT
        assertEquals(t("2026-11-01T22:00:00Z"), s.sessionEndMillis(sat));     // 17:00 EST
        assertEquals(t("2026-11-01T22:00:00Z"), s.sessionStartMillis(t("2026-11-01T22:00:00Z")));
    }

    @Test
    void weeklySundayToFriday() {
        FixSessionSchedule s = FixSessionSchedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(17, 0),
                DayOfWeek.FRIDAY, LocalTime.of(17, 0), NEW_YORK);
        long saturday = t("2026-07-11T12:00:00Z");
        assertFalse(s.isInSession(saturday));
        assertEquals(t("2026-07-05T21:00:00Z"), s.sessionStartMillis(saturday));
        assertEquals(t("2026-07-10T21:00:00Z"), s.sessionEndMillis(saturday));
        assertTrue(s.isInSession(t("2026-07-08T03:00:00Z")));           // Tuesday night
        assertTrue(s.isInSession(t("2026-07-12T21:00:00Z")));           // Sunday 17:00
        assertEquals(t("2026-07-12T21:00:00Z"), s.sessionStartMillis(t("2026-07-12T21:00:00Z")));
        assertTrue(s.isWeekly());
        assertEquals(DayOfWeek.SUNDAY, s.startDay());
        assertEquals(DayOfWeek.FRIDAY, s.endDay());
        assertEquals(NEW_YORK, s.zone());
        assertEquals(LocalTime.of(17, 0), s.startTime());
        assertEquals(LocalTime.of(17, 0), s.endTime());
        assertTrue(s.toString().startsWith("weekly"));
    }

    @Test
    void weeklySameDayAndTimeIsAFullWeek() {
        FixSessionSchedule s = FixSessionSchedule.weekly(DayOfWeek.MONDAY, LocalTime.of(0, 0),
                DayOfWeek.MONDAY, LocalTime.of(0, 0), ZoneOffset.UTC);
        long wed = t("2026-07-08T12:00:00Z");
        assertTrue(s.isInSession(wed));
        assertEquals(t("2026-07-06T00:00:00Z"), s.sessionStartMillis(wed));
        assertEquals(t("2026-07-13T00:00:00Z"), s.sessionEndMillis(wed));
    }

    // ---- session behaviour ---------------------------------------------------

    /** One session over a sink, the peer scripted as CLIENT to VENUE. */
    private static final class Peer {
        final ManualClock clock;
        final Sink sink = new Sink();
        final Events events = new Events();
        final FixSession session;

        Peer(FixSession.Config config, boolean acceptor, FixSessionStore store,
             FixSessionOptions options, String nowIso) {
            this.clock = new ManualClock();
            clock.advanceMillis(t(nowIso) - clock.millis);
            this.session = FixSession.overStream(config, events, acceptor, store, sink, clock, options);
        }

        void deliver(FixMessage.Builder b, long seq) {
            byte[] bytes = b.encode("CLIENT", "VENUE", seq, clock.utc());
            session.onBytes(bytes, 0, bytes.length);
        }

        List<FixMessage> sent() {
            return FixTestKit.parseAll(sink.drain());
        }
    }

    private static FixSessionOptions utcWindow(int startHour, int endHour, int endMinute) {
        return FixSessionOptions.none().withSchedule(FixSessionSchedule.daily(
                LocalTime.of(startHour, 0), LocalTime.of(endHour, endMinute), ZoneOffset.UTC));
    }

    private static final String NOON = "2026-07-10T12:00:00Z";

    @Test
    void acceptorRefusesLogonOutsideWindow() {
        Peer v = new Peer(VENUE, true, FixSessionStore.inMemory(), utcWindow(13, 17, 0), NOON);
        v.deliver(FixTestKit.logon(30), 1);
        FixMessage logout = v.sent().getFirst();
        assertEquals(FixMessage.LOGOUT, logout.msgType());
        assertTrue(logout.getString(FixMessage.TEXT).contains("outside session window"));
        assertFalse(v.session.isEstablished());
        assertNotNull(v.events.disconnect);
    }

    @Test
    void initiatorWillNotSendLogonOutsideWindow() {
        Peer c = new Peer(CLIENT, false, FixSessionStore.inMemory(), utcWindow(13, 17, 0), NOON);
        assertThrows(IllegalStateException.class, c.session::sendLogon);
        assertEquals(0, c.sent().size());
    }

    @Test
    void endOfSessionLogsOutAndNextDayStartsFromOne() {
        FixSessionStore store = FixSessionStore.inMemory();
        Peer v = new Peer(VENUE, true, store, utcWindow(8, 12, 30), NOON);
        v.deliver(FixTestKit.logon(0), 1);   // HeartBtInt 0: no heartbeat timeout while the clock jumps
        v.deliver(FixTestKit.order("o1"), 2);
        assertTrue(v.session.isEstablished());
        assertEquals(1, v.events.orders.size());
        v.sent();
        v.clock.advanceMillis(29 * 60_000L);
        v.session.onTimer();
        assertTrue(v.session.isEstablished(), "12:29 is still in session");
        v.sent();
        v.clock.advanceMillis(60_000L);                                     // 12:30
        v.session.onTimer();
        FixMessage logout = v.sent().getLast();
        assertEquals(FixMessage.LOGOUT, logout.msgType());
        assertEquals("end of session", logout.getString(FixMessage.TEXT));
        assertFalse(v.session.isEstablished());
        assertEquals(3, store.expectedIncomingSeq());

        // Same day after the window: refused, numbers kept.
        Peer late = new Peer(VENUE, true, store, utcWindow(8, 12, 30), "2026-07-10T12:40:00Z");
        late.deliver(FixTestKit.logon(30), 3);
        assertFalse(late.session.isEstablished());
        assertEquals(3, store.expectedIncomingSeq());

        // Next morning: the store rolls on construction and the peer starts at 1.
        Peer tomorrow = new Peer(VENUE, true, store, utcWindow(8, 12, 30), "2026-07-11T08:05:00Z");
        assertEquals(1, store.expectedIncomingSeq());
        assertEquals(1, store.nextOutgoingSeq());
        tomorrow.deliver(FixTestKit.logon(30), 1);
        assertTrue(tomorrow.session.isEstablished());
        assertEquals("1", tomorrow.sent().getFirst().getString(FixMessage.MSG_SEQ_NUM));
    }

    @Test
    void storeOfUnknownPeriodIsAdoptedThenRolledByTheTimerAtSessionStart() {
        FixSessionStore store = FixSessionStore.inMemory();
        store.saveOutgoingSeq(55);
        store.saveIncomingSeq(55);
        Peer v = new Peer(VENUE, true, store, utcWindow(13, 17, 0), NOON);
        assertEquals(55, store.nextOutgoingSeq(), "unknown period: adopted, not reset");
        assertEquals(t("2026-07-09T13:00:00Z"), store.sessionStartMillis());
        v.session.onTimer();
        assertEquals(55, store.nextOutgoingSeq(), "12:00 is outside the window: no roll yet");
        v.clock.advanceMillis(60 * 60_000L);                                // 13:00
        v.session.onTimer();
        assertEquals(1, store.nextOutgoingSeq());
        assertEquals(1, store.expectedIncomingSeq());
        assertEquals(t("2026-07-10T13:00:00Z"), store.sessionStartMillis());
        assertEquals(1, v.session.nextOutgoingSeqNum());
        assertEquals(1, v.session.expectedIncomingSeqNum());
    }

    @Test
    void initiatorRollsBeforeItsLogon() {
        FixSessionStore store = FixSessionStore.inMemory();
        store.saveSessionStartMillis(t("2026-07-09T08:00:00Z"));
        store.saveOutgoingSeq(9);
        Peer c = new Peer(CLIENT, false, store, utcWindow(8, 17, 0), NOON);
        c.session.sendLogon();
        FixMessage logon = c.sent().getFirst();
        assertEquals("1", logon.getString(FixMessage.MSG_SEQ_NUM));
    }

    @Test
    void fileStoreKeepsTheSessionPeriodAcrossReopen(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(FixSessionStore.UNKNOWN_SESSION_START, store.sessionStartMillis());
            store.saveSessionStartMillis(t("2026-07-10T08:00:00Z"));
        }
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(t("2026-07-10T08:00:00Z"), store.sessionStartMillis());
        }
        java.nio.file.Files.writeString(dir.resolve("session.period"), "garbage");
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(FixSessionStore.UNKNOWN_SESSION_START, store.sessionStartMillis());
        }
    }

    @Test
    void fileStoreRestartNextDayResets(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            Peer v = new Peer(VENUE, true, store, utcWindow(8, 17, 0), NOON);
            v.deliver(FixTestKit.logon(30), 1);
            v.deliver(FixTestKit.order("o1"), 2);
            assertEquals(3, store.expectedIncomingSeq());
        }
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(3, store.expectedIncomingSeq());
            new Peer(VENUE, true, store, utcWindow(8, 17, 0), "2026-07-10T16:00:00Z");
            assertEquals(3, store.expectedIncomingSeq(), "same day: resume");
            new Peer(VENUE, true, store, utcWindow(8, 17, 0), "2026-07-11T09:00:00Z");
            assertEquals(1, store.expectedIncomingSeq(), "next day: roll");
            assertEquals(1, store.nextOutgoingSeq());
        }
    }

    // ---- NextExpectedMsgSeqNum (789) ----------------------------------------

    private static final FixSessionOptions WITH_789 = FixSessionOptions.none().withNextExpectedMsgSeqNum();

    @Test
    void optionsAccessors() {
        assertFalse(FixSessionOptions.none().nextExpectedMsgSeqNum());
        assertEquals(null, FixSessionOptions.none().schedule());
        assertTrue(WITH_789.nextExpectedMsgSeqNum());
    }

    @Test
    void replyCarries789WhenThePeerSentIt() {
        Peer v = new Peer(VENUE, true, FixSessionStore.inMemory(), FixSessionOptions.none(), NOON);
        v.deliver(FixTestKit.logon(30).field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, 1), 1);
        FixMessage reply = v.sent().getFirst();
        assertEquals(FixMessage.LOGON, reply.msgType());
        assertEquals("2", reply.getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM));
        assertTrue(v.session.isEstablished());
    }

    @Test
    void peer789AboveOurNextNumberIsRefused() {
        Peer v = new Peer(VENUE, true, FixSessionStore.inMemory(), WITH_789, NOON);
        v.deliver(FixTestKit.logon(30).field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, 5), 1);
        FixMessage logout = v.sent().getFirst();
        assertEquals(FixMessage.LOGOUT, logout.msgType());
        assertTrue(logout.getString(FixMessage.TEXT).contains("789"));
        assertFalse(v.session.isEstablished());
    }

    @Test
    void malformed789IsRefused() {
        Peer v = new Peer(VENUE, true, FixSessionStore.inMemory(), WITH_789, NOON);
        v.deliver(FixTestKit.logon(30).field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, "x"), 1);
        assertEquals(FixMessage.LOGOUT, v.sent().getFirst().msgType());
        assertFalse(v.session.isEstablished());
    }

    @Test
    void peerThatSkipsTheReplayGetsAResendRequest() {
        Peer v = new Peer(VENUE, true, FixSessionStore.inMemory(), WITH_789, NOON);
        v.deliver(FixTestKit.logon(30).field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, 1), 3);
        List<FixMessage> first = v.sent();
        assertEquals(1, first.size(), "Logon reply only, no ResendRequest");
        assertEquals("1", first.getFirst().getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM));
        v.deliver(FixMessage.builder(FixMessage.HEARTBEAT), 4);
        FixMessage rr = v.sent().getFirst();
        assertEquals(FixMessage.RESEND_REQUEST, rr.msgType());
        assertEquals("1", rr.getString(FixMessage.BEGIN_SEQ_NO));
    }

    @Test
    void reconnectRecoversBothDirectionsFrom789WithoutResendRequests() {
        FixSessionStore clientStore = FixSessionStore.inMemory();
        FixSessionStore venueStore = FixSessionStore.inMemory();
        Link first = new Link();
        first.a = FixSession.overStream(CLIENT, new Events(), false, clientStore, first.aOut,
                first.clock, WITH_789);
        first.b = FixSession.overStream(VENUE, new Events(), true, venueStore, first.bOut,
                first.clock, WITH_789);
        first.a.sendLogon();
        first.pump();
        assertTrue(first.a.isEstablished() && first.b.isEstablished());
        assertEquals("1", first.aToB.getFirst().getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM));
        assertEquals("2", first.bToA.getFirst().getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM));
        // Two orders each way are written and lost with the connection.
        first.a.sendNewOrderSingle("c1", "EURUSD", Side.BUY, 1, 1.5, '0');
        first.a.sendNewOrderSingle("c2", "EURUSD", Side.BUY, 1, 1.5, '0');
        first.b.sendNewOrderSingle("v1", "EURUSD", Side.SELL, 1, 1.5, '0');
        first.b.sendNewOrderSingle("v2", "EURUSD", Side.SELL, 1, 1.5, '0');
        first.aOut.drain();
        first.bOut.drain();

        Events clientEvents = new Events();
        Events venueEvents = new Events();
        Link second = new Link();
        second.a = FixSession.overStream(CLIENT, clientEvents, false, clientStore, second.aOut,
                second.clock, WITH_789);
        second.b = FixSession.overStream(VENUE, venueEvents, true, venueStore, second.bOut,
                second.clock, WITH_789);
        second.a.sendLogon();
        second.pump();

        FixMessage clientLogon = second.aToB.getFirst();
        assertEquals("4", clientLogon.getString(FixMessage.MSG_SEQ_NUM));
        assertEquals("2", clientLogon.getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM));
        FixMessage venueLogon = second.bToA.getFirst();
        assertEquals("4", venueLogon.getString(FixMessage.MSG_SEQ_NUM));
        assertEquals("2", venueLogon.getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM));
        assertTrue(second.aToB.stream().noneMatch(m -> FixMessage.RESEND_REQUEST.equals(m.msgType())));
        assertTrue(second.bToA.stream().noneMatch(m -> FixMessage.RESEND_REQUEST.equals(m.msgType())));
        assertEquals(List.of("c1", "c2"), venueEvents.orders.stream().map(NewOrderSingle::clOrdId).toList());
        assertEquals(List.of("v1", "v2"), clientEvents.orders.stream().map(NewOrderSingle::clOrdId).toList());
        assertEquals(5, second.a.expectedIncomingSeqNum());
        assertEquals(5, second.b.expectedIncomingSeqNum());
        assertEquals(1, venueEvents.gaps.size());
        assertEquals(1, clientEvents.gaps.size());

        // Traffic continues in sequence.
        second.a.sendNewOrderSingle("c3", "EURUSD", Side.BUY, 1, 1.5, '0');
        second.pump();
        assertEquals(3, venueEvents.orders.size());
        assertTrue(second.a.isEstablished() && second.b.isEstablished());
    }

    @Test
    void initiatorDisconnectsWhenTheReply789IsTooHigh() {
        Peer c = new Peer(CLIENT, false, FixSessionStore.inMemory(), WITH_789, NOON);
        c.session.sendLogon();
        c.sent();
        byte[] reply = FixTestKit.logon(30).field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, 7)
                .encode("VENUE", "CLIENT", 1, c.clock.utc());
        c.session.onBytes(reply, 0, reply.length);
        assertEquals(FixMessage.LOGOUT, c.sent().getLast().msgType());
        assertNotNull(c.events.disconnect);
    }
}

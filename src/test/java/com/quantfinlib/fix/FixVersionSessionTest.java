package com.quantfinlib.fix;

import com.quantfinlib.fix.FixTestKit.Events;
import com.quantfinlib.fix.FixTestKit.Link;
import com.quantfinlib.fix.FixTestKit.Scripted;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixVersionSessionTest {

    private static final FixSession.Config VENUE = new FixSession.Config("VENUE", "CLIENT", 30);
    private static final FixSession.Config CLIENT = new FixSession.Config("CLIENT", "VENUE", 30);

    private static Link link(FixVersion v, Events clientEvents, Events venueEvents) {
        FixSessionOptions o = FixSessionOptions.none().withVersion(v);
        Link link = new Link();
        link.a = FixSession.overStream(CLIENT, clientEvents, false, FixSessionStore.inMemory(),
                link.aOut, link.clock, o);
        link.b = FixSession.overStream(VENUE, venueEvents, true, FixSessionStore.inMemory(),
                link.bOut, link.clock, o);
        link.a.sendLogon();
        link.pump();
        return link;
    }

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void logonAndOrderFlowInEachVersion(FixVersion v) {
        Events clientEvents = new Events();
        Events venueEvents = new Events();
        Link link = link(v, clientEvents, venueEvents);
        assertTrue(link.a.isEstablished() && link.b.isEstablished());
        assertEquals(v, link.a.version());
        FixMessage logon = link.aToB.get(0);
        assertEquals(v.beginString(), logon.beginString());
        assertEquals(v.applVerId(), logon.getString(FixVersion.DEFAULT_APPL_VER_ID, null));
        assertEquals(v.applVerId(), link.bToA.get(0)
                .getString(FixVersion.DEFAULT_APPL_VER_ID, null));
        assertEquals(v.applVerId(), link.b.peerDefaultApplVerId());
        assertEquals(v.applVerId(), link.a.peerDefaultApplVerId());

        link.a.sendNewOrderSingle("o1", "EURUSD", Side.BUY, 100, 1.5, '0');
        link.pump();
        assertEquals(1, venueEvents.orders.size());
        assertTrue(link.aToB.stream().allMatch(m -> m.version() == v));
    }

    @Test
    void defaultIsFix44WithoutApplVerId() {
        Link link = new Link();
        link.a = FixSession.overStream(CLIENT, new Events(), false, FixSessionStore.inMemory(),
                link.aOut, link.clock);
        link.b = FixSession.overStream(VENUE, new Events(), true, FixSessionStore.inMemory(),
                link.bOut, link.clock);
        link.a.sendLogon();
        link.pump();
        assertTrue(link.b.isEstablished());
        assertEquals(FixVersion.FIX_4_4, link.b.version());
        assertEquals("FIX.4.4", link.aToB.get(0).beginString());
        assertFalse(link.aToB.get(0).has(FixVersion.DEFAULT_APPL_VER_ID));
        assertNull(link.b.peerDefaultApplVerId());
        assertEquals(FixVersion.FIX_4_4, new FixSessionOptions(null, false).version());
    }

    @Test
    void versionMismatchIsRefused() {
        Events venueEvents = new Events();
        Link link = new Link();
        link.a = FixSession.overStream(CLIENT, new Events(), false, FixSessionStore.inMemory(),
                link.aOut, link.clock, FixSessionOptions.none().withVersion(FixVersion.FIX_4_2));
        link.b = FixSession.overStream(VENUE, venueEvents, true, FixSessionStore.inMemory(),
                link.bOut, link.clock);
        link.a.sendLogon();
        link.pump();
        assertFalse(link.b.isEstablished());
        assertNotNull(venueEvents.disconnect);
        assertTrue(venueEvents.disconnect.contains("BeginString"), venueEvents.disconnect);
    }

    @Test
    void fixtLogonWithoutOrWithWrongDefaultApplVerIdIsRefused() {
        for (String applVer : new String[]{null, "7"}) {
            Events events = new Events();
            Scripted venue = new Scripted(VENUE, true, FixSessionStore.inMemory(), events);
            FixSession s = FixSession.overStream(VENUE, events, true, FixSessionStore.inMemory(),
                    venue.sink, venue.clock,
                    FixSessionOptions.none().withVersion(FixVersion.FIX_5_0_SP2));
            FixMessage.Builder logon = FixTestKit.logon(30);
            if (applVer != null) {
                logon.field(FixVersion.DEFAULT_APPL_VER_ID, applVer);
            }
            byte[] wire = logon.encode(FixVersion.FIX_5_0_SP2, "CLIENT", "VENUE", 1,
                    venue.clock.utc(), false, null);
            s.onBytes(wire, 0, wire.length);
            assertFalse(s.isEstablished());
            List<FixMessage> sent = FixTestKit.parseAll(venue.sink.drain());
            assertEquals(FixMessage.LOGOUT, sent.get(sent.size() - 1).msgType());
            assertTrue(sent.get(sent.size() - 1).getString(FixMessage.TEXT).contains("1137"));
        }
    }

    @Test
    void fix42ExecutionReportCarriesExecTransType() {
        Events clientEvents = new Events();
        Link link = link(FixVersion.FIX_4_2, clientEvents, new Events());
        link.a.sendNewOrderSingle("o1", "EURUSD", Side.BUY, 100, 1.5, '0');
        link.pump();
        link.bToA.clear();
        link.b.sendExecutionReport(new ExecutionReport("ord1", "x1", '0', '0', "o1",
                "EURUSD", Side.BUY, 0, 0, 0, 100, 0));
        link.pump();
        FixMessage er = link.bToA.get(0);
        assertEquals("FIX.4.2", er.beginString());
        assertEquals("0", er.getString(FixVersion.EXEC_TRANS_TYPE));
        assertEquals(1, clientEvents.reports.size());
    }
}

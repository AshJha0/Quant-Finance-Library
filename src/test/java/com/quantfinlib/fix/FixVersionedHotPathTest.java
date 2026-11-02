package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version-aware hot path: the encoder writes FIX 4.2, 4.4 and FIXT.1.1, the
 * views read repeating groups through {@link FixDictionary} and map FIX 4.2
 * execution semantics to 4.4. Every encoded message is checked by the
 * validating {@link FixMessage#parse} (BodyLength and CheckSum).
 */
class FixVersionedHotPathTest {

    private static final long TS = 1_800_000_000_000L;

    private static byte[] fix(String s) {
        return s.replace('|', '\u0001').getBytes(StandardCharsets.US_ASCII);
    }

    private static FixOrderEncoder encoder(FixVersion v) {
        return new FixOrderEncoder("QFL", "VENUE", 4, 512, v).registerSymbol(0, "EURUSD");
    }

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void everyMessageTypeCarriesTheVersionBeginString(FixVersion v) {
        FixOrderEncoder enc = encoder(v);
        enc.encodeLimit(1, 11, 0, Side.BUY, 100, 108505, 5, TS);
        FixMessage d = FixMessage.parse(enc.toByteArray());
        assertEquals(v.beginString(), d.getString(8));
        assertEquals("1.08505", d.getString(FixMessage.PRICE));
        enc.encodeCancel(2, 12, 11, 0, Side.BUY, 100, TS);
        assertEquals(v.beginString(), FixMessage.parse(enc.toByteArray()).getString(8));
        enc.encodeReplace(3, 13, 12, 0, Side.SELL, 50, 1085, 3, TS);
        assertEquals(v.beginString(), FixMessage.parse(enc.toByteArray()).getString(8));
        assertEquals(v, enc.version());
    }

    @Test
    void handlInstOnlyOnFix42NewOrderSingle() {
        FixOrderEncoder e42 = encoder(FixVersion.FIX_4_2);
        e42.encodeMarket(1, 11, 0, Side.BUY, 100, TS);
        assertEquals("1", FixMessage.parse(e42.toByteArray()).getString(21, null));
        FixOrderEncoder e44 = encoder(FixVersion.FIX_4_4);
        e44.encodeMarket(1, 11, 0, Side.BUY, 100, TS);
        assertNull(FixMessage.parse(e44.toByteArray()).getString(21, null));
    }

    @Test
    void defaultConstructorStaysFix44ByteForByte() {
        FixOrderEncoder legacy = new FixOrderEncoder("QFL", "VENUE", 4, 512).registerSymbol(0, "EURUSD");
        FixOrderEncoder v44 = encoder(FixVersion.FIX_4_4);
        legacy.encodeLimit(7, 99, 0, Side.SELL, 5, 1, 0, TS);
        v44.encodeLimit(7, 99, 0, Side.SELL, 5, 1, 0, TS);
        assertEquals(new String(legacy.toByteArray(), StandardCharsets.US_ASCII),
                new String(v44.toByteArray(), StandardCharsets.US_ASCII));
    }

    @Test
    void perMessageApplVerIdFollowsMsgType() {
        FixOrderEncoder enc = encoder(FixVersion.FIX_5_0_SP2).applVerId("9");
        enc.encodeLimit(1, 11, 0, Side.BUY, 100, 108505, 5, TS);
        String wire = new String(enc.toByteArray(), StandardCharsets.US_ASCII).replace('\u0001', '|');
        assertTrue(wire.startsWith("8=FIXT.1.1|9="), wire);
        assertTrue(wire.contains("|35=D|1128=9|49=QFL|"), wire);
        FixMessage.parse(enc.toByteArray());   // checksum and length still valid

        enc.applVerId(null);
        enc.encodeLimit(2, 12, 0, Side.BUY, 100, 108505, 5, TS);
        assertNull(FixMessage.parse(enc.toByteArray()).getString(FixVersion.APPL_VER_ID, null));
        assertThrows(IllegalStateException.class, () -> encoder(FixVersion.FIX_4_4).applVerId("9"));
    }

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void encoderStaysAllocationFreeInEveryVersion(FixVersion v) {
        FixOrderEncoder enc = encoder(v);
        if (v.isFixt()) {
            enc.applVerId("9");
        }
        long sink = 0;
        for (int i = 0; i < 20_000; i++) {      // warm-up
            sink += enc.encodeLimit(i, i, 0, Side.BUY, 100, 108505, 5, TS + i);
        }
        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) {
            sink += enc.encodeLimit(i, i, 0, Side.BUY, 100, 108505, 5, TS + i % 1000);
            sink += enc.encodeCancel(i, i, i, 0, Side.SELL, 100, TS + i % 1000);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, v + " allocated " + allocated + " (sink " + sink + ")");
    }

    // ---- execution reports ----

    @Test
    void fix42ExecTransTypeMapsTo44ExecType() {
        FixExecReportView view = new FixExecReportView();
        assertTrue(w(view, report("FIX.4.2", "20=0|150=1|39=1|")));
        assertEquals('F', view.execType());
        assertEquals('1', view.rawExecType());
        assertEquals(FixVersion.FIX_4_2, view.version());
        assertTrue(w(view, report("FIX.4.2", "20=1|150=2|39=2|")));
        assertEquals('H', view.execType());
        assertTrue(w(view, report("FIX.4.2", "20=2|150=2|39=2|")));
        assertEquals('G', view.execType());
        assertTrue(w(view, report("FIX.4.2", "20=3|150=0|39=0|")));
        assertEquals('I', view.execType());
        assertTrue(w(view, report("FIX.4.2", "20=0|150=4|39=4|")));
        assertEquals('4', view.execType());
        // FIX 4.4: no mapping, even for values 4.2 would have mapped
        assertTrue(w(view, report("FIX.4.4", "150=F|39=1|")));
        assertEquals('F', view.execType());
        assertEquals(0, view.execTransType());
    }

    @Test
    void partiesGroupIsVersionAware() {
        FixExecReportView view = new FixExecReportView();
        String parties = "453=2|448=BRKR|447=D|452=1|448=DESK|447=D|452=11|";
        assertTrue(w(view, report("FIX.4.4", "150=F|" + parties)));
        assertEquals(2, view.noPartyIds());
        assertEquals(2, view.partyInstances());
        assertFalse(view.partyCountMismatch());
        assertTrue(w(view, report("FIX.4.4", "150=F|453=3|448=BRKR|447=D|452=1|")));
        assertTrue(view.partyCountMismatch());
        // FIX 4.2 has no Parties component: 453 is not a group there
        assertTrue(w(view, report("FIX.4.2", "150=0|" + parties)));
        assertEquals(-1, view.noPartyIds());
    }

    @Test
    void execReportExposesApplVerId() {
        FixExecReportView view = new FixExecReportView();
        byte[] b = fix("8=FIXT.1.1|9=10|35=8|1128=9|11=5|150=F|");
        assertTrue(view.wrap(b, 0, b.length));
        assertEquals(FixVersion.FIX_5_0_SP2, view.version());
        assertTrue(view.hasApplVerId());
        assertEquals('9', view.byteAt(view.applVerIdOffset()));
        assertEquals(1, view.applVerIdLength());
    }

    private static boolean w(FixExecReportView v, byte[] b) {
        return v.wrap(b, 0, b.length);
    }

    private static byte[] report(String begin, String body) {
        return fix("8=" + begin + "|9=10|35=8|11=5|" + body);
    }

    // ---- market data ----

    @Test
    void fix50IncrementalReadsPriceLevelFromTheDictionary() {
        FixMarketDataView view = new FixMarketDataView();
        byte[] b = fix("8=FIXT.1.1|9=10|35=X|268=2|279=0|269=0|55=EURUSD|270=1.085|271=1000000|1023=1|"
                + "279=1|269=1|55=EURUSD|270=1.0852|271=2000000|1023=2|");
        assertTrue(view.wrap(b, 0, b.length));
        assertEquals(FixVersion.FIX_5_0_SP2, view.version());
        assertEquals(2, view.entryCount());
        assertEquals(1, view.priceLevel(0));
        assertEquals(2, view.priceLevel(1));
        assertEquals(FixMarketDataView.ACTION_CHANGE, view.action(1));
        assertEquals(2_000_000, view.size(1));
    }

    @Test
    void priceLevelIsNotAGroupMemberBefore50() {
        FixMarketDataView view = new FixMarketDataView();
        // 1023 ends the 4.4 group; the 270 after it is top-level and ignored
        byte[] b = fix("8=FIX.4.4|9=10|35=X|268=1|279=0|269=0|1023=1|270=1.085|");
        assertTrue(view.wrap(b, 0, b.length));
        assertEquals(1, view.entryCount());
        assertEquals(0, view.priceLevel(0));
        assertEquals(0, view.pxMantissa(0));
    }

    @Test
    void venueDictionaryAddsAnEntryField() {
        FixGroupDef custom = FixGroupDef.of("NoMDEntries", FixDictionary.NO_MD_ENTRIES,
                269, 270, 271, 5000);
        FixDictionary venue = FixDictionary.forVersion(FixVersion.FIX_4_4).with("W", custom);
        FixMarketDataView view = new FixMarketDataView(8, venue);
        byte[] b = fix("8=FIX.4.4|9=10|35=W|55=EURUSD|268=2|269=0|5000=x|270=1.08|271=5|"
                + "269=1|5000=y|270=1.09|271=6|");
        assertTrue(view.wrap(b, 0, b.length));
        assertEquals(2, view.entryCount());
        assertEquals(6, view.size(1));
    }

    @Test
    void versionAwareViewsStayAllocationFree() {
        FixMarketDataView md = new FixMarketDataView();
        FixExecReportView er = new FixExecReportView();
        byte[] x = fix("8=FIXT.1.1|9=10|35=X|1128=9|268=1|279=0|269=0|270=1.085|271=1|1023=1|");
        byte[] r = report("FIX.4.2", "20=2|150=2|39=2|");
        long sink = 0;
        for (int i = 0; i < 20_000; i++) {
            md.wrap(x, 0, x.length);
            er.wrap(r, 0, r.length);
            sink += md.priceLevel(0) + er.execType();
        }
        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 200_000; i++) {
            md.wrap(x, 0, x.length);
            er.wrap(r, 0, r.length);
            sink += md.priceLevel(0) + er.execType();
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "allocated " + allocated + " (sink " + sink + ")");
    }
}

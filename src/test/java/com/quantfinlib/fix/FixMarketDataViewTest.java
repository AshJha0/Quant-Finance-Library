package com.quantfinlib.fix;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixMarketDataViewTest {

    private static final char SOH = 1;

    private static byte[] msg(String pipeDelimited) {
        return pipeDelimited.replace('|', SOH).getBytes(StandardCharsets.US_ASCII);
    }

    private final FixMarketDataView v = new FixMarketDataView();

    @Test
    void snapshotWithTieredEntriesParses() {
        byte[] m = msg("8=FIX.4.4|9=120|35=W|55=EURUSD|268=4|"
                + "269=0|270=1.08500|271=1000000|"
                + "269=0|270=1.08498|271=5000000|"
                + "269=1|270=1.08502|271=1000000|"
                + "269=1|270=1.08504|271=5000000|10=123|");
        assertTrue(v.wrap(m, 0, m.length));
        assertTrue(v.isSnapshot());
        assertEquals(4, v.entryCount());
        assertEquals(4, v.declaredEntries());
        assertTrue(v.symbolEquals("EURUSD".getBytes(StandardCharsets.US_ASCII)));

        assertEquals(FixMarketDataView.ENTRY_BID, v.type(0));
        assertEquals(FixMarketDataView.ACTION_NEW, v.action(0));
        assertEquals(108500, v.pxMantissa(0));
        assertEquals(5, v.pxDecimals(0));
        assertEquals(1_000_000, v.size(0));
        // Entry order preserved: position is the tier.
        assertEquals(108498, v.pxMantissa(1));
        assertEquals(5_000_000, v.size(1));
        assertEquals(FixMarketDataView.ENTRY_OFFER, v.type(2));
        assertEquals(108504, v.pxMantissa(3));
    }

    @Test
    void incrementalRefreshCarriesUpdateActions() {
        byte[] m = msg("8=FIX.4.4|9=90|35=X|268=3|"
                + "279=0|269=0|55=USDJPY|270=161.505|271=2000000|"
                + "279=1|269=1|55=USDJPY|270=161.510|271=3000000|"
                + "279=2|269=0|55=USDJPY|270=161.500|271=0|10=001|");
        assertTrue(v.wrap(m, 0, m.length));
        assertFalse(v.isSnapshot());
        assertEquals(3, v.entryCount());
        assertEquals(FixMarketDataView.ACTION_NEW, v.action(0));
        assertEquals(FixMarketDataView.ACTION_CHANGE, v.action(1));
        assertEquals(FixMarketDataView.ACTION_DELETE, v.action(2));
        assertEquals(161505, v.pxMantissa(0));
        assertEquals(3, v.pxDecimals(0));
        assertEquals(FixMarketDataView.ENTRY_OFFER, v.type(1));
        assertTrue(v.symbolEquals("USDJPY".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void nonMarketDataMessagesAreRejectedImmediately() {
        byte[] exec = msg("8=FIX.4.4|9=30|35=8|55=EURUSD|10=002|");
        assertFalse(v.wrap(exec, 0, exec.length));
        byte[] heartbeat = msg("8=FIX.4.4|9=10|35=0|10=003|");
        assertFalse(v.wrap(heartbeat, 0, heartbeat.length));
    }

    @Test
    void entriesBeyondCapacityAreDroppedAndCounted() {
        FixMarketDataView small = new FixMarketDataView(2);
        byte[] m = msg("8=FIX.4.4|9=90|35=W|55=EURUSD|268=3|"
                + "269=0|270=1.1|271=1|269=0|270=1.2|271=2|269=0|270=1.3|271=3|10=1|");
        assertTrue(small.wrap(m, 0, m.length));
        assertEquals(2, small.entryCount());
        assertEquals(3, small.declaredEntries());
        assertEquals(1, small.droppedEntries());
        assertEquals(11, small.pxMantissa(0));
    }

    @Test
    void integerPricesAndFractionalZeroSizesParse() {
        byte[] m = msg("35=W|55=USDJPY|268=1|269=1|270=161|271=100.0|");
        assertTrue(v.wrap(m, 0, m.length));
        assertEquals(161, v.pxMantissa(0));
        assertEquals(0, v.pxDecimals(0));
        assertEquals(100, v.size(0));
    }

    @Test
    void deleteEntriesWithoutPriceNeverExposeStaleValues() {
        // 35=X DELETE legitimately omits 270/271; a stale price from a
        // previous message's entry at the same index must read as 0.
        byte[] full = msg("35=W|55=EURUSD|268=1|269=1|270=1.08550|271=5000000|");
        assertTrue(v.wrap(full, 0, full.length));
        assertEquals(108550, v.pxMantissa(0));
        byte[] del = msg("35=X|268=1|279=2|269=1|55=EURUSD|");
        assertTrue(v.wrap(del, 0, del.length));
        assertEquals(FixMarketDataView.ACTION_DELETE, v.action(0));
        assertEquals(0, v.pxMantissa(0), "absent price must not leak from message N-1");
        assertEquals(0, v.size(0));
    }

    @Test
    void negativePricesRoundTrip() {
        // Negative prices are real in FX (forward points, negative rates).
        byte[] m = msg("35=W|55=EURUSD|268=1|269=0|270=-0.5|271=1000000|");
        assertTrue(v.wrap(m, 0, m.length));
        assertEquals(-5, v.pxMantissa(0));
        assertEquals(1, v.pxDecimals(0));
        assertEquals(-0.5, v.price(0), 1e-12);
    }

    @Test
    void negativeSizesFailLoudly() {
        byte[] m = msg("35=W|55=EURUSD|268=1|269=0|270=1.1|271=-5|");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> v.wrap(m, 0, m.length));
    }

    @Test
    void absurdDecimalCountsClampInsteadOfCrashingTheGetter() {
        // >18 fractional digits: the message was accepted at wrap time, so
        // price() must degrade (clamped scale), never throw at read time.
        byte[] m = msg("35=W|55=EURUSD|268=1|269=0|270=0.0000000000000000000001|271=1|");
        assertTrue(v.wrap(m, 0, m.length));
        assertEquals(22, v.pxDecimals(0));
        double p = v.price(0);                            // must not throw
        assertTrue(p >= 0);
    }

    @Test
    void priceGetterConvertsPerEntryDecimals() {
        // Wire formatting varies per entry: "1.0850" vs "1.085" — the
        // double getter must scale each by its own decimals.
        byte[] m = msg("35=W|55=EURUSD|268=2|"
                + "269=0|270=1.0850|271=1|269=0|270=1.085|271=1|");
        assertTrue(v.wrap(m, 0, m.length));
        assertEquals(1.0850, v.price(0), 1e-12);
        assertEquals(1.0850, v.price(1), 1e-12);
        assertEquals(4, v.pxDecimals(0));
        assertEquals(3, v.pxDecimals(1));
    }

    @Test
    void decodingIsAllocationFree() {
        byte[] m = msg("8=FIX.4.4|9=120|35=W|55=EURUSD|268=4|"
                + "269=0|270=1.08500|271=1000000|"
                + "269=0|270=1.08498|271=5000000|"
                + "269=1|270=1.08502|271=1000000|"
                + "269=1|270=1.08504|271=5000000|10=123|");
        long checksum = 0;
        for (int i = 0; i < 200_000; i++) {              // warm-up
            v.wrap(m, 0, m.length);
            checksum += v.pxMantissa(i & 3);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            v.wrap(m, 0, m.length);
            checksum += v.pxMantissa(i & 3);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "MD decode allocated " + allocated + " bytes");
        assertTrue(checksum != 0);
    }
}

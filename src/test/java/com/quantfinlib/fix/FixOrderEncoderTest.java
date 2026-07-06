package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The garbage-free NewOrderSingle encoder, proven against the validated
 * String-based codec: every encoded message must parse through
 * {@link FixMessage#parse} (which verifies BodyLength and CheckSum) with
 * identical field values — the readable codec is the executable
 * specification of the fast one. Plus scaled-price rendering, the cached
 * timestamp's day rollover, and the allocation proof.
 */
class FixOrderEncoderTest {

    private static FixOrderEncoder encoder() {
        return new FixOrderEncoder("QFL", "VENUE", 16, 512)
                .registerSymbol(0, "EURUSD")
                .registerSymbol(1, "USDJPY");
    }

    private static FixMessage roundTrip(FixOrderEncoder enc) {
        // FixMessage.parse validates 9= and 10= — a framing bug fails here.
        return FixMessage.parse(Arrays.copyOfRange(
                enc.buffer(), enc.offset(), enc.offset() + enc.length()));
    }

    @Test
    void limitOrderRoundTripsThroughTheValidatedParser() {
        FixOrderEncoder enc = encoder();
        long ts = 1_800_000_000_000L; // 2027-01-15 08:00:00 UTC
        int len = enc.encodeLimit(42, 987654321L, 0, Side.BUY, 1_000_000,
                108505, 5, ts);
        assertEquals(len, enc.length());

        FixMessage m = roundTrip(enc);
        assertEquals("D", m.getString(FixMessage.MSG_TYPE));
        assertEquals("QFL", m.getString(FixMessage.SENDER_COMP_ID));
        assertEquals("VENUE", m.getString(FixMessage.TARGET_COMP_ID));
        assertEquals(42, m.getLong(FixMessage.MSG_SEQ_NUM));
        assertEquals("987654321", m.getString(FixMessage.CL_ORD_ID));
        assertEquals("EURUSD", m.getString(FixMessage.SYMBOL));
        assertEquals("1", m.getString(FixMessage.SIDE));
        assertEquals(1_000_000, m.getLong(FixMessage.ORDER_QTY));
        assertEquals("2", m.getString(FixMessage.ORD_TYPE));     // limit
        assertEquals("1.08505", m.getString(FixMessage.PRICE));  // exact rendering
        // Timestamp format: yyyyMMdd-HH:mm:ss.SSS.
        String sending = m.getString(FixMessage.SENDING_TIME);
        assertTrue(sending.matches("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"), sending);
        assertEquals(sending, m.getString(FixMessage.TRANSACT_TIME));
    }

    @Test
    void marketOrderOmitsThePriceTag() {
        FixOrderEncoder enc = encoder();
        enc.encodeMarket(7, 1, 1, Side.SELL, 500_000, 1_800_000_000_000L);
        FixMessage m = roundTrip(enc);
        assertEquals("1", m.getString(FixMessage.ORD_TYPE));     // market
        assertEquals("2", m.getString(FixMessage.SIDE));
        assertEquals("USDJPY", m.getString(FixMessage.SYMBOL));
        assertFalse(m.has(FixMessage.PRICE));
    }

    @Test
    void scaledPricesRenderExactly() {
        FixOrderEncoder enc = encoder();
        // Sub-1 price: leading zero and full zero-padding of the fraction.
        enc.encodeLimit(1, 1, 0, Side.BUY, 1, 505, 5, 1_800_000_000_000L);
        assertEquals("0.00505", roundTrip(enc).getString(FixMessage.PRICE));
        // JPY-style 3 decimals.
        enc.encodeLimit(2, 2, 1, Side.BUY, 1, 155_123, 3, 1_800_000_000_000L);
        assertEquals("155.123", roundTrip(enc).getString(FixMessage.PRICE));
        // Zero decimals: plain integer.
        enc.encodeLimit(3, 3, 0, Side.BUY, 1, 99, 0, 1_800_000_000_000L);
        assertEquals("99", roundTrip(enc).getString(FixMessage.PRICE));
    }

    @Test
    void timestampDateCacheRollsAtUtcMidnight() {
        FixOrderEncoder enc = encoder();
        long day = 86_400_000L;
        long midnightEve = 20_000L * day - 1;       // 23:59:59.999
        enc.encodeLimit(1, 1, 0, Side.BUY, 1, 108505, 5, midnightEve);
        String before = roundTrip(enc).getString(FixMessage.SENDING_TIME);
        enc.encodeLimit(2, 2, 0, Side.BUY, 1, 108505, 5, midnightEve + 1);
        String after = roundTrip(enc).getString(FixMessage.SENDING_TIME);
        assertTrue(before.endsWith("23:59:59.999"), before);
        assertTrue(after.endsWith("00:00:00.000"), after);
        // The DATE part must have advanced with the rollover.
        assertFalse(before.substring(0, 8).equals(after.substring(0, 8)));
    }

    @Test
    void validation() {
        FixOrderEncoder enc = encoder();
        assertThrows(IllegalStateException.class,
                () -> enc.encodeLimit(1, 1, 9, Side.BUY, 1, 1, 1, 0)); // unregistered
        assertThrows(IllegalArgumentException.class,
                () -> new FixOrderEncoder("", "V", 4, 512));
        assertThrows(IllegalArgumentException.class,
                () -> new FixOrderEncoder("S", "V", 4, 64));           // buffer too small
    }

    @Test
    void encodeLoopIsAllocationFree() {
        FixOrderEncoder enc = encoder();
        // JIT warmup (also crosses NO day boundary, so the date cache holds).
        long base = 1_800_000_000_000L;
        for (int i = 0; i < 100_000; i++) {
            enc.encodeLimit(i, i, i & 1, (i & 1) == 0 ? Side.BUY : Side.SELL,
                    1_000 + i % 100, 108_000 + i % 1000, 5, base + i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        long blackhole = 0;
        for (int i = 0; i < 500_000; i++) {
            blackhole += enc.encodeLimit(i, i, i & 1, (i & 1) == 0 ? Side.BUY : Side.SELL,
                    1_000 + i % 100, 108_000 + i % 1000, 5, base + i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "encode loop allocated " + allocated + " bytes (blackhole " + blackhole + ")");
    }
}

package com.quantfinlib.fix;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The garbage-free ExecutionReport view, proven against the String-based
 * codec: messages BUILT by the validated {@link FixMessage.Builder} must
 * read back field-identical through the view — the readable codec is the
 * executable specification again. Plus scaled-price extraction, non-exec
 * rejection, in-place symbol comparison, and the allocation proof.
 */
class FixExecReportViewTest {

    /** A venue fill built through the existing validated String codec. */
    private static byte[] fill(String clOrdId, String symbol, char side, long lastQty,
                               String lastPx, long cumQty, long leavesQty) {
        return FixMessage.builder("8")
                .field(FixMessage.ORDER_ID, "V-1")
                .field(FixMessage.EXEC_ID, "E-1")
                .field(FixMessage.EXEC_TYPE, "F")
                .field(FixMessage.ORD_STATUS, "2")
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, symbol)
                .field(FixMessage.SIDE, String.valueOf(side))
                .field(FixMessage.LAST_QTY, String.valueOf(lastQty))
                .field(FixMessage.LAST_PX, lastPx)
                .field(FixMessage.CUM_QTY, String.valueOf(cumQty))
                .field(FixMessage.LEAVES_QTY, String.valueOf(leavesQty))
                .encode("VENUE", "QFL", 7, "20260706-12:00:00.000");
    }

    @Test
    void counterpartyFormattedClOrdIdMapsToNotOursInsteadOfThrowing() {
        // ClOrdID is free-format FIX: unsolicited venue reports (expires,
        // restatements, drop-copy) carry THEIR formats. A foreign id must
        // never kill the message pump — it reads as the -1 "not ours"
        // sentinel and the rest of the report still parses.
        for (String foreign : new String[] {"ORD-2024-17", "abc", "NONE",
                "550e8400-e29b-41d4"}) {
            byte[] bytes = fill(foreign, "EURUSD", '1', 100, "1.08505", 100, 0);
            FixExecReportView view = new FixExecReportView();
            assertTrue(view.wrap(bytes, 0, bytes.length), foreign);
            assertEquals(-1, view.clOrdId(), foreign);
            assertEquals(100, view.lastQty(), foreign);
            assertEquals(108505, view.lastPxMantissa(), foreign);
        }
    }

    @Test
    void negativePricesRoundTripThroughTheView() {
        // Forward points / negative rates: '-' must parse, not garble.
        byte[] bytes = fill("42", "EURUSD", '1', 100, "-0.00035", 100, 0);
        FixExecReportView view = new FixExecReportView();
        assertTrue(view.wrap(bytes, 0, bytes.length));
        assertEquals(-35, view.lastPxMantissa());
        assertEquals(5, view.lastPxDecimals());
    }

    @Test
    void readsAFillFieldIdenticalToTheStringCodec() {
        byte[] bytes = fill("987654321", "EURUSD", '1', 250_000, "1.08505", 750_000, 250_000);
        FixExecReportView view = new FixExecReportView();
        assertTrue(view.wrap(bytes, 0, bytes.length));

        assertEquals(987654321L, view.clOrdId());
        assertEquals('F', view.execType());
        assertEquals('2', view.ordStatus());
        assertEquals('1', view.side());
        assertEquals(250_000, view.lastQty());
        assertEquals(750_000, view.cumQty());
        assertEquals(250_000, view.leavesQty());
        // Price as scaled long — the same representation FixOrderEncoder
        // takes in: the FIX round trip never touches a double.
        assertEquals(108505, view.lastPxMantissa());
        assertEquals(5, view.lastPxDecimals());
        // Symbol compared in place, never materialized.
        assertTrue(view.symbolEquals("EURUSD".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(view.symbolEquals("USDJPY".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void nonExecReportsAreRejectedEarly() {
        // A heartbeat (35=0) and an encoder-built NewOrderSingle (35=D):
        // wrap must return false for both and never mislead the fill handler.
        byte[] heartbeat = FixMessage.builder("0")
                .encode("VENUE", "QFL", 8, "20260706-12:00:00.000");
        FixExecReportView view = new FixExecReportView();
        assertFalse(view.wrap(heartbeat, 0, heartbeat.length));

        FixOrderEncoder enc = new FixOrderEncoder("QFL", "VENUE", 4, 512)
                .registerSymbol(0, "EURUSD");
        enc.encodeLimit(1, 1, 0, com.quantfinlib.orderbook.Side.BUY, 1, 108505, 5,
                1_800_000_000_000L);
        assertFalse(view.wrap(enc.buffer(), enc.offset(), enc.length()));
    }

    @Test
    void priceAndQuantityEdgeCases() {
        FixExecReportView view = new FixExecReportView();
        // Integer price, zero-fraction quantity ("100.0" style feeds).
        byte[] a = fill("1", "USDJPY", '2', 100, "155", 100, 0);
        assertTrue(view.wrap(a, 0, a.length));
        assertEquals(155, view.lastPxMantissa());
        assertEquals(0, view.lastPxDecimals());
        byte[] b = FixMessage.builder("8")
                .field(FixMessage.CL_ORD_ID, "2")
                .field(FixMessage.LAST_QTY, "100.00")
                .encode("VENUE", "QFL", 9, "20260706-12:00:00.000");
        assertTrue(view.wrap(b, 0, b.length));
        assertEquals(100, view.lastQty());
        // A REAL fractional quantity must fail loudly, never truncate.
        byte[] c = FixMessage.builder("8")
                .field(FixMessage.CL_ORD_ID, "3")
                .field(FixMessage.LAST_QTY, "100.5")
                .encode("VENUE", "QFL", 10, "20260706-12:00:00.000");
        assertThrows(IllegalArgumentException.class, () -> view.wrap(c, 0, c.length));
    }

    @Test
    void wrapLoopIsAllocationFree() {
        byte[] bytes = fill("42", "EURUSD", '1', 1_000, "1.08505", 1_000, 0);
        FixExecReportView view = new FixExecReportView();
        byte[] symbol = "EURUSD".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 100_000; i++) { // JIT warmup
            view.wrap(bytes, 0, bytes.length);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        long blackhole = 0;
        for (int i = 0; i < 500_000; i++) {
            view.wrap(bytes, 0, bytes.length);
            blackhole += view.clOrdId() + view.lastQty() + view.lastPxMantissa()
                    + (view.symbolEquals(symbol) ? 1 : 0);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "wrap loop allocated " + allocated + " bytes (blackhole " + blackhole + ")");
    }
}

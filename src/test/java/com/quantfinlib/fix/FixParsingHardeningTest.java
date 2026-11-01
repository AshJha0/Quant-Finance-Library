package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser, decoder, flyweight and encoder guards (H8-H12). */
class FixParsingHardeningTest {

    @Test
    void dataFieldWithEmbeddedSohIsReadByItsLength() {
        String raw = "a\u0001b=c";   // contains SOH and '=': would split without the length
        byte[] bytes = FixMessage.builder(FixMessage.NEW_ORDER_SINGLE)
                .dataField(FixMessage.RAW_DATA_LENGTH, FixMessage.RAW_DATA, raw)
                .field(FixMessage.CL_ORD_ID, "x")
                .encode("A", "B", 3, "20260710-12:00:00.000");
        FixMessage m = FixMessage.parse(bytes);
        assertEquals(raw, m.getString(FixMessage.RAW_DATA));
        assertEquals("x", m.getString(FixMessage.CL_ORD_ID));
    }

    @Test
    void dataFieldShorterThanItsLengthIsAFieldFault() {
        String body = "35=D\u000149=A\u000156=B\u000134=3\u000152=20260710-12:00:00.000\u0001"
                + "95=10\u000196=abc\u0001";
        FixParseException e = assertThrows(FixParseException.class, () -> FixMessage.parse(frame(body)));
        assertFalse(e.garbled());
        assertEquals(FixMessage.REJECT_INCORRECT_DATA_FORMAT, e.rejectReason());
        assertEquals(3, e.seqNum());
    }

    @Test
    void repeatedTagsAreAllKeptInWireOrder() {
        byte[] bytes = FixMessage.builder("W")
                .field(268, 2L)
                .field(269, '0').field(270, "1.0850")
                .field(269, '1').field(270, "1.0852")
                .encode("A", "B", 1, "20260710-12:00:00.000");
        FixMessage m = FixMessage.parse(bytes);
        assertEquals(List.of("1.0850", "1.0852"), m.getAll(270));
        assertEquals("1.0850", m.getString(270), "first occurrence by default");
    }

    @Test
    void nonNumericTagAndEmptyValueAreFieldFaultsWithTheSequenceNumber() {
        FixParseException badTag = assertThrows(FixParseException.class, () -> FixMessage.parse(frame(
                "35=D\u000149=A\u000156=B\u000134=9\u000152=20260710-12:00:00.000\u0001x1=5\u0001")));
        assertEquals(FixMessage.REJECT_INVALID_TAG_NUMBER, badTag.rejectReason());
        assertEquals(9, badTag.seqNum());
        FixParseException empty = assertThrows(FixParseException.class, () -> FixMessage.parse(frame(
                "35=D\u000149=A\u000156=B\u000134=9\u000152=20260710-12:00:00.000\u000111=\u0001")));
        assertEquals(FixMessage.REJECT_TAG_WITHOUT_VALUE, empty.rejectReason());
        assertEquals(11, empty.refTagId());
    }

    @Test
    void sohInsideAPlainFieldIsRefusedAtBuildTime() {
        assertThrows(IllegalArgumentException.class,
                () -> FixMessage.builder("D").field(FixMessage.TEXT, "a\u0001b"));
    }

    @Test
    void parseLongRefusesNonDigits() {
        byte[] b = "1e3".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> FixParse.parseLong(b, 0, b.length));
        byte[] ok = "100.00".getBytes(StandardCharsets.US_ASCII);
        assertEquals(100, FixParse.parseLong(ok, 0, ok.length));
        byte[] empty = new byte[0];
        assertThrows(IllegalArgumentException.class, () -> FixParse.parseLong(empty, 0, 0));
        byte[] px = "1.0x5".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> FixParse.priceMantissa(px, 0, px.length));
    }

    @Test
    void decoderNeverReadsStaleBytesPastTheFilledLength() {
        // 20 bytes whose first SOH is the last byte: "9=" has not arrived,
        // and the zeros after the filled length must not be read as data.
        byte[] head = new byte[20];
        Arrays.fill(head, (byte) 'X');
        head[19] = FixMessage.SOH;
        FixDecoder decoder = new FixDecoder();
        decoder.feed(head, 0, head.length);
        assertNull(decoder.poll(), "incomplete, not corrupt");
    }

    @Test
    void execReportViewCarriesRecoveryFieldsAndTheDeduplicatorDropsReplays() {
        FixExecReportView view = new FixExecReportView();
        FixExecReportView.ExecIdDeduplicator dedup = new FixExecReportView.ExecIdDeduplicator(4);
        byte[] first = report("E-100", false);
        byte[] replay = report("E-100", true);
        assertTrue(view.wrap(first, 0, first.length));
        assertEquals("E-100", view.execId());
        assertEquals(103, view.ordRejReason());
        assertEquals("late", view.text());
        assertEquals(108_505L, view.avgPxMantissa());
        assertEquals(5, view.avgPxDecimals());
        assertFalse(view.possDup());
        assertTrue(dedup.firstSeen(view));

        assertTrue(view.wrap(replay, 0, replay.length));
        assertTrue(view.possDup());
        assertFalse(dedup.firstSeen(view), "a PossDup replay of a seen fill is a duplicate");
    }

    @Test
    void deduplicatorForgetsOldestFirstAndKeepsProbeChainsIntact() {
        FixExecReportView.ExecIdDeduplicator dedup = new FixExecReportView.ExecIdDeduplicator(64);
        java.util.ArrayDeque<String> window = new java.util.ArrayDeque<>();
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(7);
        for (int i = 0; i < 20_000; i++) {
            String id = "E" + rnd.nextInt(300);
            byte[] b = id.getBytes(StandardCharsets.US_ASCII);
            boolean expectedNew = !window.contains(id);
            assertEquals(expectedNew, dedup.firstSeen(b, 0, b.length), "step " + i + " id " + id);
            if (expectedNew) {
                window.addLast(id);
                if (window.size() > 64) {
                    window.removeFirst();
                }
            }
        }
        assertEquals(64, dedup.size());
    }

    @Test
    void cancelAndReplaceEncodeGarbageFreeAndParseBack() {
        FixOrderEncoder enc = new FixOrderEncoder("QFL", "VENUE", 4, 512).registerSymbol(0, "EURUSD");
        long ts = 1_800_000_000_000L;
        enc.encodeCancel(43, 2002L, 1001L, 0, Side.SELL, 500, ts);
        FixMessage c = FixMessage.parse(enc.toByteArray());
        assertEquals("F", c.msgType());
        assertEquals("1001", c.getString(FixMessage.ORIG_CL_ORD_ID));
        assertEquals("2002", c.getString(FixMessage.CL_ORD_ID));
        assertEquals("2", c.getString(FixMessage.SIDE));
        assertEquals(500, c.getLong(FixMessage.ORDER_QTY));
        assertEquals(43, c.getLong(FixMessage.MSG_SEQ_NUM));

        enc.encodeReplace(44, 2003L, 2002L, 0, Side.SELL, 400, 108_510L, 5, ts);
        FixMessage r = FixMessage.parse(enc.toByteArray());
        assertEquals("G", r.msgType());
        assertEquals("1.08510", r.getString(FixMessage.PRICE));
        assertEquals(400, r.getLong(FixMessage.ORDER_QTY));
        assertEquals("2", r.getString(FixMessage.ORD_TYPE));

        var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(mx.isThreadAllocatedMemorySupported());
        long tid = Thread.currentThread().threadId();
        long min = Long.MAX_VALUE;
        for (int run = 0; run < 6; run++) {
            long before = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 100_000; i++) {
                enc.encodeCancel(i, i + 1, i, 0, Side.BUY, 100, ts);
                enc.encodeReplace(i, i + 2, i + 1, 0, Side.BUY, 100, 108_510L, 5, ts);
            }
            min = Math.min(min, mx.getThreadAllocatedBytes(tid) - before);
        }
        assertEquals(0, min, "cancel/replace encoding allocated in steady state");
    }

    // ------------------------------------------------------------------

    private static byte[] report(String execId, boolean possDup) {
        FixMessage.Builder b = FixMessage.builder("8")
                .field(FixMessage.ORDER_ID, "V-1")
                .field(FixMessage.EXEC_ID, execId)
                .field(FixMessage.EXEC_TYPE, "F")
                .field(FixMessage.ORD_STATUS, "2")
                .field(FixMessage.CL_ORD_ID, "77")
                .field(FixMessage.SYMBOL, "EURUSD")
                .field(FixMessage.SIDE, "1")
                .field(FixMessage.LAST_QTY, "100")
                .field(FixMessage.LAST_PX, "1.08505")
                .field(FixMessage.AVG_PX, "1.08505")
                .field(FixMessage.ORD_REJ_REASON, "103")
                .field(FixMessage.TEXT, "late");
        return b.encode("VENUE", "QFL", 7, "20260710-12:00:01.000", possDup,
                possDup ? "20260710-12:00:00.000" : null);
    }

    static byte[] frame(String afterLength) {
        String head = "8=FIX.4.4\u00019=" + afterLength.length() + "\u0001";
        byte[] payload = (head + afterLength).getBytes(StandardCharsets.ISO_8859_1);
        int sum = 0;
        for (byte x : payload) {
            sum += x & 0xFF;
        }
        return (head + afterLength + String.format("10=%03d\u0001", sum % 256))
                .getBytes(StandardCharsets.ISO_8859_1);
    }
}

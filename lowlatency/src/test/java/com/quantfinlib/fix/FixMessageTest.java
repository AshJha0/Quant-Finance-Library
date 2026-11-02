package com.quantfinlib.fix;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixMessageTest {

    @Test
    void corruptBodyLengthFailsLoudlyInsteadOfZombifying() {
        // One flipped byte in the BodyLength digits used to inflate the
        // expected frame size, and the framer would then wait FOREVER —
        // a zombie session silently swallowing all later traffic.
        byte[] bytes = sampleOrder();
        int soh = 0;
        while (bytes[soh] != FixMessage.SOH) {
            soh++;
        }
        bytes[soh + 3] = ':';                  // '9' + 1: the classic bit flip
        FixDecoder decoder = new FixDecoder();
        decoder.feed(bytes, 0, bytes.length);
        assertThrows(IllegalStateException.class, decoder::poll,
                "corruption must disconnect, never stall");
    }

    private static byte[] sampleOrder() {
        return FixMessage.builder(FixMessage.NEW_ORDER_SINGLE)
                .field(FixMessage.CL_ORD_ID, "ord-1")
                .field(FixMessage.SYMBOL, "EURUSD")
                .field(FixMessage.SIDE, '1')
                .field(FixMessage.ORDER_QTY, 1_000_000L)
                .field(FixMessage.ORD_TYPE, '2')
                .field(FixMessage.PRICE, 1.0851)
                .encode("CLIENT", "VENUE", 7, "20260704-12:00:00.000");
    }

    @Test
    void encodeParseRoundTrips() {
        FixMessage m = FixMessage.parse(sampleOrder());
        assertEquals("D", m.msgType());
        assertEquals("ord-1", m.getString(FixMessage.CL_ORD_ID));
        assertEquals("EURUSD", m.getString(FixMessage.SYMBOL));
        assertEquals('1', m.getChar(FixMessage.SIDE));
        assertEquals(1_000_000L, m.getLong(FixMessage.ORDER_QTY));
        assertEquals(1.0851, m.getDouble(FixMessage.PRICE), 0.0);
        assertEquals("CLIENT", m.getString(FixMessage.SENDER_COMP_ID));
        assertEquals("VENUE", m.getString(FixMessage.TARGET_COMP_ID));
        assertEquals(7, m.getLong(FixMessage.MSG_SEQ_NUM));
    }

    @Test
    void checksumIsIndependentlyVerifiable() {
        byte[] bytes = sampleOrder();
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        int checksumField = raw.lastIndexOf(FixMessage.SOH + "10=");
        int sum = 0;
        for (int i = 0; i <= checksumField; i++) {
            sum += bytes[i] & 0xFF;
        }
        assertEquals(String.format("%03d", sum % 256),
                raw.substring(checksumField + 4, raw.length() - 1));
    }

    @Test
    void tamperedMessagesAreRejected() {
        byte[] bytes = sampleOrder();
        bytes[30] ^= 0x01;   // flip one payload bit
        assertThrows(IllegalArgumentException.class, () -> FixMessage.parse(bytes));

        // Wrong protocol prefix.
        assertThrows(IllegalArgumentException.class,
                () -> FixMessage.parse(("8=FIX.4.2" + FixMessage.SOH + "9=5" + FixMessage.SOH + "35=0" + FixMessage.SOH + "10=000" + FixMessage.SOH)
                        .getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void doublesRenderAsPlainDecimals() {
        byte[] bytes = FixMessage.builder("0")
                .field(FixMessage.PRICE, 0.0001)
                .encode("A", "B", 1, "20260704-12:00:00.000");
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("44=0.0001" + FixMessage.SOH), raw.replace(FixMessage.SOH, '|'));
    }

    @Test
    void decoderReassemblesFragmentsAndSplitsCoalescedMessages() {
        byte[] first = sampleOrder();
        byte[] second = FixMessage.builder(FixMessage.HEARTBEAT)
                .encode("CLIENT", "VENUE", 8, "20260704-12:00:01.000");
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);

        FixDecoder decoder = new FixDecoder();
        // Worst case: one byte at a time.
        int produced = 0;
        for (byte b : stream) {
            decoder.feed(new byte[]{b}, 0, 1);
            FixMessage m;
            while ((m = decoder.poll()) != null) {
                produced++;
                if (produced == 1) {
                    assertEquals("D", m.msgType());
                } else {
                    assertEquals("0", m.msgType());
                }
            }
        }
        assertEquals(2, produced);
        assertNull(decoder.poll());
    }
}

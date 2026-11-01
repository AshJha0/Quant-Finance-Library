package com.quantfinlib.feed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoldUdp64ReceiverTest {

    private final List<Long> delivered = new ArrayList<>();
    private final List<long[]> requests = new ArrayList<>();
    private final List<Long> snapshots = new ArrayList<>();

    private MoldUdp64Receiver receiver() {
        return new MoldUdp64Receiver(
                (buf, off, len, seq) -> {
                    assertEquals(1, len);
                    assertEquals((byte) seq, buf[off], "payload matches its sequence number");
                    delivered.add(seq);
                },
                new MoldUdp64Receiver.Recovery() {
                    @Override
                    public void requestRetransmit(String session, long from, int count) {
                        assertEquals("SESSION1", session);
                        requests.add(new long[]{from, count});
                    }

                    @Override
                    public void rebuildFromSnapshot(String session, long missingFrom) {
                        snapshots.add(missingFrom);
                    }
                }, 100, 1_000_000L, 2);
    }

    /** Packet carrying messages first..first+count-1, each one byte equal to its sequence. */
    private static byte[] packet(long first, int count) {
        byte[] buf = new byte[20 + count * 3];
        MoldUdp64Receiver.encodeHeader(buf, 0, "SESSION1", first, count);
        int p = 20;
        for (int i = 0; i < count; i++) {
            p += MoldUdp64Receiver.encodeBlock(buf, p, new byte[]{(byte) (first + i)}, 0, 1);
        }
        return buf;
    }

    private static void feed(MoldUdp64Receiver r, byte[] packet, long now) {
        r.onPacket(packet, 0, packet.length, now);
    }

    @Test
    void inSequencePacketsAreDeliveredInOrder() {
        MoldUdp64Receiver r = receiver();
        feed(r, packet(1, 3), 0);
        feed(r, packet(4, 2), 0);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), delivered);
        assertEquals(6, r.expectedSequence());
    }

    @Test
    void gapIsDetectedRetransmittedAndClosed() {
        MoldUdp64Receiver r = receiver();
        feed(r, packet(1, 2), 0);
        feed(r, packet(6, 2), 0);              // 3..5 lost
        assertTrue(r.isRecovering());
        assertEquals(1, r.gapsDetected());
        assertEquals(1, requests.size());
        assertEquals(3, requests.getFirst()[0]);
        assertEquals(5, requests.getFirst()[1]);   // 3..7: the gap and the dropped packet
        assertEquals(List.of(1L, 2L), delivered, "nothing out of order is delivered");

        feed(r, packet(3, 5), 10);             // the retransmission
        assertFalse(r.isRecovering());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), delivered);
    }

    @Test
    void duplicatesAndOverlapsDeliverOnlyNewMessages() {
        MoldUdp64Receiver r = receiver();
        feed(r, packet(1, 3), 0);
        feed(r, packet(1, 3), 0);              // A/B line duplicate
        feed(r, packet(2, 4), 0);              // straddles: 4 and 5 are new
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), delivered);
        assertEquals(1, r.duplicatePackets());
    }

    @Test
    void heartbeatAheadOfExpectedRevealsAGap() {
        MoldUdp64Receiver r = receiver();
        feed(r, packet(1, 1), 0);
        feed(r, packet(5, 0), 0);              // heartbeat: next sequence is 5
        assertTrue(r.isRecovering());
        assertEquals(2, requests.getFirst()[0]);
        assertEquals(3, requests.getFirst()[1]);
    }

    @Test
    void unansweredRetransmitsFallBackToASnapshot() {
        MoldUdp64Receiver r = receiver();
        feed(r, packet(1, 1), 0);
        feed(r, packet(4, 1), 0);
        r.onTimer(999_999);
        assertEquals(1, requests.size(), "no re-request before the timeout");
        r.onTimer(1_000_001);
        assertEquals(2, requests.size());
        r.onTimer(2_000_002);
        assertEquals(List.of(2L), snapshots);
        assertTrue(r.isAwaitingSnapshot());
        feed(r, packet(5, 1), 2_000_003);
        assertEquals(List.of(1L), delivered, "nothing applies until the snapshot is in");

        r.resumeAfterSnapshot(6);
        feed(r, packet(6, 1), 3_000_000);
        assertEquals(List.of(1L, 6L), delivered);
    }

    @Test
    void malformedAndForeignPacketsAreCounted() {
        MoldUdp64Receiver r = receiver();
        feed(r, packet(1, 1), 0);
        byte[] cut = java.util.Arrays.copyOf(packet(2, 2), 24);   // block runs past the end
        feed(r, cut, 0);
        assertEquals(1, r.malformedPackets());
        byte[] other = new byte[23];
        MoldUdp64Receiver.encodeHeader(other, 0, "OTHER", 2, 1);
        feed(r, other, 0);
        assertEquals(1, r.foreignSessionPackets());
        feed(r, new byte[10], 0);
        assertEquals(2, r.malformedPackets());
    }
}

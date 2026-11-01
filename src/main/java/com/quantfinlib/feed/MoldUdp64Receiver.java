package com.quantfinlib.feed;

import java.nio.charset.StandardCharsets;

/**
 * MoldUDP64 receiver: unpacks downstream packets into sequenced messages,
 * detects gaps, and drives recovery (Nasdaq "MoldUDP64 Protocol
 * Specification" v1.00, 2009).
 *
 * <h2>Wire format</h2>
 * <pre>
 * packet  = session (10 bytes ASCII) | sequence (uint64 BE, of the first message)
 *           | count (uint16 BE) | count × message block
 * message = length (uint16 BE) | payload (e.g. one ITCH message)
 * count 0 = heartbeat; count 0xFFFF = end of session
 * </pre>
 *
 * <h2>Sequencing</h2>
 * Messages are delivered to the {@link MessageHandler} strictly in sequence.
 * A packet whose first sequence number is above the next expected one opens
 * a gap: its messages are dropped, {@link Recovery#requestRetransmit} is
 * called for the missing range (at most {@code maxRequestCount} messages,
 * the size limit of a MoldUDP64 request packet), and in-sequence delivery
 * resumes when the retransmitted packets arrive. Packets wholly below the
 * expected number are duplicates (the A/B arbitration case) and are
 * ignored; a packet straddling it delivers only its new messages.
 *
 * <p>If the gap is not closed within {@code retransmitTimeoutNanos},
 * {@link #onTimer} re-requests, and after {@code maxRetransmitAttempts}
 * requests calls {@link Recovery#rebuildFromSnapshot}: the owner clears its
 * book ({@code L3BookBuilder.clear()}), replays a snapshot (Glimpse or
 * equivalent), and calls {@link #resumeAfterSnapshot} with the sequence
 * number the snapshot is current to.</p>
 *
 * <p>Zero allocation per packet; single thread (the feed handler).</p>
 */
public final class MoldUdp64Receiver {

    /** Downstream header: session, sequence, count. */
    public static final int HEADER_LENGTH = 20;
    /** Message count marking the end of the session. */
    public static final int END_OF_SESSION = 0xFFFF;

    /** Receives each in-sequence message payload. */
    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(byte[] buf, int offset, int length, long sequence);
    }

    /** Recovery actions the owner implements. */
    public interface Recovery {

        /** Ask the re-request server for {@code count} messages from {@code fromSequence}. */
        void requestRetransmit(String session, long fromSequence, int count);

        /** Retransmission failed: rebuild from a snapshot, then call {@link #resumeAfterSnapshot}. */
        void rebuildFromSnapshot(String session, long missingFromSequence);
    }

    private final MessageHandler handler;
    private final Recovery recovery;
    private final int maxRequestCount;
    private final long retransmitTimeoutNanos;
    private final int maxRetransmitAttempts;
    private final byte[] session = new byte[10];
    private boolean sessionKnown;
    private String sessionName = "";
    private long expected = 1;
    private boolean recovering;
    private boolean awaitingSnapshot;
    private long gapEnd;              // exclusive end of the missing range
    private long requestedAtNanos;
    private int attempts;
    private boolean endOfSession;
    private long gapsDetected;
    private long duplicatePackets;
    private long malformedPackets;
    private long foreignSessionPackets;
    private long messagesDelivered;

    /**
     * @param maxRequestCount        largest count in one retransmit request
     * @param retransmitTimeoutNanos re-request after this long without the gap closing
     * @param maxRetransmitAttempts  requests before falling back to a snapshot
     */
    public MoldUdp64Receiver(MessageHandler handler, Recovery recovery, int maxRequestCount,
                             long retransmitTimeoutNanos, int maxRetransmitAttempts) {
        if (maxRequestCount < 1 || maxRequestCount > 0xFFFE || maxRetransmitAttempts < 1) {
            throw new IllegalArgumentException("need 1 <= maxRequestCount <= 65534, attempts >= 1");
        }
        this.handler = handler;
        this.recovery = recovery;
        this.maxRequestCount = maxRequestCount;
        this.retransmitTimeoutNanos = retransmitTimeoutNanos;
        this.maxRetransmitAttempts = maxRetransmitAttempts;
    }

    /**
     * Processes one datagram. {@code nowNanos} is a monotonic time used for
     * the retransmit timeout.
     */
    public void onPacket(byte[] buf, int offset, int length, long nowNanos) {
        if (length < HEADER_LENGTH || offset < 0 || offset + length > buf.length) {
            malformedPackets++;
            return;
        }
        if (!sessionKnown) {
            System.arraycopy(buf, offset, session, 0, 10);
            sessionName = new String(session, StandardCharsets.US_ASCII).trim();
            sessionKnown = true;
        } else if (!java.util.Arrays.equals(buf, offset, offset + 10, session, 0, 10)) {
            foreignSessionPackets++;
            return;
        }
        long first = u64(buf, offset + 10);
        int count = u16(buf, offset + 18);
        if (count == END_OF_SESSION) {
            endOfSession = true;
            count = 0;
        }
        if (first < 1) {
            malformedPackets++;
            return;
        }
        if (awaitingSnapshot) {
            return;   // nothing applies until the snapshot is in
        }
        long next = first + count;   // first sequence after this packet
        if (first > expected) {
            gapsDetected += recovering ? 0 : 1;
            gapEnd = Math.max(gapEnd, recovering ? next : Math.max(first, next));
            if (!recovering) {
                recovering = true;
                attempts = 0;
                request(nowNanos);
            }
            return;
        }
        if (count == 0) {
            return;   // heartbeat or end of session at or below expected: nothing missed
        }
        if (next <= expected) {
            duplicatePackets++;
            return;
        }
        // Walk the blocks; deliver those at or above expected.
        int p = offset + HEADER_LENGTH;
        int end = offset + length;
        long seq = first;
        for (int i = 0; i < count; i++) {
            if (p + 2 > end) {
                malformedPackets++;
                return;
            }
            int len = u16(buf, p);
            p += 2;
            if (p + len > end) {
                malformedPackets++;
                return;
            }
            if (seq == expected) {
                handler.onMessage(buf, p, len, seq);
                messagesDelivered++;
                expected = seq + 1;
            }
            p += len;
            seq++;
        }
        if (recovering && expected >= gapEnd) {
            recovering = false;   // gap closed
        } else if (recovering) {
            // Partially closed: the next request starts from the new expected.
            attempts = 0;
            requestedAtNanos = nowNanos;
        }
    }

    /** Re-requests or falls back to a snapshot when recovery has timed out. */
    public void onTimer(long nowNanos) {
        if (!recovering || awaitingSnapshot) {
            return;
        }
        if (nowNanos - requestedAtNanos < retransmitTimeoutNanos) {
            return;
        }
        if (attempts >= maxRetransmitAttempts) {
            awaitingSnapshot = true;
            recovery.rebuildFromSnapshot(sessionName, expected);
            return;
        }
        request(nowNanos);
    }

    /**
     * Resumes in-sequence delivery after a snapshot that is current up to
     * (excluding) {@code nextSequence}.
     */
    public void resumeAfterSnapshot(long nextSequence) {
        expected = nextSequence;
        recovering = false;
        awaitingSnapshot = false;
        gapEnd = 0;
        attempts = 0;
    }

    private void request(long nowNanos) {
        attempts++;
        requestedAtNanos = nowNanos;
        long missing = Math.max(1, gapEnd - expected);
        recovery.requestRetransmit(sessionName, expected, (int) Math.min(missing, maxRequestCount));
    }

    /** Encodes a MoldUDP64 request packet (session, sequence, count); returns 20. */
    public static int encodeRequest(byte[] buf, int offset, String session, long fromSequence,
                                    int count) {
        byte[] s = String.format("%-10s", session).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, buf, offset, 10);
        p64(buf, offset + 10, fromSequence);
        buf[offset + 18] = (byte) (count >>> 8);
        buf[offset + 19] = (byte) count;
        return HEADER_LENGTH;
    }

    /** Encodes a downstream packet header; message blocks follow at offset + 20. */
    public static int encodeHeader(byte[] buf, int offset, String session, long firstSequence,
                                   int count) {
        return encodeRequest(buf, offset, session, firstSequence, count);
    }

    /** Appends one message block (length + payload) at {@code offset}; returns bytes written. */
    public static int encodeBlock(byte[] buf, int offset, byte[] payload, int payloadOffset,
                                  int payloadLength) {
        buf[offset] = (byte) (payloadLength >>> 8);
        buf[offset + 1] = (byte) payloadLength;
        System.arraycopy(payload, payloadOffset, buf, offset + 2, payloadLength);
        return payloadLength + 2;
    }

    /** Next sequence number expected. */
    public long expectedSequence() {
        return expected;
    }

    public boolean isRecovering() {
        return recovering;
    }

    public boolean isAwaitingSnapshot() {
        return awaitingSnapshot;
    }

    public boolean isEndOfSession() {
        return endOfSession;
    }

    public long gapsDetected() {
        return gapsDetected;
    }

    public long duplicatePackets() {
        return duplicatePackets;
    }

    public long malformedPackets() {
        return malformedPackets;
    }

    public long foreignSessionPackets() {
        return foreignSessionPackets;
    }

    public long messagesDelivered() {
        return messagesDelivered;
    }

    public String session() {
        return sessionName;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static long u64(byte[] b, int i) {
        long v = 0;
        for (int k = 0; k < 8; k++) {
            v = (v << 8) | (b[i + k] & 0xFF);
        }
        return v;
    }

    private static void p64(byte[] b, int i, long v) {
        for (int k = 7; k >= 0; k--) {
            b[i + k] = (byte) v;
            v >>>= 8;
        }
    }
}

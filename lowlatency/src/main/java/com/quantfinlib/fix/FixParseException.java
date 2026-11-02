package com.quantfinlib.fix;

/**
 * A FIX message that could not be parsed. Two kinds, handled differently by
 * the session (FIX 4.4 Vol 2):
 * <ul>
 *   <li>{@link #garbled()}: the frame itself is bad (BeginString, BodyLength,
 *       CheckSum). The message is discarded and its sequence number is not
 *       consumed; the gap, if real, is recovered by ResendRequest.</li>
 *   <li>field fault: the frame is good but a field is not (non-numeric tag,
 *       empty value, data field shorter than its length). The session answers
 *       Reject(35=3) with {@link #rejectReason()} as SessionRejectReason(373)
 *       and consumes the sequence number {@link #seqNum()}.</li>
 * </ul>
 */
public final class FixParseException extends IllegalArgumentException
        implements com.quantfinlib.core.QuantFinException {

    private static final long serialVersionUID = 1L;

    private final boolean garbled;
    private final int rejectReason;
    private final int refTagId;
    private final long seqNum;
    private final String msgType;

    private FixParseException(String message, boolean garbled, int rejectReason, int refTagId,
                              long seqNum, String msgType) {
        super(message);
        this.garbled = garbled;
        this.rejectReason = rejectReason;
        this.refTagId = refTagId;
        this.seqNum = seqNum;
        this.msgType = msgType;
    }

    static FixParseException garbled(String message) {
        return new FixParseException(message, true, -1, 0, -1, null);
    }

    static FixParseException field(int reason, int refTagId, String message, long seqNum,
                                   String msgType) {
        return new FixParseException(message, false, reason, refTagId, seqNum, msgType);
    }

    /** True for a bad frame (ignore the message), false for a field fault (reject it). */
    public boolean garbled() {
        return garbled;
    }

    /** SessionRejectReason(373) for a field fault; -1 when garbled. */
    public int rejectReason() {
        return rejectReason;
    }

    /** RefTagID(371) of the offending field, or 0 when unknown. */
    public int refTagId() {
        return refTagId;
    }

    /** MsgSeqNum(34) of the faulty message, or -1 when it could not be read. */
    public long seqNum() {
        return seqNum;
    }

    /** MsgType(35) of the faulty message, or null. */
    public String msgType() {
        return msgType;
    }
}

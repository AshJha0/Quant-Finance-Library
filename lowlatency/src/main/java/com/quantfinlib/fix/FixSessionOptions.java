package com.quantfinlib.fix;

/**
 * Optional session-layer behaviour for a {@link FixSession}, kept apart from
 * {@link FixSession.Config} so that the config record stays unchanged.
 *
 * @param schedule              session window and sequence-number roll, or null
 *                              for a session that is always open and never rolls
 * @param nextExpectedMsgSeqNum send NextExpectedMsgSeqNum(789) on Logon and
 *                              recover a gap from the peer's 789 instead of a
 *                              ResendRequest exchange (Logon semantics of the
 *                              FIX 4.4 errata and FIXT 1.1)
 * @param version               protocol version the session speaks; null means
 *                              FIX 4.4, the default
 */
public record FixSessionOptions(FixSessionSchedule schedule, boolean nextExpectedMsgSeqNum,
                                FixVersion version) {

    private static final FixSessionOptions NONE = new FixSessionOptions(null, false);

    public FixSessionOptions {
        if (version == null) {
            version = FixVersion.FIX_4_4;
        }
    }

    /** FIX 4.4 options (the 2.1 constructor). */
    public FixSessionOptions(FixSessionSchedule schedule, boolean nextExpectedMsgSeqNum) {
        this(schedule, nextExpectedMsgSeqNum, FixVersion.FIX_4_4);
    }

    /** No schedule, no 789: the behaviour of a session created without options. */
    public static FixSessionOptions none() {
        return NONE;
    }

    /** These options with {@code s} as the session schedule. */
    public FixSessionOptions withSchedule(FixSessionSchedule s) {
        return new FixSessionOptions(s, nextExpectedMsgSeqNum, version);
    }

    /** These options with NextExpectedMsgSeqNum(789) on Logon. */
    public FixSessionOptions withNextExpectedMsgSeqNum() {
        return new FixSessionOptions(schedule, true, version);
    }

    /** These options with {@code v} as the protocol version (FIX 4.2, 4.4 or 5.0 SP2/FIXT.1.1). */
    public FixSessionOptions withVersion(FixVersion v) {
        return new FixSessionOptions(schedule, nextExpectedMsgSeqNum, v);
    }
}

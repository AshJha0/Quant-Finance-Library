package com.quantfinlib.fix;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistence seam for a {@link FixSession}: sequence numbers and the
 * outbound message store that services the peer's ResendRequests.
 *
 * <p>Contract used by the session, in this order, for every outbound message:
 * {@link #recordSent} (assigns the record to its sequence number), then
 * {@link #sync()} (makes it durable), then the bytes go to the wire. A store
 * that honours this contract can never report a sequence number as sent while
 * the message behind it is missing, which is what lets a resend after a crash
 * replay the real orders instead of covering them with a GapFill.</p>
 *
 * <p>Stores are bounded: {@link #firstRetainedSeq()} is the lowest sequence
 * number still retrievable. The session reports resend requests that reach
 * below it through {@link FixSession.Listener#onResendUnavailable}.</p>
 *
 * <p>Methods are invoked from the session's writer path (outgoing) and reader
 * thread (incoming); implementations must be thread-safe.</p>
 */
public interface FixSessionStore {

    /** One stored outbound application message, replayable on ResendRequest. */
    record StoredMessage(String msgType, String body, String sendingTime) {
    }

    /** The next outgoing MsgSeqNum to use (1 for a fresh session). */
    long nextOutgoingSeq();

    /** The next incoming MsgSeqNum expected (1 for a fresh session). */
    long expectedIncomingSeq();

    void saveOutgoingSeq(long nextSeq);

    void saveIncomingSeq(long expectedSeq);

    /** Stores an application message under {@code seq}; durable on return for file stores. */
    void storeMessage(long seq, StoredMessage message);

    /** The stored message at {@code seq}, or null (admin, never sent, or pruned). */
    StoredMessage retrieve(long seq);

    /**
     * Records an outbound message about to be sent as {@code seq}; afterwards
     * {@link #nextOutgoingSeq()} is {@code seq + 1}. Administrative messages
     * are recorded too (without being replayable) so that the outgoing
     * counter is recovered from the same record stream as the messages.
     * Durability is only guaranteed after {@link #sync()}.
     */
    default void recordSent(long seq, StoredMessage message, boolean application) {
        if (application) {
            storeMessage(seq, message);
        }
        saveOutgoingSeq(seq + 1);
    }

    /** Forces every {@link #recordSent} so far to stable storage. */
    default void sync() {
    }

    /** Lowest sequence number whose message is still retained (1 when nothing was pruned). */
    default long firstRetainedSeq() {
        return 1;
    }

    /**
     * ResetSeqNumFlag(141)=Y: discards the stored history and restarts both
     * counters at 1. File stores roll their journal.
     */
    default void reset() {
        saveOutgoingSeq(1);
        saveIncomingSeq(1);
    }

    /** Fresh in-memory store keeping the last 100 000 application messages. */
    static FixSessionStore inMemory() {
        return new InMemory(100_000);
    }

    /** Fresh in-memory store keeping the last {@code maxRetained} application messages. */
    static FixSessionStore inMemory(int maxRetained) {
        return new InMemory(maxRetained);
    }

    /** Bounded in-memory store: per-connection sequence numbers, oldest messages evicted. */
    final class InMemory implements FixSessionStore {

        private final int maxRetained;
        private final Map<Long, StoredMessage> messages = new HashMap<>();
        private final java.util.ArrayDeque<Long> order = new java.util.ArrayDeque<>();
        private long nextOutgoing = 1;
        private long expectedIncoming = 1;
        private long firstRetained = 1;

        public InMemory(int maxRetained) {
            if (maxRetained < 1) {
                throw new IllegalArgumentException("maxRetained must be >= 1");
            }
            this.maxRetained = maxRetained;
        }

        @Override
        public synchronized long nextOutgoingSeq() {
            return nextOutgoing;
        }

        @Override
        public synchronized long expectedIncomingSeq() {
            return expectedIncoming;
        }

        @Override
        public synchronized void saveOutgoingSeq(long nextSeq) {
            this.nextOutgoing = nextSeq;
        }

        @Override
        public synchronized void saveIncomingSeq(long expectedSeq) {
            this.expectedIncoming = expectedSeq;
        }

        @Override
        public synchronized void storeMessage(long seq, StoredMessage message) {
            if (messages.put(seq, message) == null) {
                order.addLast(seq);
            }
            while (order.size() > maxRetained) {
                long evicted = order.removeFirst();
                messages.remove(evicted);
                firstRetained = Math.max(firstRetained, evicted + 1);
            }
        }

        @Override
        public synchronized StoredMessage retrieve(long seq) {
            return messages.get(seq);
        }

        @Override
        public synchronized long firstRetainedSeq() {
            return firstRetained;
        }

        @Override
        public synchronized void reset() {
            messages.clear();
            order.clear();
            firstRetained = 1;
            nextOutgoing = 1;
            expectedIncoming = 1;
        }
    }
}

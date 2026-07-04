package com.quantfinlib.fix;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistence seam for a {@link FixSession}: sequence numbers and the
 * outbound application-message store. With a durable implementation
 * ({@link FileSessionStore}) a session reconnects with <b>sequence-number
 * continuation</b> — the peer's ResendRequest is then serviced from messages
 * sent before the restart, which is what production counterparties expect.
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

    void storeMessage(long seq, StoredMessage message);

    /** The stored message at {@code seq}, or null (admin / never sent). */
    StoredMessage retrieve(long seq);

    /** Fresh in-memory store: per-connection sequence numbers (the default). */
    static FixSessionStore inMemory() {
        return new InMemory();
    }

    final class InMemory implements FixSessionStore {

        private final Map<Long, StoredMessage> messages = new HashMap<>();
        private long nextOutgoing = 1;
        private long expectedIncoming = 1;

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
            messages.put(seq, message);
        }

        @Override
        public synchronized StoredMessage retrieve(long seq) {
            return messages.get(seq);
        }
    }
}

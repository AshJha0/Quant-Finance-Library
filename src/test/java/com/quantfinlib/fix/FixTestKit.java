package com.quantfinlib.fix;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic FIX session harness: a manual clock, a session over an
 * in-memory stream, a scripted peer, and a two-session link pumped by hand.
 * No threads, no sleeps.
 */
final class FixTestKit {

    static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    private FixTestKit() {
    }

    /** Manual clock starting at 2026-07-10 12:00:00 UTC. */
    static final class ManualClock implements FixClock {
        long nanos = 1_000_000_000L;
        long millis = Instant.parse("2026-07-10T12:00:00Z").toEpochMilli();

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        void advanceMillis(long ms) {
            nanos += ms * 1_000_000L;
            millis += ms;
        }

        String utc() {
            return UTC.format(Instant.ofEpochMilli(millis));
        }
    }

    /** Captures everything a session writes. */
    static final class Sink extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int consumed;

        @Override
        public synchronized void write(int b) {
            bytes.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
        }

        /** Bytes written since the last call. */
        synchronized byte[] drain() {
            byte[] all = bytes.toByteArray();
            byte[] fresh = java.util.Arrays.copyOfRange(all, consumed, all.length);
            consumed = all.length;
            return fresh;
        }
    }

    /** Records listener callbacks. */
    static class Events implements FixSession.Listener {
        final List<NewOrderSingle> orders = new ArrayList<>();
        final List<ExecutionReport> reports = new ArrayList<>();
        final List<String> rejects = new ArrayList<>();
        final List<String> garbled = new ArrayList<>();
        final List<long[]> gaps = new ArrayList<>();
        final List<long[]> unavailable = new ArrayList<>();
        int logons;
        int logouts;
        String disconnect;
        FixSession.LogonDecision decision = FixSession.LogonDecision.accept();

        @Override
        public FixSession.LogonDecision onLogonRequest(FixSession s, FixMessage logon) {
            return decision;
        }

        @Override
        public void onLogon(FixSession s) {
            logons++;
        }

        @Override
        public void onLogout(FixSession s) {
            logouts++;
        }

        @Override
        public void onNewOrderSingle(FixSession s, NewOrderSingle order) {
            orders.add(order);
        }

        @Override
        public void onExecutionReport(FixSession s, ExecutionReport report) {
            reports.add(report);
        }

        @Override
        public void onReject(FixSession s, long refSeqNum, String text) {
            rejects.add(refSeqNum + ":" + text);
        }

        @Override
        public void onGarbledMessage(String reason) {
            garbled.add(reason);
        }

        @Override
        public void onSequenceGap(long expected, long received) {
            gaps.add(new long[]{expected, received});
        }

        @Override
        public void onResendUnavailable(long begin, long end) {
            unavailable.add(new long[]{begin, end});
        }

        @Override
        public void onDisconnect(String reason) {
            disconnect = reason;
        }
    }

    /** One session driven by a scripted peer. */
    static final class Scripted {
        final ManualClock clock = new ManualClock();
        final Sink sink = new Sink();
        final Events events;
        final FixSession session;
        final String peerSender;
        final String peerTarget;

        Scripted(FixSession.Config config, boolean acceptor, FixSessionStore store, Events events) {
            this.events = events;
            this.session = FixSession.overStream(config, events, acceptor, store, sink, clock);
            this.peerSender = config.targetCompId();
            this.peerTarget = config.senderCompId();
        }

        /** Delivers a message from the peer with the given sequence number, sent now. */
        void deliver(FixMessage.Builder b, long seq) {
            feed(b.encode(peerSender, peerTarget, seq, clock.utc()));
        }

        void deliverPossDup(FixMessage.Builder b, long seq, String origSendingTime) {
            feed(b.encode(peerSender, peerTarget, seq, clock.utc(), true, origSendingTime));
        }

        void feed(byte[] bytes) {
            session.onBytes(bytes, 0, bytes.length);
        }

        /** Messages the session wrote since the last call. */
        List<FixMessage> sent() {
            return parseAll(sink.drain());
        }
    }

    /** Two sessions joined back to back; {@link #pump()} moves bytes until both are quiet. */
    static final class Link {
        final ManualClock clock = new ManualClock();
        final Sink aOut = new Sink();
        final Sink bOut = new Sink();
        FixSession a;
        FixSession b;
        final List<FixMessage> aToB = new ArrayList<>();
        final List<FixMessage> bToA = new ArrayList<>();

        void pump() {
            for (int round = 0; round < 1000; round++) {
                byte[] fromA = aOut.drain();
                byte[] fromB = bOut.drain();
                if (fromA.length == 0 && fromB.length == 0) {
                    return;
                }
                if (fromA.length > 0) {
                    aToB.addAll(parseAll(fromA));
                    b.onBytes(fromA, 0, fromA.length);
                }
                if (fromB.length > 0) {
                    bToA.addAll(parseAll(fromB));
                    a.onBytes(fromB, 0, fromB.length);
                }
            }
            throw new AssertionError("link did not go quiet");
        }
    }

    static List<FixMessage> parseAll(byte[] bytes) {
        FixDecoder decoder = new FixDecoder();
        decoder.feed(bytes, 0, bytes.length);
        List<FixMessage> out = new ArrayList<>();
        FixMessage m;
        while ((m = decoder.poll()) != null) {
            out.add(m);
        }
        return out;
    }

    static FixMessage.Builder logon(int heartbeatSeconds) {
        return FixMessage.builder(FixMessage.LOGON)
                .field(FixMessage.ENCRYPT_METHOD, "0")
                .field(FixMessage.HEART_BT_INT, heartbeatSeconds);
    }

    static FixMessage.Builder order(String clOrdId) {
        return FixMessage.builder(FixMessage.NEW_ORDER_SINGLE)
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, "EURUSD")
                .field(FixMessage.SIDE, '1')
                .field(FixMessage.ORDER_QTY, 100L)
                .field(FixMessage.ORD_TYPE, '2')
                .field(FixMessage.PRICE, 1.0851);
    }
}

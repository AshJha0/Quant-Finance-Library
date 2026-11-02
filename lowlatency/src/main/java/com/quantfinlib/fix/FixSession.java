package com.quantfinlib.fix;

import com.quantfinlib.core.Side;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * FIX 4.4 session, initiator or acceptor, implementing the session layer of
 * FIX 4.4 Volume 2 ("Session Protocol") that is needed to trade: Logon,
 * Heartbeat and TestRequest, sequence numbers with gap detection and
 * recovery, Reject, SequenceReset, Logout, and the order / execution report
 * application flow. Zero dependencies.
 *
 * <h2>Logon</h2>
 * The first message received must be a Logon; anything else disconnects
 * without processing it. An acceptor validates CompIDs, SendingTime and
 * HeartBtInt(108), then asks {@link Listener#onLogonRequest} (credentials,
 * permissions); a rejection answers with Logout carrying the reason. An
 * accepted Logon is answered with a Logon using the initiator's HeartBtInt,
 * and only then, if the initiator's MsgSeqNum is above the expected one, a
 * ResendRequest follows (Vol 2: the Logon reply must be the first message).
 *
 * <h2>Validation of every message (Vol 2, "Message Recovery" and "Session
 * Level Test Cases" 2a-2t)</h2>
 * <ul>
 *   <li>SenderCompID(49)/TargetCompID(56) must match the configured pair, else
 *       Reject(373=9), Logout, disconnect.</li>
 *   <li>SendingTime(52) must be within the configured tolerance of the wall
 *       clock, else Reject(373=10), Logout, disconnect.</li>
 *   <li>MsgSeqNum above the expected: ResendRequest, message discarded (Logout
 *       and ResendRequest still act). Below without PossDupFlag(43): Logout,
 *       disconnect. Below with PossDup: ignored.</li>
 *   <li>PossDupFlag=Y at the expected number requires OrigSendingTime(122) no
 *       later than SendingTime; missing is Reject(373=1), later is
 *       Reject(373=10) and Logout.</li>
 *   <li>A garbled frame (CheckSum, BodyLength) is ignored without consuming
 *       its number; a well-framed message with a bad field (non-numeric tag,
 *       empty value) gets Reject(35=3) and consumes its number.</li>
 *   <li>SequenceReset-GapFill is sequenced like any message and must move the
 *       number forward; SequenceReset-Reset ignores MsgSeqNum and a NewSeqNo
 *       below the expected one is rejected (373=5).</li>
 * </ul>
 *
 * <h2>Heartbeats</h2>
 * All timing reads an injected {@link FixClock}. {@link #onTimer()} sends a
 * Heartbeat after one interval of outbound silence, a TestRequest with a
 * fresh TestReqID(112) after 1.5 intervals of inbound silence, and
 * disconnects after 2.5. Only a Heartbeat echoing the outstanding TestReqID
 * answers the probe. Socket sessions run {@code onTimer()} on a thread that
 * parks until the next deadline; {@link #overStream} sessions are driven by
 * the caller, which makes the state machine testable step by step.
 *
 * <h2>Persistence and resend</h2>
 * Every outbound message is recorded in the {@link FixSessionStore} and the
 * store is synced before the bytes are written, so after a crash a
 * ResendRequest replays the real application messages (PossDupFlag=Y,
 * OrigSendingTime) and only administrative runs are coalesced into
 * SequenceReset-GapFill. Requests reaching below the store's retention window
 * are reported through {@link Listener#onResendUnavailable}.
 * {@link #sendBatch} shares one store sync across several messages.
 *
 * <h2>ResetSeqNumFlag(141)</h2>
 * {@link Config#withResetOnLogon()} makes the initiator send 141=Y with
 * 34=1 and restart both counters; the receiving side resets its store (the
 * file store rolls its journal) and confirms with 141=Y.
 *
 * <h2>Session schedule</h2>
 * With a {@link FixSessionSchedule} in {@link FixSessionOptions}, a Logon
 * outside the session window is refused (acceptor: Logout with the reason;
 * initiator: {@link #sendLogon()} throws), an established session sends
 * Logout when {@link #onTimer()} finds the window closed, and the store rolls
 * (both counters back to 1, history discarded) the first time the session is
 * created, logs on or ticks in a new period. The period the store belongs to
 * is kept by {@link FixSessionStore#saveSessionStartMillis}, so a process
 * restarted the next morning resets instead of resuming yesterday's numbers.
 *
 * <h2>NextExpectedMsgSeqNum(789)</h2>
 * With {@link FixSessionOptions#nextExpectedMsgSeqNum()} the Logon carries
 * 789, the next MsgSeqNum this side expects. A peer 789 above the number this
 * side is about to use is a fatal error (messages the peer claims to expect
 * were never sent): Logout and disconnect. A lower 789 makes this side replay
 * the missing range right after the Logon exchange, as it would for a
 * ResendRequest. When the peer's Logon carries 789 and its MsgSeqNum is
 * above the expected one, this side does not send a ResendRequest: the peer
 * replays the gap and the Logon's own number is consumed once the gap is
 * closed. The acceptor's reply carries 789 whenever the initiator's Logon
 * did. Source: FIX 4.4 errata (Logon, tag 789) and FIXT 1.1 "Session
 * Protocol", section on NextExpectedMsgSeqNum.
 *
 * <p>Not implemented: encryption.</p>
 */
public final class FixSession implements AutoCloseable {

    /**
     * Session configuration.
     *
     * @param heartbeatSeconds            HeartBtInt(108) the initiator proposes; an acceptor
     *                                    adopts the initiator's value instead
     * @param sendingTimeToleranceSeconds maximum |SendingTime - now|; 0 disables the check
     */
    public record Config(String senderCompId, String targetCompId, int heartbeatSeconds,
                         String username, String password, boolean resetOnLogon,
                         int sendingTimeToleranceSeconds) {

        public static final int DEFAULT_SENDING_TIME_TOLERANCE_SECONDS = 120;

        public Config {
            if (heartbeatSeconds < 1) {
                throw new IllegalArgumentException("heartbeat must be >= 1s");
            }
            if (sendingTimeToleranceSeconds < 0) {
                throw new IllegalArgumentException("sending time tolerance must be >= 0");
            }
        }

        public Config(String senderCompId, String targetCompId, int heartbeatSeconds) {
            this(senderCompId, targetCompId, heartbeatSeconds, null, null, false,
                    DEFAULT_SENDING_TIME_TOLERANCE_SECONDS);
        }

        public Config(String senderCompId, String targetCompId, int heartbeatSeconds,
                      String username, String password) {
            this(senderCompId, targetCompId, heartbeatSeconds, username, password, false,
                    DEFAULT_SENDING_TIME_TOLERANCE_SECONDS);
        }

        public Config(String senderCompId, String targetCompId, int heartbeatSeconds,
                      String username, String password, boolean resetOnLogon) {
            this(senderCompId, targetCompId, heartbeatSeconds, username, password, resetOnLogon,
                    DEFAULT_SENDING_TIME_TOLERANCE_SECONDS);
        }

        /** Adds Username(553)/Password(554) to the initiator's Logon. */
        public Config withCredentials(String username, String password) {
            return new Config(senderCompId, targetCompId, heartbeatSeconds, username, password,
                    resetOnLogon, sendingTimeToleranceSeconds);
        }

        /**
         * Initiator sends ResetSeqNumFlag(141)=Y on Logon and restarts both
         * sequence counters at 1. The peer resets its own counters and
         * confirms with 141=Y; the stored history is discarded, so messages
         * sent before the reset can no longer be resent.
         */
        public Config withResetOnLogon() {
            return new Config(senderCompId, targetCompId, heartbeatSeconds, username, password,
                    true, sendingTimeToleranceSeconds);
        }

        /** Maximum accepted |SendingTime - now| in seconds; 0 disables the check. */
        public Config withSendingTimeTolerance(int seconds) {
            return new Config(senderCompId, targetCompId, heartbeatSeconds, username, password,
                    resetOnLogon, seconds);
        }
    }

    /** Acceptor's answer to an inbound Logon. */
    public record LogonDecision(boolean accepted, String text) {

        private static final LogonDecision ACCEPT = new LogonDecision(true, null);

        public static LogonDecision accept() {
            return ACCEPT;
        }

        /** Rejects the Logon; {@code text} is sent in the Logout's Text(58). */
        public static LogonDecision reject(String text) {
            return new LogonDecision(false, text);
        }
    }

    /** Session callbacks; invoked on the thread that feeds the session its bytes. */
    public interface Listener {

        /**
         * Acceptor only: decides on an inbound Logon before it is answered
         * (Username(553)/Password(554), permissions). Default accepts.
         */
        default LogonDecision onLogonRequest(FixSession session, FixMessage logon) {
            return LogonDecision.accept();
        }

        default void onLogon(FixSession session) {
        }

        default void onLogout(FixSession session) {
        }

        /** Execution report; {@link ExecutionReport#possDup()} marks a resend replay. */
        default void onExecutionReport(FixSession session, ExecutionReport report) {
        }

        default void onNewOrderSingle(FixSession session, NewOrderSingle order) {
        }

        default void onOrderCancelRequest(FixSession session, OrderCancelRequest request) {
        }

        default void onOrderCancelReplace(FixSession session, OrderCancelReplaceRequest request) {
        }

        /** OrderCancelReject (35=9): the raw message (ClOrdID, OrigClOrdID, CxlRejReason). */
        default void onOrderCancelReject(FixSession session, FixMessage message) {
        }

        /** BusinessMessageReject (35=j). */
        default void onBusinessMessageReject(FixSession session, long refSeqNum, String refMsgType,
                                             String text) {
        }

        /** Session-level Reject (35=3) received from the peer. */
        default void onReject(FixSession session, long refSeqNum, String text) {
        }

        /** Heartbeats, test requests, the accepted Logon and unrecognised message types. */
        default void onMessage(FixSession session, FixMessage message) {
        }

        default void onSequenceGap(long expectedSeqNum, long receivedSeqNum) {
        }

        /** This session serviced a peer's ResendRequest for [beginSeqNo, endSeqNo]. */
        default void onResendServed(long beginSeqNo, long endSeqNo) {
        }

        /**
         * A peer's ResendRequest reached below the store's retention window:
         * application messages in [beginSeqNo, endSeqNo] may have existed and
         * were covered by a GapFill. Needs out-of-band reconciliation.
         */
        default void onResendUnavailable(long beginSeqNo, long endSeqNo) {
        }

        /** A garbled frame (bad CheckSum or BodyLength) was discarded. */
        default void onGarbledMessage(String reason) {
        }

        default void onDisconnect(String reason) {
        }
    }

    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final long ESTABLISH_TIMEOUT_SECONDS = 10;
    private static final long NOT_ESTABLISHED_TIMER_NANOS = 50_000_000L;
    private static final long BAD_TIME = FixSessionParsing.BAD_TIME;
    private static final Set<String> ADMIN_TYPES = Set.of(
            FixMessage.HEARTBEAT, FixMessage.TEST_REQUEST, FixMessage.RESEND_REQUEST,
            FixMessage.REJECT, FixMessage.SEQUENCE_RESET, FixMessage.LOGOUT, FixMessage.LOGON);

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Config config;
    private final Listener listener;
    private final boolean acceptor;
    private final FixClock clock;
    private final Object writeLock = new Object();
    private final FixDecoder decoder = new FixDecoder();
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private final CountDownLatch logoutLatch = new CountDownLatch(1);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();

    private volatile boolean running = true;
    private volatile boolean established;
    private volatile boolean logoutSent;
    private volatile long lastSentNanos;
    private volatile long lastReceivedNanos;
    private volatile long heartbeatNanos;
    private volatile String pendingTestReqId;
    private long testReqCounter;              // heartbeat path only
    private final FixSessionStore store;
    private long outgoingSeq;                 // guarded by writeLock
    private volatile long expectedIncomingSeq; // written by the reader only
    private boolean resendPending;            // reader only
    private long resendTriggerSeq;            // reader only
    private final boolean sentResetLogon;     // this side initiated the 141=Y reset
    private final Thread readerThread;
    private final Thread heartbeatThread;
    private final FixSessionSchedule schedule;    // null: always open, never rolls
    private final boolean useNextExpected;
    private long ourLogonSeq = -1;                // guarded by writeLock
    private final FixVersion version;
    private volatile String peerDefaultApplVerId;
    private volatile long pendingLogonSeq;        // peer Logon number consumed after its 789 replay

    private FixSession(Socket socket, OutputStream out, Config config, Listener listener,
                       boolean acceptor, FixSessionStore store, FixClock clock) throws IOException {
        this(socket, out, config, listener, acceptor, store, clock, FixSessionOptions.none());
    }

    private FixSession(Socket socket, OutputStream out, Config config, Listener listener,
                       boolean acceptor, FixSessionStore store, FixClock clock,
                       FixSessionOptions options) throws IOException {
        this.socket = socket;
        if (socket != null) {
            socket.setTcpNoDelay(true);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        } else {
            this.in = null;
            this.out = out;
        }
        this.config = config;
        this.listener = listener;
        this.acceptor = acceptor;
        this.store = store;
        this.clock = clock;
        this.lastSentNanos = clock.nanoTime();
        this.lastReceivedNanos = lastSentNanos;
        this.heartbeatNanos = config.heartbeatSeconds() * 1_000_000_000L;
        // An initiator configured for reset-on-logon starts both counters at
        // 1 before any thread runs (its Logon goes out as 34=1, 141=Y); the
        // store rolls so the abandoned history cannot be replayed.
        this.schedule = options.schedule();
        this.useNextExpected = options.nextExpectedMsgSeqNum();
        this.version = options.version();
        this.sentResetLogon = !acceptor && config.resetOnLogon();
        if (sentResetLogon) {
            store.reset();
        }
        if (schedule != null) {
            long period = schedule.sessionStartMillis(clock.currentTimeMillis());
            long recorded = store.sessionStartMillis();
            if (recorded != FixSessionStore.UNKNOWN_SESSION_START && recorded != period) {
                store.reset();
            }
            store.saveSessionStartMillis(period);
        }
        this.outgoingSeq = store.nextOutgoingSeq();
        this.expectedIncomingSeq = store.expectedIncomingSeq();
        if (socket != null) {
            this.readerThread = new Thread(this::readerLoop, "fix-reader-" + config.senderCompId());
            this.heartbeatThread = new Thread(this::heartbeatLoop,
                    "fix-heartbeat-" + config.senderCompId());
            readerThread.setDaemon(true);
            heartbeatThread.setDaemon(true);
        } else {
            this.readerThread = null;
            this.heartbeatThread = null;
        }
    }

    /** Protocol version this session speaks (FIX 4.4 unless set in {@link FixSessionOptions}). */
    public FixVersion version() {
        return version;
    }

    /** DefaultApplVerID(1137) the peer sent on a FIXT.1.1 Logon, or null before logon or pre-FIXT. */
    public String peerDefaultApplVerId() {
        return peerDefaultApplVerId;
    }

    /** Connects, sends Logon, and blocks until the handshake completes. */
    public static FixSession initiate(String host, int port, Config config, Listener listener)
            throws IOException {
        return initiate(host, port, config, listener, FixSessionStore.inMemory());
    }

    /** As {@link #initiate(String, int, Config, Listener)} with a durable session store. */
    public static FixSession initiate(String host, int port, Config config, Listener listener,
                                      FixSessionStore store) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        FixSession session = new FixSession(socket, null, config, listener, false, store,
                FixClock.system());
        session.readerThread.start();
        session.heartbeatThread.start();
        session.sendLogon();
        session.awaitEstablished();
        return session;
    }

    /** As {@link #initiate(String, int, Config, Listener, FixSessionStore)} with session options. */
    public static FixSession initiate(String host, int port, Config config, Listener listener,
                                      FixSessionStore store, FixSessionOptions options)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        FixSession session = new FixSession(socket, null, config, listener, false, store,
                FixClock.system(), java.util.Objects.requireNonNull(options, "options"));
        session.readerThread.start();
        session.heartbeatThread.start();
        try {
            session.sendLogon();
        } catch (IllegalStateException e) {
            session.close();
            throw new IOException(e.getMessage(), e);
        }
        session.awaitEstablished();
        return session;
    }

    /** Accepts one connection, awaits the peer's Logon, replies, and returns established. */
    public static FixSession accept(ServerSocket server, Config config, Listener listener)
            throws IOException {
        return accept(server, config, listener, FixSessionStore.inMemory());
    }

    /** As {@link #accept(ServerSocket, Config, Listener)} with a durable session store. */
    public static FixSession accept(ServerSocket server, Config config, Listener listener,
                                    FixSessionStore store) throws IOException {
        FixSession session = new FixSession(server.accept(), null, config, listener, true, store,
                FixClock.system());
        session.readerThread.start();
        session.heartbeatThread.start();
        session.awaitEstablished();
        return session;
    }

    /** As {@link #accept(ServerSocket, Config, Listener, FixSessionStore)} with session options. */
    public static FixSession accept(ServerSocket server, Config config, Listener listener,
                                    FixSessionStore store, FixSessionOptions options)
            throws IOException {
        FixSession session = new FixSession(server.accept(), null, config, listener, true, store,
                FixClock.system(), java.util.Objects.requireNonNull(options, "options"));
        session.readerThread.start();
        session.heartbeatThread.start();
        session.awaitEstablished();
        return session;
    }

    /**
     * As {@link #overStream(Config, Listener, boolean, FixSessionStore, OutputStream, FixClock)}
     * with a session schedule and NextExpectedMsgSeqNum(789) behaviour.
     */
    public static FixSession overStream(Config config, Listener listener, boolean acceptor,
                                        FixSessionStore store, OutputStream out, FixClock clock,
                                        FixSessionOptions options) {
        try {
            return new FixSession(null, out, config, listener, acceptor, store, clock,
                    java.util.Objects.requireNonNull(options, "options"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // unreachable without a socket
        }
    }

    /**
     * A session over a caller-owned transport, with no threads: outbound bytes
     * go to {@code out}; the caller feeds inbound bytes to {@link #onBytes}
     * and calls {@link #onTimer()} (at the latest after the nanoseconds it
     * returns) from one driver thread. An initiator starts with
     * {@link #sendLogon()}. Use it for custom transports (TLS, NIO selectors)
     * and for deterministic tests with a manual {@link FixClock}.
     */
    public static FixSession overStream(Config config, Listener listener, boolean acceptor,
                                        FixSessionStore store, OutputStream out, FixClock clock) {
        try {
            return new FixSession(null, out, config, listener, acceptor, store, clock);
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // unreachable without a socket
        }
    }

    private void awaitEstablished() throws IOException {
        try {
            if (!logonLatch.await(ESTABLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                close();
                throw new IOException("FIX logon timed out after " + ESTABLISH_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("interrupted during FIX logon", e);
        }
        if (!established) {
            throw new IOException("FIX logon failed");
        }
    }

    /**
     * Initiator: sends the Logon (HeartBtInt, optional 141=Y, 789 and
     * credentials).
     *
     * @throws IllegalStateException on an acceptor, or outside the schedule's session window
     */
    public void sendLogon() {
        if (acceptor) {
            throw new IllegalStateException("an acceptor answers Logon, it does not send one first");
        }
        if (schedule != null) {
            long now = clock.currentTimeMillis();
            if (!schedule.isInSession(now)) {
                throw new IllegalStateException("logon outside session window (" + schedule + ")");
            }
            rollIfNewPeriod(now);
        }
        FixMessage.Builder logon = FixSessionMessages.logon(config, version, useNextExpected,
                expectedIncomingSeq);
        synchronized (writeLock) {
            ourLogonSeq = outgoingSeq;
            send(logon);
        }
    }

    /**
     * Rolls the store and both counters when {@code now} lies in a later
     * session period than the store's. Only called while not established.
     */
    private void rollIfNewPeriod(long now) {
        long period = schedule.sessionStartMillis(now);
        if (store.sessionStartMillis() == period) {
            return;
        }
        synchronized (writeLock) {
            store.reset();
            outgoingSeq = 1;
        }
        store.saveSessionStartMillis(period);
        expectedIncomingSeq = 1;
        resendPending = false;
        resendTriggerSeq = 0;
        pendingLogonSeq = 0;
    }

    // ------------------------------------------------------------------
    // Application messages
    // ------------------------------------------------------------------

    /**
     * Sends a NewOrderSingle. {@code limitPrice = NaN} sends a market order.
     * Returns the ClOrdID for correlation with execution reports.
     */
    public String sendNewOrderSingle(String clOrdId, String symbol, Side side, long quantity,
                                     double limitPrice, char timeInForce) {
        requireEstablished();
        send(FixSessionMessages.newOrderSingle(clOrdId, symbol, side, quantity, limitPrice,
                timeInForce, utcNow()));
        return clOrdId;
    }

    /** Venue side: sends an execution report for a received order. */
    public void sendExecutionReport(ExecutionReport report) {
        requireEstablished();
        FixMessage.Builder b = report.toBuilder().field(FixMessage.TRANSACT_TIME, utcNow());
        if (version.requiresExecTransType()) {
            b.field(FixVersion.EXEC_TRANS_TYPE, '0');   // FIX 4.2: ExecTransType New
        }
        send(b);
    }

    /** Sends an OrderCancelRequest (35=F) for a working order. */
    public String sendOrderCancelRequest(String clOrdId, String origClOrdId, String symbol,
                                         Side side, long quantity) {
        requireEstablished();
        send(FixSessionMessages.cancelRequest(clOrdId, origClOrdId, symbol, side, quantity,
                utcNow()));
        return clOrdId;
    }

    /** Sends an OrderCancelReplaceRequest (35=G); {@code limitPrice = NaN} = market. */
    public String sendOrderCancelReplace(String clOrdId, String origClOrdId, String symbol,
                                         Side side, long quantity, double limitPrice,
                                         char timeInForce) {
        requireEstablished();
        send(FixSessionMessages.cancelReplace(clOrdId, origClOrdId, symbol, side, quantity,
                limitPrice, timeInForce, utcNow()));
        return clOrdId;
    }

    /**
     * Sends several messages with one store sync (group commit): all are
     * recorded, the store is forced once, then all are written.
     */
    public void sendBatch(List<FixMessage.Builder> builders) {
        requireEstablished();
        sendAll(builders.toArray(new FixMessage.Builder[0]));
    }

    /** Sends a session-level Reject (35=3) referring to an inbound message. */
    public void sendReject(long refSeqNum, String text) {
        sendReject(refSeqNum, null, FixMessage.REJECT_OTHER, 0, text);
    }

    /**
     * Sends a session-level Reject with RefMsgType(372), SessionRejectReason(373)
     * and RefTagID(371) when known ({@code refMsgType} null and
     * {@code refTagId} 0 omit them).
     */
    public void sendReject(long refSeqNum, String refMsgType, int reason, int refTagId,
                           String text) {
        send(FixSessionMessages.reject(refSeqNum, refMsgType, reason, refTagId, text));
    }

    /** Initiates the Logout handshake and closes the session. */
    public void logout() {
        if (!established) {
            close();
            return;
        }
        logoutSent = true;
        send(FixMessage.builder(FixMessage.LOGOUT));
        if (socket != null) {
            try {
                // Best-effort teardown: the socket closes whether or not the
                // peer acknowledges in time.
                logoutLatch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            close();
        }
    }

    public boolean isEstablished() {
        return established;
    }

    /** Next incoming sequence number this session expects. */
    public long expectedIncomingSeqNum() {
        return expectedIncomingSeq;
    }

    /** Next outgoing sequence number. */
    public long nextOutgoingSeqNum() {
        synchronized (writeLock) {
            return outgoingSeq;
        }
    }

    /** Heartbeat interval in force, in seconds (the initiator's HeartBtInt on an acceptor). */
    public int heartbeatIntervalSeconds() {
        return (int) (heartbeatNanos / 1_000_000_000L);
    }

    @Override
    public void close() {
        running = false;
        established = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        if (heartbeatThread != null) {
            LockSupport.unpark(heartbeatThread);
        }
        if (store instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    // ------------------------------------------------------------------
    // Outbound
    // ------------------------------------------------------------------

    void send(FixMessage.Builder builder) {
        sendAll(builder);
    }

    private void sendAll(FixMessage.Builder... builders) {
        try {
            synchronized (writeLock) {
                String sendingTime = utcNow();
                byte[][] frames = new byte[builders.length][];
                for (int i = 0; i < builders.length; i++) {
                    FixMessage.Builder b = builders[i];
                    long seq = outgoingSeq++;
                    store.recordSent(seq, new FixSessionStore.StoredMessage(
                            b.msgType(), b.body(), sendingTime), !ADMIN_TYPES.contains(b.msgType()));
                    frames[i] = b.encode(version, config.senderCompId(), config.targetCompId(),
                            seq, sendingTime, false, null);
                }
                store.sync();   // durable before the peer can see the sequence numbers
                for (byte[] frame : frames) {
                    out.write(frame);
                }
                out.flush();
                lastSentNanos = clock.nanoTime();
            }
        } catch (IOException | UncheckedIOException e) {
            disconnect("send failed: " + e.getMessage());
        }
    }

    /** Writes a replayed/gap-fill message with an explicit (past) sequence number. */
    private void writeReplay(FixMessage.Builder builder, long seqNum, String origSendingTime)
            throws IOException {
        synchronized (writeLock) {
            out.write(builder.encode(version, config.senderCompId(), config.targetCompId(),
                    seqNum, utcNow(), true, origSendingTime));
            out.flush();
            lastSentNanos = clock.nanoTime();
        }
    }

    /** Test hook: skips outgoing sequence numbers to provoke a gap on the peer. */
    void forceOutgoingSeq(long seqNum) {
        synchronized (writeLock) {
            outgoingSeq = seqNum;
        }
    }

    private String utcNow() {
        return UTC_TIMESTAMP.format(Instant.ofEpochMilli(clock.currentTimeMillis()));
    }

    // ------------------------------------------------------------------
    // Inbound
    // ------------------------------------------------------------------

    private void readerLoop() {
        byte[] chunk = new byte[4096];
        try {
            while (running) {
                int n = in.read(chunk);
                if (n < 0) {
                    disconnect("peer closed connection");
                    return;
                }
                onBytes(chunk, 0, n);
            }
        } catch (IOException e) {
            if (running) {
                disconnect("read failed: " + e.getMessage());
            }
        }
    }

    /**
     * Feeds inbound bytes in any fragmentation; complete messages are
     * processed before this returns. One driver thread only.
     */
    public void onBytes(byte[] data, int offset, int length) {
        if (!running) {
            return;
        }
        lastReceivedNanos = clock.nanoTime();
        decoder.feed(data, offset, length);
        while (running) {
            FixMessage message;
            try {
                message = decoder.poll();
            } catch (FixParseException e) {
                handleParseFailure(e);
                continue;
            } catch (IllegalStateException e) {
                // Framing lost (BodyLength corrupt): no way to find the next
                // message boundary, so the stream is unusable.
                disconnect("stream corrupt: " + e.getMessage());
                return;
            }
            if (message == null) {
                return;
            }
            try {
                handle(message);
            } catch (RuntimeException e) {
                disconnect("error handling 35=" + message.msgType() + ": " + e);
                return;
            }
        }
    }

    private void handleParseFailure(FixParseException e) {
        long seq = e.seqNum();
        if (e.garbled() || seq < 1) {
            listener.onGarbledMessage(e.getMessage());
            return;
        }
        if (!established) {
            disconnect("malformed first message: " + e.getMessage());
            return;
        }
        if (seq == expectedIncomingSeq) {
            sendReject(seq, e.msgType(), e.rejectReason(), e.refTagId(), e.getMessage());
            advanceIncoming(seq + 1);
        } else if (seq > expectedIncomingSeq) {
            onGap(seq);
        }
    }

    private void handle(FixMessage m) {
        String type = m.msgType();
        long seq = parsePositive(m.getString(FixMessage.MSG_SEQ_NUM, null));
        if (seq < 1) {
            logoutAndDisconnect("MsgSeqNum(34) missing or invalid");
            return;
        }
        if (!version.beginString().equals(m.beginString())) {
            // FIX session rules: a wrong BeginString ends the session.
            logoutAndDisconnect("BeginString " + m.beginString() + " does not match session "
                    + version.beginString());
            return;
        }
        if (!established) {
            if (FixMessage.LOGOUT.equals(type)) {
                disconnect("logout before logon: " + m.getString(FixMessage.TEXT, ""));
                return;
            }
            if (!FixMessage.LOGON.equals(type)) {
                disconnect("first message was 35=" + type + ", not Logon");
                return;
            }
            handleLogon(m, seq);
            return;
        }
        if (!compIdsMatch(m)) {
            rejectAndLogout(seq, type, FixMessage.REJECT_COMP_ID_PROBLEM,
                    FixMessage.SENDER_COMP_ID, "CompID problem");
            return;
        }
        if (!sendingTimeAcceptable(m, seq, type)) {
            return;
        }
        if (FixMessage.SEQUENCE_RESET.equals(type)) {
            handleSequenceReset(m, seq);
            return;
        }
        if (seq > expectedIncomingSeq) {
            onGap(seq);
            // Logout and ResendRequest still act while our inbound gap is
            // open: gap recovery on both sides depends on the peer's resend
            // requests being serviced.
            if (!(FixMessage.LOGOUT.equals(type) || FixMessage.RESEND_REQUEST.equals(type))) {
                return;
            }
        } else if (seq < expectedIncomingSeq) {
            if (m.isFlagSet(FixMessage.POSS_DUP_FLAG)) {
                return;   // duplicate from a resend overlap
            }
            logoutAndDisconnect("MsgSeqNum too low, expecting " + expectedIncomingSeq
                    + " but received " + seq);
            return;
        } else {
            if (m.isFlagSet(FixMessage.POSS_DUP_FLAG) && !possDupHeaderValid(m, seq, type)) {
                return;
            }
            advanceIncoming(seq + 1);
        }
        dispatch(m, seq, type);
    }

    private void dispatch(FixMessage m, long seq, String type) {
        switch (type) {
            case FixMessage.LOGON -> listener.onMessage(this, m);   // repeated Logon: ignored
            case FixMessage.HEARTBEAT -> {
                String id = pendingTestReqId;
                if (id != null && id.equals(m.getString(FixMessage.TEST_REQ_ID, null))) {
                    pendingTestReqId = null;
                }
                listener.onMessage(this, m);
            }
            case FixMessage.TEST_REQUEST -> {
                if (!m.has(FixMessage.TEST_REQ_ID)) {
                    sendReject(seq, type, FixMessage.REJECT_REQUIRED_TAG_MISSING,
                            FixMessage.TEST_REQ_ID, "TestReqID missing");
                    return;
                }
                send(FixMessage.builder(FixMessage.HEARTBEAT)
                        .field(FixMessage.TEST_REQ_ID, m.getString(FixMessage.TEST_REQ_ID)));
                listener.onMessage(this, m);
            }
            case FixMessage.LOGOUT -> {
                if (!logoutSent) {
                    send(FixMessage.builder(FixMessage.LOGOUT));   // confirm
                }
                established = false;
                logoutLatch.countDown();
                listener.onLogout(this);
                running = false;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // closing anyway
                    }
                }
            }
            case FixMessage.RESEND_REQUEST -> handleResendRequest(m, seq);
            case FixMessage.REJECT -> listener.onReject(this,
                    parsePositive(m.getString(FixMessage.REF_SEQ_NUM, null)),
                    m.getString(FixMessage.TEXT, ""));
            case FixMessage.EXECUTION_REPORT -> dispatchApp(seq, type,
                    () -> listener.onExecutionReport(this, ExecutionReport.fromMessage(m)));
            case FixMessage.NEW_ORDER_SINGLE -> dispatchApp(seq, type,
                    () -> listener.onNewOrderSingle(this, NewOrderSingle.fromMessage(m)));
            case FixMessage.ORDER_CANCEL_REQUEST -> dispatchApp(seq, type,
                    () -> listener.onOrderCancelRequest(this, OrderCancelRequest.fromMessage(m)));
            case FixMessage.ORDER_CANCEL_REPLACE_REQUEST -> dispatchApp(seq, type,
                    () -> listener.onOrderCancelReplace(this, OrderCancelReplaceRequest.fromMessage(m)));
            case FixMessage.ORDER_CANCEL_REJECT -> dispatchApp(seq, type,
                    () -> listener.onOrderCancelReject(this, m));
            case FixMessage.BUSINESS_MESSAGE_REJECT -> dispatchApp(seq, type,
                    () -> listener.onBusinessMessageReject(this,
                            parsePositive(m.getString(FixMessage.REF_SEQ_NUM, null)),
                            m.getString(FixMessage.REF_MSG_TYPE, ""),
                            m.getString(FixMessage.TEXT, "")));
            default -> listener.onMessage(this, m);
        }
    }

    /** Dispatches an application message; failures answer with a session Reject. */
    private void dispatchApp(long seq, String type, Runnable dispatch) {
        try {
            dispatch.run();
        } catch (RuntimeException e) {
            String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sendReject(seq, type, FixMessage.REJECT_OTHER, 0, text);
        }
    }

    private void handleLogon(FixMessage m, long seq) {
        if (!compIdsMatch(m)) {
            refuseLogon("CompID problem: got 49=" + m.getString(FixMessage.SENDER_COMP_ID, "")
                    + " 56=" + m.getString(FixMessage.TARGET_COMP_ID, ""));
            return;
        }
        if (version.isFixt()) {
            String applVer = m.getString(FixVersion.DEFAULT_APPL_VER_ID, null);
            if (applVer == null) {
                refuseLogon("DefaultApplVerID(1137) required on a FIXT.1.1 Logon");
                return;
            }
            if (!applVer.equals(version.applVerId())) {
                refuseLogon("DefaultApplVerID(1137)=" + applVer + " not supported, expected "
                        + version.applVerId());
                return;
            }
            peerDefaultApplVerId = applVer;
        }
        if (schedule != null && acceptor) {
            long now = clock.currentTimeMillis();
            if (!schedule.isInSession(now)) {
                refuseLogon("logon outside session window");
                return;
            }
            rollIfNewPeriod(now);
        }
        String nextExpectedRaw = m.getString(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, null);
        long peerNextExpected = parsePositive(nextExpectedRaw);
        if (nextExpectedRaw != null && peerNextExpected < 1) {
            refuseLogon("NextExpectedMsgSeqNum(789) invalid: " + nextExpectedRaw);
            return;
        }
        String timeProblem = sendingTimeProblem(m);
        if (timeProblem != null) {
            refuseLogon(timeProblem);
            return;
        }
        boolean reset = m.isFlagSet(FixMessage.RESET_SEQ_NUM_FLAG);
        if (reset && seq != 1) {
            refuseLogon("ResetSeqNumFlag(141)=Y requires MsgSeqNum 1, got " + seq);
            return;
        }
        long peerHeartbeat = parsePositive(m.getString(FixMessage.HEART_BT_INT, null));
        if (acceptor) {
            if (peerHeartbeat < 0 && !"0".equals(m.getString(FixMessage.HEART_BT_INT, null))) {
                refuseLogon("HeartBtInt(108) missing or invalid");
                return;
            }
            LogonDecision decision;
            try {
                decision = listener.onLogonRequest(this, m);
            } catch (RuntimeException e) {
                decision = LogonDecision.reject("logon check failed: " + e.getMessage());
            }
            if (decision == null || !decision.accepted()) {
                refuseLogon(decision == null || decision.text() == null
                        ? "logon rejected" : decision.text());
                return;
            }
            heartbeatNanos = Math.max(peerHeartbeat, 0) * 1_000_000_000L;
        }
        if (reset && !m.isFlagSet(FixMessage.POSS_DUP_FLAG)) {
            applyLogonSeqReset();
        }
        if (seq < expectedIncomingSeq) {
            refuseLogon("MsgSeqNum too low, expecting " + expectedIncomingSeq
                    + " but received " + seq);
            return;
        }
        // Highest 789 the peer may send: our reply's number (acceptor), or one
        // past our Logon (initiator).
        long maxPeerExpected;
        synchronized (writeLock) {
            maxPeerExpected = acceptor ? outgoingSeq : ourLogonSeq + 1;
        }
        boolean peerUses789 = peerNextExpected >= 1;
        if (peerUses789 && peerNextExpected > maxPeerExpected) {
            String reason = "NextExpectedMsgSeqNum(789) " + peerNextExpected
                    + " higher than next to send " + maxPeerExpected;
            if (acceptor) {
                refuseLogon(reason);
            } else {
                logoutAndDisconnect(reason);
            }
            return;
        }
        long logonSeqOfOurs;
        if (acceptor) {
            // The Logon reply is the first message the initiator sees; any
            // ResendRequest for our inbound gap, or 789 replay, follows it.
            FixMessage.Builder reply = FixMessage.builder(FixMessage.LOGON)
                    .field(FixMessage.ENCRYPT_METHOD, "0")
                    .field(FixMessage.HEART_BT_INT, heartbeatNanos / 1_000_000_000L);
            if (version.isFixt()) {
                reply.field(FixVersion.DEFAULT_APPL_VER_ID, version.applVerId());
            }
            if (reset) {
                reply.field(FixMessage.RESET_SEQ_NUM_FLAG, "Y");
            }
            if (useNextExpected || peerUses789) {
                reply.field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM,
                        seq == expectedIncomingSeq ? seq + 1 : expectedIncomingSeq);
            }
            synchronized (writeLock) {
                ourLogonSeq = outgoingSeq;
                send(reply);
            }
        }
        synchronized (writeLock) {
            logonSeqOfOurs = ourLogonSeq;
        }
        established = true;
        logonLatch.countDown();
        if (seq > expectedIncomingSeq) {
            if (peerUses789 && (acceptor || useNextExpected)) {
                // The peer saw our 789 (or will, in our reply) and replays
                // [expected, seq - 1] itself; its Logon number is consumed
                // once that range is in.
                listener.onSequenceGap(expectedIncomingSeq, seq);
                pendingLogonSeq = seq;
                resendPending = true;
                resendTriggerSeq = seq;
            } else {
                onGap(seq);
            }
        } else {
            advanceIncoming(seq + 1);
        }
        if (peerUses789 && peerNextExpected < logonSeqOfOurs) {
            replay(peerNextExpected, logonSeqOfOurs - 1);
        }
        listener.onMessage(this, m);
        listener.onLogon(this);
    }

    private void refuseLogon(String reason) {
        send(FixMessage.builder(FixMessage.LOGOUT).field(FixMessage.TEXT, reason));
        disconnect("logon refused: " + reason);
    }

    private boolean compIdsMatch(FixMessage m) {
        return config.targetCompId().equals(m.getString(FixMessage.SENDER_COMP_ID, null))
                && config.senderCompId().equals(m.getString(FixMessage.TARGET_COMP_ID, null));
    }

    /** Null when SendingTime is present, well formed and within tolerance; else the problem. */
    private String sendingTimeProblem(FixMessage m) {
        String st = m.getString(FixMessage.SENDING_TIME, null);
        if (st == null) {
            return "SendingTime(52) missing";
        }
        long t = parseUtcMillis(st);
        if (t == BAD_TIME) {
            return "SendingTime(52) malformed: " + st;
        }
        long tolerance = config.sendingTimeToleranceSeconds() * 1000L;
        if (tolerance > 0 && Math.abs(clock.currentTimeMillis() - t) > tolerance) {
            return "SendingTime accuracy problem: " + st;
        }
        return null;
    }

    private boolean sendingTimeAcceptable(FixMessage m, long seq, String type) {
        String st = m.getString(FixMessage.SENDING_TIME, null);
        if (st == null) {
            rejectMessage(seq, type, FixMessage.REJECT_REQUIRED_TAG_MISSING,
                    FixMessage.SENDING_TIME, "SendingTime(52) missing");
            return false;
        }
        if (parseUtcMillis(st) == BAD_TIME) {
            rejectMessage(seq, type, FixMessage.REJECT_INCORRECT_DATA_FORMAT,
                    FixMessage.SENDING_TIME, "SendingTime(52) malformed");
            return false;
        }
        String problem = sendingTimeProblem(m);
        if (problem != null) {
            rejectAndLogout(seq, type, FixMessage.REJECT_SENDING_TIME_ACCURACY,
                    FixMessage.SENDING_TIME, problem);
            return false;
        }
        return true;
    }

    /** PossDupFlag=Y at the expected number: OrigSendingTime present and not after SendingTime. */
    private boolean possDupHeaderValid(FixMessage m, long seq, String type) {
        String orig = m.getString(FixMessage.ORIG_SENDING_TIME, null);
        if (orig == null) {
            rejectMessage(seq, type, FixMessage.REJECT_REQUIRED_TAG_MISSING,
                    FixMessage.ORIG_SENDING_TIME, "OrigSendingTime(122) missing on PossDup");
            return false;
        }
        long origMillis = parseUtcMillis(orig);
        if (origMillis == BAD_TIME) {
            rejectMessage(seq, type, FixMessage.REJECT_INCORRECT_DATA_FORMAT,
                    FixMessage.ORIG_SENDING_TIME, "OrigSendingTime(122) malformed");
            return false;
        }
        if (origMillis > parseUtcMillis(m.getString(FixMessage.SENDING_TIME))) {
            rejectAndLogout(seq, type, FixMessage.REJECT_SENDING_TIME_ACCURACY,
                    FixMessage.ORIG_SENDING_TIME, "OrigSendingTime after SendingTime");
            return false;
        }
        return true;
    }

    /** Session Reject; a rejected message still consumes its sequence number. */
    private void rejectMessage(long seq, String type, int reason, int refTag, String text) {
        sendReject(seq, type, reason, refTag, text);
        if (seq == expectedIncomingSeq) {
            advanceIncoming(seq + 1);
        } else if (seq > expectedIncomingSeq) {
            onGap(seq);
        }
    }

    private void rejectAndLogout(long seq, String type, int reason, int refTag, String text) {
        sendReject(seq, type, reason, refTag, text);
        logoutAndDisconnect(text);
    }

    private void logoutAndDisconnect(String reason) {
        logoutSent = true;
        send(FixMessage.builder(FixMessage.LOGOUT).field(FixMessage.TEXT, reason));
        disconnect(reason);
    }

    private void onGap(long receivedSeq) {
        listener.onSequenceGap(expectedIncomingSeq, receivedSeq);
        resendTriggerSeq = Math.max(resendTriggerSeq, receivedSeq);
        if (pendingLogonSeq > 0 && receivedSeq > pendingLogonSeq) {
            // The peer moved past its Logon without replaying the 789 gap:
            // fall back to an explicit ResendRequest.
            pendingLogonSeq = 0;
            resendPending = false;
        }
        if (!resendPending) {
            resendPending = true;
            send(FixMessage.builder(FixMessage.RESEND_REQUEST)
                    .field(FixMessage.BEGIN_SEQ_NO, expectedIncomingSeq)
                    .field(FixMessage.END_SEQ_NO, 0));
        }
    }

    /**
     * SequenceReset (35=4). GapFill mode (123=Y) is sequenced like any other
     * message and must move the number forward. Reset mode ignores MsgSeqNum;
     * a NewSeqNo below the expected one is rejected and changes nothing.
     */
    private void handleSequenceReset(FixMessage m, long seq) {
        String type = FixMessage.SEQUENCE_RESET;
        long newSeqNo = parsePositive(m.getString(FixMessage.NEW_SEQ_NO, null));
        if (newSeqNo < 1) {
            rejectMessage(seq, type, m.has(FixMessage.NEW_SEQ_NO)
                            ? FixMessage.REJECT_INCORRECT_DATA_FORMAT
                            : FixMessage.REJECT_REQUIRED_TAG_MISSING,
                    FixMessage.NEW_SEQ_NO, "NewSeqNo(36) missing or invalid");
            return;
        }
        if (!m.isFlagSet(FixMessage.GAP_FILL_FLAG)) {
            if (newSeqNo > expectedIncomingSeq) {
                advanceIncoming(newSeqNo);
            } else if (newSeqNo < expectedIncomingSeq) {
                sendReject(seq, type, FixMessage.REJECT_VALUE_INCORRECT, FixMessage.NEW_SEQ_NO,
                        "NewSeqNo " + newSeqNo + " below expected " + expectedIncomingSeq);
            }
            return;
        }
        if (seq > expectedIncomingSeq) {
            onGap(seq);
            return;
        }
        if (seq < expectedIncomingSeq) {
            if (!m.isFlagSet(FixMessage.POSS_DUP_FLAG)) {
                logoutAndDisconnect("MsgSeqNum too low, expecting " + expectedIncomingSeq
                        + " but received " + seq);
            }
            return;
        }
        if (newSeqNo <= seq) {
            rejectMessage(seq, type, FixMessage.REJECT_VALUE_INCORRECT, FixMessage.NEW_SEQ_NO,
                    "GapFill NewSeqNo " + newSeqNo + " does not move past MsgSeqNum " + seq);
            return;
        }
        advanceIncoming(newSeqNo);
    }

    /**
     * ResetSeqNumFlag(141)=Y received on a Logon: expect the peer from 1
     * again, abandon in-flight gap recovery and, unless this side initiated
     * the reset, reset the store and the outbound counter too.
     */
    private void applyLogonSeqReset() {
        if (!sentResetLogon) {
            synchronized (writeLock) {
                store.reset();
                outgoingSeq = 1;
            }
        } else {
            store.saveIncomingSeq(1);
        }
        resendPending = false;
        resendTriggerSeq = 0;
        pendingLogonSeq = 0;
        expectedIncomingSeq = 1;
    }

    private void advanceIncoming(long newExpected) {
        if (pendingLogonSeq > 0 && newExpected >= pendingLogonSeq) {
            newExpected = Math.max(newExpected, pendingLogonSeq + 1);   // the peer's Logon
            pendingLogonSeq = 0;
        }
        expectedIncomingSeq = newExpected;
        store.saveIncomingSeq(newExpected);
        if (resendPending && expectedIncomingSeq > resendTriggerSeq) {
            resendPending = false;   // gap fully recovered
        }
    }

    /**
     * Services a peer's ResendRequest: replays stored application messages
     * with PossDupFlag/OrigSendingTime and coalesces runs without a stored
     * application message into SequenceReset-GapFill.
     */
    private void handleResendRequest(FixMessage m, long seq) {
        long begin = parsePositive(m.getString(FixMessage.BEGIN_SEQ_NO, null));
        long endRequested = parsePositive(m.getString(FixMessage.END_SEQ_NO, null));
        if (begin < 1 || endRequested < 0) {
            sendReject(seq, FixMessage.RESEND_REQUEST, FixMessage.REJECT_VALUE_INCORRECT,
                    begin < 1 ? FixMessage.BEGIN_SEQ_NO : FixMessage.END_SEQ_NO,
                    "invalid BeginSeqNo/EndSeqNo");
            return;
        }
        long end;
        synchronized (writeLock) {
            long last = outgoingSeq - 1;
            end = endRequested == 0 ? last : Math.min(endRequested, last);
        }
        replay(begin, end);
    }

    /**
     * Replays stored application messages in [begin, end] with PossDupFlag and
     * OrigSendingTime, coalescing runs without one into SequenceReset-GapFill.
     */
    private void replay(long begin, long end) {
        try {
            synchronized (writeLock) {
                long firstRetained = store.firstRetainedSeq();
                if (begin < firstRetained && begin <= end) {
                    listener.onResendUnavailable(begin, Math.min(end, firstRetained - 1));
                }
                long gapFillStart = -1;
                for (long s = begin; s <= end; s++) {
                    FixSessionStore.StoredMessage stored = store.retrieve(s);
                    if (stored == null) {
                        if (gapFillStart < 0) {
                            gapFillStart = s;
                        }
                        continue;
                    }
                    if (gapFillStart >= 0) {
                        sendGapFill(gapFillStart, s);
                        gapFillStart = -1;
                    }
                    writeReplay(FixMessage.Builder.restore(stored.msgType(), stored.body()),
                            s, stored.sendingTime());
                }
                if (gapFillStart >= 0) {
                    sendGapFill(gapFillStart, end + 1);
                }
                listener.onResendServed(begin, end);
            }
        } catch (IOException | UncheckedIOException e) {
            disconnect("resend failed: " + e.getMessage());
        }
    }

    /** SequenceReset-GapFill: "seqs [seq, newSeqNo) carry nothing to resend; skip to newSeqNo". */
    private void sendGapFill(long seq, long newSeqNo) throws IOException {
        writeReplay(FixMessage.builder(FixMessage.SEQUENCE_RESET)
                        .field(FixMessage.GAP_FILL_FLAG, "Y")
                        .field(FixMessage.NEW_SEQ_NO, newSeqNo),
                seq, utcNow());
    }

    // ------------------------------------------------------------------
    // Timers
    // ------------------------------------------------------------------

    /**
     * Runs the heartbeat state machine against the clock: sends a Heartbeat
     * after one interval of outbound silence, a TestRequest after 1.5
     * intervals of inbound silence, and disconnects after 2.5. Returns the
     * nanoseconds until the next deadline.
     */
    public long onTimer() {
        if (!running) {
            return Long.MAX_VALUE;
        }
        if (schedule != null) {
            long nowMillis = clock.currentTimeMillis();
            if (established && !schedule.isInSession(nowMillis)) {
                logoutAndDisconnect("end of session");
                return Long.MAX_VALUE;
            }
            if (!established && schedule.isInSession(nowMillis)) {
                rollIfNewPeriod(nowMillis);
            }
        }
        if (!established) {
            return NOT_ESTABLISHED_TIMER_NANOS;
        }
        long hb = heartbeatNanos;
        if (hb <= 0) {
            return 1_000_000_000L;   // HeartBtInt=0: no heartbeats agreed
        }
        long now = clock.nanoTime();
        if (now - lastSentNanos >= hb) {
            send(FixMessage.builder(FixMessage.HEARTBEAT));
        }
        long silence = now - lastReceivedNanos;
        if (silence >= hb * 5 / 2) {
            disconnect("heartbeat timeout after " + silence / 1_000_000 + "ms of silence");
            return Long.MAX_VALUE;
        }
        if (silence >= hb * 3 / 2 && pendingTestReqId == null) {
            String id = "TEST-" + (++testReqCounter);
            pendingTestReqId = id;
            send(FixMessage.builder(FixMessage.TEST_REQUEST).field(FixMessage.TEST_REQ_ID, id));
        }
        long next = lastSentNanos + hb - now;
        long rx = lastReceivedNanos;
        next = Math.min(next, (pendingTestReqId == null ? rx + hb * 3 / 2 : rx + hb * 5 / 2) - now);
        return Math.max(next, 1_000_000L);
    }

    /** TestReqID of the outstanding TestRequest, or null. */
    public String pendingTestReqId() {
        return pendingTestReqId;
    }

    private void heartbeatLoop() {
        while (running) {
            long wait = onTimer();
            if (!running) {
                return;
            }
            LockSupport.parkNanos(this, Math.min(wait, 1_000_000_000L));
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private void disconnect(String reason) {
        if (!disconnectNotified.compareAndSet(false, true)) {
            return;
        }
        close();
        logonLatch.countDown();   // unblock anyone waiting on establishment
        logoutLatch.countDown();
        listener.onDisconnect(reason);
    }

    private void requireEstablished() {
        if (!established) {
            throw new IllegalStateException("FIX session not established");
        }
    }

    // ------------------------------------------------------------------
    // Parsing helpers (implementation in FixSessionParsing)
    // ------------------------------------------------------------------

    private static long parsePositive(String s) {
        return FixSessionParsing.parsePositive(s);
    }

    /**
     * UTCTimestamp {@code YYYYMMDD-HH:MM:SS[.sss...]} (FIX 4.4 Vol 1) to epoch
     * milliseconds; fractions beyond milliseconds are truncated. Returns
     * {@code Long.MIN_VALUE} when malformed.
     */
    static long parseUtcMillis(String s) {
        return FixSessionParsing.parseUtcMillis(s);
    }
}

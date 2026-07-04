package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FIX 4.4 session over TCP — initiator or acceptor — implementing the session
 * protocol needed to trade: Logon handshake, Heartbeats with TestRequest
 * probing and staleness disconnect, sequence-number tracking with gap
 * detection, Logout handshake, and the application flow NewOrderSingle out /
 * ExecutionReport in (or the reverse, on the venue side). Zero dependencies.
 *
 * <p>Scope notes (v1, documented honestly): gaps are <i>detected</i> and
 * reported via {@link Listener#onSequenceGap} but not recovered with
 * ResendRequest, and sequence numbers reset on every connection (no message
 * store). Suitable for venue simulators, test harnesses, and sessions where
 * the counterparty accepts seqnum reset on logon.</p>
 */
public final class FixSession implements AutoCloseable {

    public record Config(String senderCompId, String targetCompId, int heartbeatSeconds) {

        public Config {
            if (heartbeatSeconds < 1) {
                throw new IllegalArgumentException("heartbeat must be >= 1s");
            }
        }
    }

    /** Session callbacks; invoked on the session's reader thread. */
    public interface Listener {

        default void onLogon(FixSession session) {
        }

        default void onLogout(FixSession session) {
        }

        default void onExecutionReport(FixSession session, ExecutionReport report) {
        }

        default void onNewOrderSingle(FixSession session, NewOrderSingle order) {
        }

        /** Heartbeats, test requests and any unrecognized message types. */
        default void onMessage(FixSession session, FixMessage message) {
        }

        default void onSequenceGap(long expectedSeqNum, long receivedSeqNum) {
        }

        default void onDisconnect(String reason) {
        }
    }

    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final long ESTABLISH_TIMEOUT_SECONDS = 10;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Config config;
    private final Listener listener;
    private final boolean acceptor;
    private final Object writeLock = new Object();
    private final FixDecoder decoder = new FixDecoder();
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private final CountDownLatch logoutLatch = new CountDownLatch(1);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();

    private volatile boolean running = true;
    private volatile boolean established;
    private volatile boolean logoutSent;
    private volatile long lastSentNanos = System.nanoTime();
    private volatile long lastReceivedNanos = System.nanoTime();
    private volatile boolean testRequestPending;
    private long outgoingSeq = 1;        // guarded by writeLock
    private long expectedIncomingSeq = 1; // reader thread only
    private final Thread readerThread;
    private final Thread heartbeatThread;

    private FixSession(Socket socket, Config config, Listener listener, boolean acceptor)
            throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.config = config;
        this.listener = listener;
        this.acceptor = acceptor;
        this.readerThread = new Thread(this::readerLoop,
                "fix-reader-" + config.senderCompId());
        this.heartbeatThread = new Thread(this::heartbeatLoop,
                "fix-heartbeat-" + config.senderCompId());
        readerThread.setDaemon(true);
        heartbeatThread.setDaemon(true);
    }

    /** Connects, sends Logon, and blocks until the handshake completes. */
    public static FixSession initiate(String host, int port, Config config, Listener listener)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        FixSession session = new FixSession(socket, config, listener, false);
        session.readerThread.start();
        session.heartbeatThread.start();
        session.send(FixMessage.builder(FixMessage.LOGON)
                .field(FixMessage.ENCRYPT_METHOD, "0")
                .field(FixMessage.HEART_BT_INT, config.heartbeatSeconds()));
        session.awaitEstablished();
        return session;
    }

    /** Accepts one connection, awaits the peer's Logon, replies, and returns established. */
    public static FixSession accept(ServerSocket server, Config config, Listener listener)
            throws IOException {
        FixSession session = new FixSession(server.accept(), config, listener, true);
        session.readerThread.start();
        session.heartbeatThread.start();
        session.awaitEstablished();
        return session;
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
        FixMessage.Builder b = FixMessage.builder(FixMessage.NEW_ORDER_SINGLE)
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, symbol)
                .field(FixMessage.SIDE, side == Side.BUY ? '1' : '2')
                .field(FixMessage.TRANSACT_TIME, UTC_TIMESTAMP.format(Instant.now()))
                .field(FixMessage.ORDER_QTY, quantity);
        if (Double.isNaN(limitPrice)) {
            b.field(FixMessage.ORD_TYPE, NewOrderSingle.ORD_TYPE_MARKET);
        } else {
            b.field(FixMessage.ORD_TYPE, NewOrderSingle.ORD_TYPE_LIMIT)
                    .field(FixMessage.PRICE, limitPrice);
        }
        b.field(FixMessage.TIME_IN_FORCE, timeInForce);
        send(b);
        return clOrdId;
    }

    /** Venue side: sends an execution report for a received order. */
    public void sendExecutionReport(ExecutionReport report) {
        requireEstablished();
        send(report.toBuilder()
                .field(FixMessage.TRANSACT_TIME, UTC_TIMESTAMP.format(Instant.now())));
    }

    /** Initiates the Logout handshake and closes the session. */
    public void logout() {
        if (!established) {
            close();
            return;
        }
        logoutSent = true;
        send(FixMessage.builder(FixMessage.LOGOUT));
        try {
            logoutLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close();
    }

    public boolean isEstablished() {
        return established;
    }

    /** Next incoming sequence number this session expects (for diagnostics/tests). */
    public long expectedIncomingSeqNum() {
        return expectedIncomingSeq;
    }

    @Override
    public void close() {
        running = false;
        established = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    // ------------------------------------------------------------------
    // Session machinery
    // ------------------------------------------------------------------

    void send(FixMessage.Builder builder) {
        try {
            synchronized (writeLock) {
                byte[] bytes = builder.encode(config.senderCompId(), config.targetCompId(),
                        outgoingSeq++, UTC_TIMESTAMP.format(Instant.now()));
                out.write(bytes);
                out.flush();
                lastSentNanos = System.nanoTime();
            }
        } catch (IOException e) {
            disconnect("send failed: " + e.getMessage());
        }
    }

    /** Test hook: skips outgoing sequence numbers to provoke a gap on the peer. */
    void forceOutgoingSeq(long seqNum) {
        synchronized (writeLock) {
            outgoingSeq = seqNum;
        }
    }

    private void readerLoop() {
        byte[] chunk = new byte[4096];
        try {
            while (running) {
                int n = in.read(chunk);
                if (n < 0) {
                    disconnect("peer closed connection");
                    return;
                }
                lastReceivedNanos = System.nanoTime();
                testRequestPending = false;
                decoder.feed(chunk, 0, n);
                FixMessage message;
                while ((message = decoder.poll()) != null) {
                    handle(message);
                }
            }
        } catch (IOException e) {
            if (running) {
                disconnect("read failed: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            disconnect("protocol error: " + e.getMessage());
        }
    }

    private void handle(FixMessage m) {
        long seq = m.getLong(FixMessage.MSG_SEQ_NUM);
        if (seq > expectedIncomingSeq) {
            listener.onSequenceGap(expectedIncomingSeq, seq);
        }
        if (seq >= expectedIncomingSeq) {
            expectedIncomingSeq = seq + 1;
        }
        switch (m.msgType()) {
            case FixMessage.LOGON -> {
                if (!established) {
                    if (acceptor) {
                        send(FixMessage.builder(FixMessage.LOGON)
                                .field(FixMessage.ENCRYPT_METHOD, "0")
                                .field(FixMessage.HEART_BT_INT, config.heartbeatSeconds()));
                    }
                    established = true;
                    logonLatch.countDown();
                    listener.onLogon(this);
                }
            }
            case FixMessage.HEARTBEAT -> listener.onMessage(this, m);
            case FixMessage.TEST_REQUEST -> {
                FixMessage.Builder reply = FixMessage.builder(FixMessage.HEARTBEAT);
                if (m.has(FixMessage.TEST_REQ_ID)) {
                    reply.field(FixMessage.TEST_REQ_ID, m.getString(FixMessage.TEST_REQ_ID));
                }
                send(reply);
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
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            case FixMessage.EXECUTION_REPORT ->
                    listener.onExecutionReport(this, ExecutionReport.fromMessage(m));
            case FixMessage.NEW_ORDER_SINGLE ->
                    listener.onNewOrderSingle(this, NewOrderSingle.fromMessage(m));
            default -> listener.onMessage(this, m);
        }
    }

    private void heartbeatLoop() {
        long intervalNanos = config.heartbeatSeconds() * 1_000_000_000L;
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!established || !running) {
                continue;
            }
            long now = System.nanoTime();
            if (now - lastSentNanos >= intervalNanos) {
                send(FixMessage.builder(FixMessage.HEARTBEAT));
            }
            long silence = now - lastReceivedNanos;
            if (silence >= intervalNanos * 3 / 2 && !testRequestPending) {
                testRequestPending = true;
                send(FixMessage.builder(FixMessage.TEST_REQUEST)
                        .field(FixMessage.TEST_REQ_ID, "probe-" + now));
            }
            if (silence >= intervalNanos * 5 / 2) {
                disconnect("heartbeat timeout after " + silence / 1_000_000 + "ms of silence");
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
}

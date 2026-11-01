package com.quantfinlib.feed;

import com.quantfinlib.marketdata.HftMarketDataBus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Live market data over WebSocket into the {@link HftMarketDataBus}. Pure JDK
 * ({@code java.net.http} WebSocket client), pluggable {@link FeedParser} per
 * exchange ({@link BinanceTradeParser} is the reference), reconnection with
 * exponential backoff, and an optional subscription message for exchanges
 * that require one after connecting.
 *
 * <pre>{@code
 * HftMarketDataBus bus = new HftMarketDataBus();
 * TickCapture capture = TickCapture.attach(bus, Path.of("session.qflt"));
 * bus.start();
 * try (WebSocketFeed feed = new WebSocketFeed(
 *         BinanceTradeParser.streamUri("BTCUSDT", "ETHUSDT"),
 *         new BinanceTradeParser(), bus).withSymbols("BTCUSDT", "ETHUSDT")) {
 *     feed.start();
 * }
 * }</pre>
 *
 * <h2>Threading</h2>
 * The bus has a single-producer ring. Every connection attempt gets an epoch
 * number; callbacks from a socket of an older epoch are ignored, at most one
 * reconnect is scheduled per epoch (the JDK may report both {@code onError}
 * and {@code onClose}), the previous socket is aborted before the next one
 * opens, and publishing happens under a lock that re-checks the epoch, so two
 * sockets can never publish at the same time.
 *
 * <h2>Losses</h2>
 * When the bus ring is full, publishing is retried for a bounded budget
 * ({@link #withPublishRetry}); after that the tick is dropped, counted in
 * {@link #droppedTicks()} and the gap flag is raised. A reconnect also raises
 * it, since trades during the outage are lost. Consumers that need a complete
 * stream poll {@link #consumeGap()} and resynchronise.
 *
 * <h2>Symbols</h2>
 * With {@link #withSymbols} the symbols are registered on the bus up front
 * and trades for any other symbol are dropped and counted in
 * {@link #unknownSymbolDrops()}. Without it, symbols are registered on first
 * sight until the bus is full; later ones are dropped the same way instead
 * of throwing inside the WebSocket callback.
 *
 * <p>Published tick timestamps are the exchange's event time (epoch millis
 * converted to nanos).</p>
 */
public final class WebSocketFeed implements AutoCloseable {

    private final URI endpoint;
    private final FeedParser parser;
    private final HftMarketDataBus bus;
    private final HttpClient httpClient;
    private final Map<String, Integer> symbolIds = new ConcurrentHashMap<>();
    private final CountDownLatch firstConnection = new CountDownLatch(1);
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong tradesPublished = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong droppedTicks = new AtomicLong();
    private final AtomicLong unknownSymbolDrops = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicBoolean gap = new AtomicBoolean();
    private final Object publishLock = new Object();

    private String subscribeMessage;
    private int maxReconnectAttempts = 10;
    private long initialBackoffMillis = 500;
    private long publishRetryNanos = 1_000_000L;
    private boolean fixedSymbols;
    private volatile boolean userClosed;
    private volatile boolean connected;
    private volatile WebSocket webSocket;
    private volatile CompletableFuture<Void> closeAck = new CompletableFuture<>();
    private volatile String lastDisconnectReason;

    public WebSocketFeed(URI endpoint, FeedParser parser, HftMarketDataBus bus) {
        this.endpoint = endpoint;
        this.parser = parser;
        this.bus = bus;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Text frame sent right after connecting (exchanges with subscribe protocols). */
    public WebSocketFeed withSubscribeMessage(String message) {
        this.subscribeMessage = message;
        return this;
    }

    public WebSocketFeed withReconnect(int maxAttempts, long initialBackoffMillis) {
        this.maxReconnectAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        return this;
    }

    /**
     * Registers these symbols on the bus now (before {@link #start()}) and
     * drops trades for any other symbol.
     */
    public WebSocketFeed withSymbols(String... symbols) {
        for (String s : symbols) {
            int id;
            try {
                id = bus.symbolId(s);
            } catch (IllegalArgumentException unregistered) {
                id = bus.registerSymbol(s);
            }
            symbolIds.put(s, id);
        }
        this.fixedSymbols = true;
        return this;
    }

    /** How long a publish into a full ring is retried before the tick is dropped. */
    public WebSocketFeed withPublishRetry(Duration budget) {
        this.publishRetryNanos = Math.max(0, budget.toNanos());
        return this;
    }

    /** Connects and blocks until the first connection is established. */
    public void start() throws IOException {
        start(Duration.ofSeconds(15));
    }

    public void start(Duration timeout) throws IOException {
        connect(epoch.get());
        try {
            if (!firstConnection.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                close();
                throw new IOException("WebSocket connect to " + endpoint + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("interrupted while connecting", e);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public long messagesReceived() {
        return messagesReceived.get();
    }

    public long tradesPublished() {
        return tradesPublished.get();
    }

    public long reconnectCount() {
        return reconnects.get();
    }

    /** Ticks dropped because the bus ring stayed full beyond the retry budget. */
    public long droppedTicks() {
        return droppedTicks.get();
    }

    /** Trades dropped because their symbol was not registered or the bus was full of symbols. */
    public long unknownSymbolDrops() {
        return unknownSymbolDrops.get();
    }

    /** Returns and clears the gap flag (raised by a dropped tick or a reconnect). */
    public boolean consumeGap() {
        return gap.getAndSet(false);
    }

    /** Reason of the last disconnect, or null. */
    public String lastDisconnectReason() {
        return lastDisconnectReason;
    }

    /** Current connection epoch (increments on every reconnect). */
    long currentEpoch() {
        return epoch.get();
    }

    /**
     * Sends a close frame and waits up to two seconds for the server's close
     * frame (or the send failing) before aborting the socket.
     */
    @Override
    public void close() {
        userClosed = true;
        connected = false;
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        try {
            CompletableFuture<WebSocket> sent = ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                    .toCompletableFuture();
            CompletableFuture.anyOf(closeAck, sent.thenCompose(w -> closeAck))
                    .get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // timed out or the send failed: abort below
        } finally {
            ws.abort();
        }
    }

    // ------------------------------------------------------------------

    private void connect(long connectEpoch) {
        closeAck = new CompletableFuture<>();
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(endpoint, new FeedListener(connectEpoch))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        onDisconnect(connectEpoch, "connect failed: " + error.getMessage());
                    } else if (connectEpoch != epoch.get()) {
                        ws.abort();   // superseded while connecting
                    } else {
                        // onOpen normally set it already; close() owns teardown
                        webSocket = ws;
                    }
                });
    }

    /**
     * Disconnect signal from the socket of {@code fromEpoch}. Only the
     * current epoch can trigger a reconnect, and only once.
     */
    void onDisconnect(long fromEpoch, String reason) {
        if (fromEpoch != epoch.get()) {
            return;
        }
        connected = false;
        lastDisconnectReason = reason;
        if (userClosed || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        int currentAttempt = attempt.incrementAndGet();
        if (currentAttempt > maxReconnectAttempts) {
            return;   // gave up; reconnecting stays set, isConnected() is false
        }
        long backoff = Math.min(initialBackoffMillis << Math.min(currentAttempt - 1, 6), 30_000);
        Thread t = new Thread(() -> {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoff));
            if (userClosed) {
                return;
            }
            WebSocket old = webSocket;
            long next;
            synchronized (publishLock) {
                next = epoch.incrementAndGet();   // the old socket can no longer publish
            }
            if (old != null) {
                old.abort();
            }
            reconnects.incrementAndGet();
            gap.set(true);   // trades during the outage were missed
            reconnecting.set(false);
            connect(next);
        }, "ws-feed-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void handleMessage(long fromEpoch, String message) {
        messagesReceived.incrementAndGet();
        FeedTrade trade = parser.parseTrade(message);
        if (trade == null) {
            return;
        }
        Integer id = symbolIds.get(trade.symbol());
        if (id == null) {
            id = registerOnFirstSight(trade.symbol());
            if (id == null) {
                unknownSymbolDrops.incrementAndGet();
                return;
            }
        }
        long ts = trade.timestampMillis() * 1_000_000L;
        synchronized (publishLock) {
            if (fromEpoch != epoch.get()) {
                return;   // a superseded socket
            }
            if (!publishWithRetry(id, trade.price(), trade.size(), ts)) {
                droppedTicks.incrementAndGet();
                gap.set(true);
                return;
            }
        }
        tradesPublished.incrementAndGet();
    }

    private Integer registerOnFirstSight(String symbol) {
        if (fixedSymbols) {
            return null;
        }
        try {
            int id = bus.registerSymbol(symbol);
            symbolIds.put(symbol, id);
            return id;
        } catch (IllegalStateException full) {
            return null;
        }
    }

    private boolean publishWithRetry(int id, double price, double size, long ts) {
        if (bus.publish(id, price, size, ts)) {
            return true;
        }
        long deadline = System.nanoTime() + publishRetryNanos;
        int spins = 0;
        while (System.nanoTime() - deadline < 0) {
            if (++spins < 100) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(10_000);
            }
            if (bus.publish(id, price, size, ts)) {
                return true;
            }
        }
        return false;
    }

    private final class FeedListener implements WebSocket.Listener {

        private final long listenerEpoch;
        private final StringBuilder buffer = new StringBuilder();

        FeedListener(long listenerEpoch) {
            this.listenerEpoch = listenerEpoch;
        }

        private boolean stale() {
            return listenerEpoch != epoch.get();
        }

        @Override
        public void onOpen(WebSocket ws) {
            if (stale() || userClosed) {
                ws.abort();
                return;
            }
            webSocket = ws;
            connected = true;
            attempt.set(0);   // healthy connection resets the backoff ladder
            if (subscribeMessage != null) {
                ws.sendText(subscribeMessage, true);
            }
            firstConnection.countDown();
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (stale()) {
                ws.abort();
                return null;
            }
            buffer.append(data);
            if (last) {
                handleMessage(listenerEpoch, buffer.toString());
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!stale()) {
                closeAck.complete(null);
            }
            onDisconnect(listenerEpoch, "closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!stale()) {
                closeAck.complete(null);
            }
            onDisconnect(listenerEpoch, "error: " + error);
        }
    }
}

package com.quantfinlib.feed;

import com.quantfinlib.marketdata.HftMarketDataBus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live market data over WebSocket into the {@link HftMarketDataBus} — the
 * last mile that lets the capture/replay, paper-trading and analytics stack
 * run on <i>real</i> ticks. Pure JDK ({@code java.net.http} WebSocket
 * client), pluggable {@link FeedParser} per exchange
 * ({@link BinanceTradeParser} ships as the reference), automatic reconnection
 * with exponential backoff, and an optional subscription message for
 * exchanges that require one after connecting.
 *
 * <p>Wire once, and everything downstream just works:</p>
 * <pre>{@code
 * HftMarketDataBus bus = new HftMarketDataBus();
 * TickCapture capture = TickCapture.attach(bus, Path.of("session.qflt"));
 * bus.start();
 * try (WebSocketFeed feed = new WebSocketFeed(
 *         BinanceTradeParser.streamUri("BTCUSDT", "ETHUSDT"),
 *         new BinanceTradeParser(), bus)) {
 *     feed.start();
 *     // strategies subscribe to the bus; the session is being recorded
 * }
 * }</pre>
 *
 * <p>Threading: the JDK invokes WebSocket listener methods sequentially per
 * connection, and reconnect epochs never overlap, so the bus's
 * single-producer contract holds. Published tick timestamps are the
 * exchange's event time (millis → nanos), so recorded sessions replay with
 * true market pacing.</p>
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

    private String subscribeMessage;
    private int maxReconnectAttempts = 10;
    private long initialBackoffMillis = 500;
    private volatile boolean userClosed;
    private volatile boolean connected;
    private volatile WebSocket webSocket;
    private volatile int attempt;

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

    /** Connects and blocks until the first connection is established. */
    public void start() throws IOException {
        start(Duration.ofSeconds(15));
    }

    public void start(Duration timeout) throws IOException {
        connect();
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

    @Override
    public void close() {
        userClosed = true;
        connected = false;
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                        .orTimeout(2, TimeUnit.SECONDS);
            } catch (RuntimeException ignored) {
                // best effort
            }
            ws.abort();
        }
    }

    // ------------------------------------------------------------------

    private void connect() {
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(endpoint, new FeedListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        scheduleReconnect("connect failed: " + error.getMessage());
                    } else {
                        webSocket = ws;
                    }
                });
    }

    private void scheduleReconnect(String reason) {
        connected = false;
        if (userClosed) {
            return;
        }
        int currentAttempt = ++attempt;
        if (currentAttempt > maxReconnectAttempts) {
            return;   // gave up; isConnected()/reconnectCount() expose the state
        }
        long backoff = Math.min(initialBackoffMillis << Math.min(currentAttempt - 1, 6), 30_000);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!userClosed) {
                reconnects.incrementAndGet();
                connect();
            }
        }, "ws-feed-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void handleMessage(String message) {
        messagesReceived.incrementAndGet();
        FeedTrade trade = parser.parseTrade(message);
        if (trade == null) {
            return;
        }
        Integer id = symbolIds.computeIfAbsent(trade.symbol(), bus::registerSymbol);
        bus.publish(id, trade.price(), trade.size(), trade.timestampMillis() * 1_000_000L);
        tradesPublished.incrementAndGet();
    }

    private final class FeedListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            attempt = 0;   // healthy connection resets the backoff ladder
            if (subscribeMessage != null) {
                ws.sendText(subscribeMessage, true);
            }
            firstConnection.countDown();
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect("closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            scheduleReconnect("error: " + error);
        }
    }
}

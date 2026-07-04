package com.quantfinlib.feed;

import com.quantfinlib.data.TickCapture;
import com.quantfinlib.data.TickFileReader;
import com.quantfinlib.marketdata.HftMarketDataBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFeedTest {

    private static final String BTC_TRADE =
            "{\"e\":\"trade\",\"E\":1751600000100,\"s\":\"BTCUSDT\",\"t\":42,"
                    + "\"p\":\"50000.50\",\"q\":\"0.10\",\"T\":1751600000099,\"m\":true}";
    private static final String ETH_TRADE_COMBINED =
            "{\"stream\":\"ethusdt@trade\",\"data\":{\"e\":\"trade\",\"s\":\"ETHUSDT\","
                    + "\"p\":\"3000.25\",\"q\":\"1.5\",\"T\":1751600000200}}";

    // ---- Parser --------------------------------------------------------

    @Test
    void parsesRawAndCombinedStreamPayloads() {
        BinanceTradeParser parser = new BinanceTradeParser();

        FeedTrade btc = parser.parseTrade(BTC_TRADE);
        assertEquals("BTCUSDT", btc.symbol());
        assertEquals(50000.50, btc.price(), 0.0);
        assertEquals(0.10, btc.size(), 0.0);
        assertEquals(1751600000099L, btc.timestampMillis());

        FeedTrade eth = parser.parseTrade(ETH_TRADE_COMBINED);
        assertEquals("ETHUSDT", eth.symbol());
        assertEquals(3000.25, eth.price(), 0.0);
    }

    @Test
    void nonTradeAndMalformedMessagesReturnNull() {
        BinanceTradeParser parser = new BinanceTradeParser();
        assertNull(parser.parseTrade("{\"result\":null,\"id\":1}"));           // subscribe ack
        assertNull(parser.parseTrade("{\"e\":\"depthUpdate\",\"s\":\"X\"}"));  // other event
        assertNull(parser.parseTrade("not json at all"));
        assertNull(parser.parseTrade("{\"e\":\"trade\",\"s\":\"X\",\"p\":\"abc\"}"));
    }

    @Test
    void streamUriCoversAllSymbols() {
        URI uri = BinanceTradeParser.streamUri("BTCUSDT", "ETHUSDT");
        assertEquals("wss://stream.binance.com:9443/stream?streams=btcusdt@trade/ethusdt@trade",
                uri.toString());
    }

    // ---- End to end over loopback ------------------------------------------

    @Test
    void tradesFlowIntoTheBusAndAreCaptured(@TempDir Path dir) throws Exception {
        Path session = dir.resolve("live.qflt");
        try (TestWebSocketServer server = new TestWebSocketServer();
             HftMarketDataBus bus = new HftMarketDataBus(1 << 12, 16, false)) {
            TickCapture capture = TickCapture.attach(bus, session);
            bus.start();

            try (WebSocketFeed feed = new WebSocketFeed(
                    URI.create("ws://127.0.0.1:" + server.port() + "/ws"),
                    new BinanceTradeParser(), bus)) {
                feed.start();
                server.awaitConnections(1, 5_000);
                assertTrue(feed.isConnected());

                server.sendText(BTC_TRADE);
                server.sendText(ETH_TRADE_COMBINED);
                server.sendText("{\"result\":null,\"id\":1}");   // ignored noise

                awaitTrue(() -> feed.tradesPublished() == 2, "trades not published");
                awaitTrue(() -> bus.processedCount() == 2, "bus did not dispatch");
                assertEquals(3, feed.messagesReceived());
                assertEquals(50000.50, bus.latestPrice("BTCUSDT"), 0.0);
                assertEquals(3000.25, bus.latestPrice("ETHUSDT"), 0.0);
            }
            bus.stop();   // drain the ring into the capture
            capture.close();
        }

        // The recorded session replays exactly, with exchange event times.
        List<String> symbols = new ArrayList<>();
        long[] count = {0};
        long[] firstTs = {0};
        TickFileReader.replay(session, new TickFileReader.ReplayHandler() {
            @Override
            public void onSymbol(int symbolId, String symbol) {
                symbols.add(symbol);
            }

            @Override
            public void onTick(int symbolId, double price, double size, long timestampNanos) {
                if (count[0] == 0) {
                    firstTs[0] = timestampNanos;
                }
                count[0]++;
            }
        });
        assertEquals(2, count[0]);
        assertTrue(symbols.contains("BTCUSDT") && symbols.contains("ETHUSDT"));
        assertEquals(1751600000099L * 1_000_000L, firstTs[0]);
    }

    @Test
    void subscribeMessageIsSentOnOpen() throws Exception {
        try (TestWebSocketServer server = new TestWebSocketServer();
             HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 4, false)) {
            bus.start();
            try (WebSocketFeed feed = new WebSocketFeed(
                    URI.create("ws://127.0.0.1:" + server.port() + "/"),
                    new BinanceTradeParser(), bus)
                    .withSubscribeMessage("{\"op\":\"subscribe\",\"args\":[\"trades\"]}")) {
                feed.start();
                awaitTrue(() -> !server.receivedTexts.isEmpty(), "subscribe not received");
                assertEquals("{\"op\":\"subscribe\",\"args\":[\"trades\"]}",
                        server.receivedTexts.getFirst());
            }
        }
    }

    @Test
    void reconnectsAfterAbruptDisconnectAndResumes(@TempDir Path dir) throws Exception {
        try (TestWebSocketServer server = new TestWebSocketServer();
             HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 4, false)) {
            bus.start();
            try (WebSocketFeed feed = new WebSocketFeed(
                    URI.create("ws://127.0.0.1:" + server.port() + "/"),
                    new BinanceTradeParser(), bus)
                    .withReconnect(10, 100)) {
                feed.start();
                server.awaitConnections(1, 5_000);
                server.sendText(BTC_TRADE);
                awaitTrue(() -> feed.tradesPublished() == 1, "first trade lost");

                server.dropConnection();   // no close frame: hard failure
                server.awaitConnections(2, 10_000);
                awaitTrue(feed::isConnected, "did not reconnect");
                assertTrue(feed.reconnectCount() >= 1);

                server.sendText(ETH_TRADE_COMBINED);
                awaitTrue(() -> feed.tradesPublished() == 2, "post-reconnect trade lost");
                awaitTrue(() -> bus.processedCount() == 2, "bus did not dispatch");
                assertEquals(3000.25, bus.latestPrice("ETHUSDT"), 0.0);
            }
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(10);
        }
    }
}

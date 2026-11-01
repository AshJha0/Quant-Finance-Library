package com.quantfinlib.feed;

import java.net.URI;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Parser for Binance trade-stream JSON — both the raw single-stream form
 * ({@code {"e":"trade","s":"BTCUSDT","p":"50000.1","q":"0.05","T":169...}})
 * and the combined-stream wrapper ({@code {"stream":"btcusdt@trade","data":{...}}}).
 * Minimal flat-field extraction: no JSON library, no allocation beyond the
 * result.
 */
public final class BinanceTradeParser implements FeedParser {
    /** Creates an instance; the explicit form of the default constructor. */
    public BinanceTradeParser() {
    }


    /** Public Binance combined-stream endpoint for the given symbols' trades. */
    public static URI streamUri(String... symbols) {
        StringJoiner streams = new StringJoiner("/");
        for (String symbol : symbols) {
            streams.add(symbol.toLowerCase(Locale.ROOT) + "@trade");
        }
        return URI.create("wss://stream.binance.com:9443/stream?streams=" + streams);
    }

    @Override
    public FeedTrade parseTrade(String message) {
        try {
            String payload = message;
            int data = message.indexOf("\"data\":");
            if (data >= 0) {
                payload = message.substring(data + 7);
            }
            if (!payload.contains("\"e\":\"trade\"")) {
                return null;
            }
            String symbol = extractString(payload, "s");
            String priceStr = extractString(payload, "p");
            String sizeStr = extractString(payload, "q");
            long time = extractLong(payload, "T");
            if (symbol == null || priceStr == null || sizeStr == null || time < 0) {
                return null;
            }
            double price = Double.parseDouble(priceStr);
            double size = Double.parseDouble(sizeStr);
            if (price <= 0 || size < 0) {
                return null;
            }
            return new FeedTrade(symbol, price, size, time);
        } catch (RuntimeException e) {
            return null;   // malformed message: skip, never kill the feed
        }
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    /**
     * Returns the field's numeric value, or {@code -1} if the field is
     * missing or its value is not a parseable non-negative integer — the
     * caller treats {@code -1} as "malformed, reject" rather than defaulting
     * to a spurious timestamp of 0 (epoch), which would look like a valid,
     * stale tick to downstream consumers.
     */
    private static long extractLong(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            return -1;
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;   // no digits found: missing/unparseable value
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

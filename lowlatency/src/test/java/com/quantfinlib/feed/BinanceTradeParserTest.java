package com.quantfinlib.feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BinanceTradeParserTest {

    private final BinanceTradeParser parser = new BinanceTradeParser();

    @Test
    void parsesWellFormedTradeMessage() {
        String message = "{\"e\":\"trade\",\"s\":\"BTCUSDT\",\"p\":\"50000.1\",\"q\":\"0.05\","
                + "\"T\":1699999999999}";
        FeedTrade trade = parser.parseTrade(message);
        assertEquals("BTCUSDT", trade.symbol());
        assertEquals(50000.1, trade.price(), 0.0);
        assertEquals(0.05, trade.size(), 0.0);
        assertEquals(1699999999999L, trade.timestampMillis());
    }

    @Test
    void missingTradeTimeFieldIsRejectedNotDefaultedToEpochZero() {
        // BUG PIN: a missing "T" field must reject the message (same path as
        // any other malformed message), not silently publish timestamp 0 —
        // which downstream consumers would mistake for a real, stale tick.
        String message = "{\"e\":\"trade\",\"s\":\"BTCUSDT\",\"p\":\"50000.1\",\"q\":\"0.05\"}";
        assertNull(parser.parseTrade(message));
    }

    @Test
    void unparseableTradeTimeFieldIsRejected() {
        // "T" present but not a plain non-negative integer (e.g. null-valued
        // or non-numeric) must also be treated as malformed.
        String message = "{\"e\":\"trade\",\"s\":\"BTCUSDT\",\"p\":\"50000.1\",\"q\":\"0.05\","
                + "\"T\":null}";
        assertNull(parser.parseTrade(message));
    }

    @Test
    void combinedStreamWrapperMissingTradeTimeIsRejected() {
        String message = "{\"stream\":\"btcusdt@trade\",\"data\":{\"e\":\"trade\","
                + "\"s\":\"BTCUSDT\",\"p\":\"50000.1\",\"q\":\"0.05\"}}";
        assertNull(parser.parseTrade(message));
    }
}

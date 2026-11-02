package com.quantfinlib.marketdata;

import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Truncation guard and the C/H/S/Q/B messages (F1, F2). */
class ItchFeedStateTest {

    private static final int LOCATE = 42;
    private static final long STOCK = ItchCodec.packStock("AAPL");

    private static L3BookBuilder book() {
        return new L3BookBuilder(LOCATE, 1_700_000, 1_800_000, 1 << 10);
    }

    @Test
    void wireLengthsMatchTheItch50Specification() {
        // Nasdaq TotalView-ITCH 5.0, section 1: message lengths in bytes.
        assertEquals(12, ItchCodec.length((byte) 'S'));
        assertEquals(39, ItchCodec.length((byte) 'R'));
        assertEquals(25, ItchCodec.length((byte) 'H'));
        assertEquals(36, ItchCodec.length((byte) 'C'));
        assertEquals(40, ItchCodec.length((byte) 'Q'));
        assertEquals(19, ItchCodec.length((byte) 'B'));
        assertEquals(50, ItchCodec.length((byte) 'I'));
    }

    @Test
    void truncatedMessagesAreSkippedAndCountedNeverReadPastTheBuffer() {
        L3BookBuilder b = book();
        byte[] full = new byte[36];
        ItchCodec.encodeAdd(full, 0, LOCATE, 1L, 7L, ItchCodec.BUY, 100, STOCK, 1_750_000);
        for (int cut = 1; cut < 36; cut++) {
            byte[] shortDatagram = java.util.Arrays.copyOf(full, cut);
            assertEquals(0, b.onMessage(shortDatagram, 0), "cut " + cut);
        }
        assertEquals(35, b.truncatedCount());
        assertEquals(0, b.restingOrders());
        // A split read inside a larger buffer: the available count is honoured.
        byte[] big = new byte[100];
        System.arraycopy(full, 0, big, 0, 36);
        assertEquals(0, b.onMessage(big, 0, 20));
        assertEquals(36, b.onMessage(big, 0, 36));
        assertEquals(1, b.restingOrders());
    }

    @Test
    void executionWithPriceRemovesLiquidityAndRecordsThePrint() {
        L3BookBuilder b = book();
        byte[] buf = new byte[64];
        ItchCodec.encodeAdd(buf, 0, LOCATE, 1L, 7L, ItchCodec.SELL, 300, STOCK, 1_750_000);
        b.onMessage(buf, 0);
        int n = ItchCodec.encodeExecutedWithPrice(buf, 0, LOCATE, 2L, 7L, 100, 99L, true, 1_749_900);
        assertEquals(36, n);
        assertEquals(36, b.onMessage(buf, 0));
        assertEquals(200, b.bestAskSize(), "phantom liquidity would leave 300");
        assertEquals(1_749_900, b.lastTradeTick());
        ItchCodec.View v = new ItchCodec.View().wrap(buf, 0);
        assertEquals(1_749_900, v.executionPriceTick());
        assertEquals(99L, v.matchNumber());
    }

    @Test
    void tradingActionHaltsAndResumes() {
        L3BookBuilder b = book();
        byte[] buf = new byte[25];
        assertTrue(b.isTrading());
        ItchCodec.encodeTradingAction(buf, 0, LOCATE, 1L, STOCK, ItchCodec.STATE_HALTED, 0);
        assertEquals(25, b.onMessage(buf, 0));
        assertFalse(b.isTrading());
        assertEquals(ItchCodec.STATE_HALTED, b.tradingState());
        ItchCodec.encodeTradingAction(buf, 0, LOCATE, 2L, STOCK, ItchCodec.STATE_TRADING, 0);
        b.onMessage(buf, 0);
        assertTrue(b.isTrading());
        // Another stock's halt does not touch this book.
        ItchCodec.encodeTradingAction(buf, 0, 99, 3L, STOCK, ItchCodec.STATE_HALTED, 0);
        assertEquals(0, b.onMessage(buf, 0));
        assertTrue(b.isTrading());
    }

    @Test
    void systemEventsAreMarketWideCrossesAndBrokenTradesAreRecorded() {
        L3BookBuilder b = book();
        byte[] buf = new byte[64];
        ItchCodec.encodeSystemEvent(buf, 0, 1L, ItchCodec.EVENT_START_OF_MARKET_HOURS);
        assertEquals(12, b.onMessage(buf, 0));
        assertEquals(ItchCodec.EVENT_START_OF_MARKET_HOURS, b.lastSystemEvent());

        ItchCodec.encodeCrossTrade(buf, 0, LOCATE, 2L, 5_000, STOCK, 1_760_000, 11L, (byte) 'O');
        assertEquals(40, b.onMessage(buf, 0));
        assertEquals(1, b.crossCount());
        assertEquals(1_760_000, b.lastTradeTick());
        ItchCodec.View v = new ItchCodec.View().wrap(buf, 0);
        assertEquals(5_000, v.crossShares());
        assertEquals('O', v.crossType());

        ItchCodec.encodeBrokenTrade(buf, 0, LOCATE, 3L, 11L);
        assertEquals(19, b.onMessage(buf, 0));
        assertEquals(1, b.brokenTradeCount());
    }

    @Test
    void clearEmptiesTheBookForASnapshotRebuild() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        b.onAdd(2, Side.SELL, 100, 1_750_100);
        b.clear();
        assertEquals(0, b.restingOrders());
        assertEquals(0, b.bestBidSize());
        assertTrue(b.onAdd(1, Side.BUY, 50, 1_750_000), "refs are free again after clear");
        assertEquals(50, b.bestBidSize());
    }
}

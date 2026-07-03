package com.quantfinlib.orderbook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    @Test
    void restingOrdersFormTopOfBook() {
        OrderBook book = new OrderBook("EURUSD");
        book.submitLimit(Side.BUY, 1.0848, 1_000_000, 1);
        book.submitLimit(Side.BUY, 1.0849, 500_000, 2);
        book.submitLimit(Side.SELL, 1.0851, 700_000, 3);
        book.submitLimit(Side.SELL, 1.0852, 900_000, 4);

        assertEquals(1.0849, book.bestBid(), 1e-12);
        assertEquals(1.0851, book.bestAsk(), 1e-12);
        assertEquals(500_000, book.bestBidSize());
        assertEquals(1.0850, book.mid(), 1e-12);
        assertEquals(0.0002, book.spread(), 1e-12);
        assertEquals(1_500_000, book.depth(Side.BUY, 10));
    }

    @Test
    void matchingRespectsPriceTimePriority() {
        OrderBook book = new OrderBook("X");
        List<Long> makerFills = new ArrayList<>();
        book.addTradeListener((maker, taker, price, qty, ts) -> makerFills.add(maker));

        long first = book.submitLimit(Side.SELL, 100.0, 10, 1);   // same price, earlier
        long second = book.submitLimit(Side.SELL, 100.0, 10, 2);  // same price, later
        long betterLater = book.submitLimit(Side.SELL, 99.0, 5, 3); // better price, latest

        book.submitMarket(Side.BUY, 20, 4);
        // Best price first (99.0), then time priority at 100.0.
        assertEquals(List.of(betterLater, first, second), makerFills);
        // second is partially filled: 20 - 5 - 10 = 5 filled of its 10.
        assertEquals(5, book.order(second).quantity());
    }

    @Test
    void crossingLimitOrderMatchesThenRests() {
        OrderBook book = new OrderBook("X");
        book.submitLimit(Side.SELL, 100.0, 10, 1);
        long id = book.submitLimit(Side.BUY, 100.5, 25, 2);   // crosses, remainder rests

        assertEquals(15, book.order(id).quantity());
        assertEquals(100.5, book.bestBid(), 1e-12);
        assertTrue(Double.isNaN(book.bestAsk()));
    }

    @Test
    void cancelRemovesQuantityAndUpdatesCounters() {
        OrderBook book = new OrderBook("X");
        long a = book.submitLimit(Side.BUY, 99.0, 100, 1);
        long b = book.submitLimit(Side.BUY, 99.0, 200, 2);

        assertEquals(0, book.qtyAhead(a));
        assertEquals(100, book.qtyAhead(b));

        assertTrue(book.cancel(a));
        assertFalse(book.cancel(a));            // idempotent
        assertEquals(0, book.qtyAhead(b));
        assertEquals(200, book.bestBidSize());
        assertEquals(1, book.cancelCount());
    }

    @Test
    void orderToTradeRatioTracksMessages() {
        OrderBook book = new OrderBook("X");
        book.submitLimit(Side.SELL, 100.0, 10, 1);
        long c = book.submitLimit(Side.SELL, 101.0, 10, 2);
        book.cancel(c);
        book.submitMarket(Side.BUY, 10, 3);     // 1 trade
        // messages = 3 orders + 1 cancel = 4; trades = 1.
        assertEquals(4.0, book.orderToTradeRatio(), 1e-12);
    }

    @Test
    void sweepComputesVwapAndImpact() {
        OrderBook book = new OrderBook("X");
        book.submitLimit(Side.BUY, 99.9, 100, 1);              // for a two-sided mid
        book.submitLimit(Side.SELL, 100.1, 10, 2);
        book.submitLimit(Side.SELL, 100.3, 10, 3);

        BookAnalytics.SweepResult sweep = BookAnalytics.sweep(book, Side.BUY, 15);
        assertEquals(15, sweep.filledQty());
        // VWAP = (10*100.1 + 5*100.3) / 15
        assertEquals((10 * 100.1 + 5 * 100.3) / 15, sweep.avgPrice(), 1e-9);
        assertEquals(2, sweep.levelsConsumed());
        assertTrue(sweep.impactBps() > 0);

        // Book unchanged by simulation.
        assertEquals(20, book.depth(Side.SELL, 10));
    }

    @Test
    void micropriceLeansTowardHeavierSide() {
        // bid 99 x 90, ask 101 x 10 -> I = 0.9 -> micro = 0.9*101 + 0.1*99 = 100.8
        assertEquals(100.8, BookAnalytics.microprice(99, 101, 90, 10), 1e-12);

        OrderBook book = new OrderBook("X");
        book.submitLimit(Side.BUY, 99, 90, 1);
        book.submitLimit(Side.SELL, 101, 10, 2);
        assertEquals(100.8, BookAnalytics.microprice(book), 1e-12);
        assertEquals(0.8, BookAnalytics.imbalance(book, 5), 1e-12);
    }

    @Test
    void depthWithinBpsFiltersFarLevels() {
        OrderBook book = new OrderBook("X");
        book.submitLimit(Side.BUY, 100.0, 50, 1);
        book.submitLimit(Side.SELL, 100.02, 60, 2);   // ~1 bps from mid
        book.submitLimit(Side.SELL, 101.0, 500, 3);   // ~99 bps away

        assertEquals(60, BookAnalytics.depthWithinBps(book, Side.SELL, 5));
        assertEquals(560, BookAnalytics.depthWithinBps(book, Side.SELL, 200));
    }
}

package com.quantfinlib.orderbook;

import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cancel unlinks from the level FIFO in place: head, middle and tail, priority kept. */
class OrderBookCancelTest {

    @Test
    void cancelHeadMiddleAndTailKeepsTimePriority() {
        OrderBook book = new OrderBook("X");
        List<long[]> fills = new ArrayList<>();
        book.addTradeListener((maker, taker, px, q, ts) -> fills.add(new long[]{maker, q}));
        long a = book.submitLimit(Side.BUY, 10.0, 10, 1);
        long b = book.submitLimit(Side.BUY, 10.0, 20, 2);
        long c = book.submitLimit(Side.BUY, 10.0, 30, 3);
        long d = book.submitLimit(Side.BUY, 10.0, 40, 4);
        assertTrue(book.cancel(b));                      // middle
        assertEquals(10, book.qtyAhead(c));
        assertTrue(book.cancel(a));                      // head
        assertEquals(0, book.qtyAhead(c));
        assertEquals(30, book.qtyAhead(d));
        assertTrue(book.cancel(d));                      // tail
        assertFalse(book.cancel(d));
        assertEquals(30, book.bestBidSize());
        long e = book.submitLimit(Side.BUY, 10.0, 5, 5);
        assertEquals(30, book.qtyAhead(e), "new order joins behind c");
        book.submitLimit(Side.SELL, 10.0, 33, 6);
        assertEquals(2, fills.size());
        assertEquals(c, fills.get(0)[0]);
        assertEquals(30, fills.get(0)[1]);
        assertEquals(e, fills.get(1)[0]);
        assertEquals(3, fills.get(1)[1]);
        assertEquals(2, book.bestBidSize());
        assertTrue(book.cancel(e));
        assertTrue(Double.isNaN(book.bestBid()), "level removed once empty");
    }

    @Test
    void manyCancelsAtOneLevel() {
        OrderBook book = new OrderBook("X");
        int n = 50_000;
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = book.submitLimit(Side.SELL, 20.0, 1, i);
        }
        for (int i = n - 1; i >= 1; i -= 2) {
            assertTrue(book.cancel(ids[i]));
        }
        assertEquals(n / 2, book.bestAskSize());
        assertEquals(n / 2 - 1, book.qtyAhead(ids[n - 2]));
    }
}

package com.quantfinlib.orderbook;

import com.quantfinlib.core.Side;
import com.quantfinlib.orderbook.HftOrderBook.SelfTradePrevention;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-trade prevention, modify with priority rules, market-order
 * protection bands, trade sequence numbers and deferred trade delivery
 * (findings E1, E2), each checked against hand-worked book states.
 */
class HftOrderBookVenueControlsTest {

    private static HftOrderBook book() {
        return new HftOrderBook(9_000, 11_000, 1_024);
    }

    /** maker, taker, tick, qty, sequence. */
    private static List<long[]> record(HftOrderBook b) {
        List<long[]> trades = new ArrayList<>();
        b.tradeSink((seq, maker, taker, tick, qty, ts) ->
                trades.add(new long[]{maker, taker, tick, qty, seq}));
        return trades;
    }

    // ------------------------------------------------------------------
    // Self-trade prevention
    // ------------------------------------------------------------------

    @Test
    void cancelRestingRemovesOwnOrderAndKeepsMatching() {
        HftOrderBook b = book().selfTradePrevention(SelfTradePrevention.CANCEL_RESTING);
        List<long[]> trades = record(b);
        long own = b.submitLimit(Side.SELL, 10_000, 30, 7, 1);
        long other = b.submitLimit(Side.SELL, 10_000, 40, 8, 2);
        long taker = b.submitLimit(Side.BUY, 10_000, 50, 7, 3);
        // Own 30 cancelled without a trade; 40 traded with owner 8; 10 rests.
        assertEquals(1, trades.size());
        assertEquals(other, trades.get(0)[0]);
        assertEquals(40, trades.get(0)[3]);
        assertEquals(0, b.openQuantity(own));
        assertEquals(10, b.openQuantity(taker));
        assertEquals(1, b.selfTradePreventionCount());
        assertEquals(1, b.restingOrders());
    }

    @Test
    void cancelIncomingStopsTheTakerAndLeavesTheRestingOrder() {
        HftOrderBook b = book().selfTradePrevention(SelfTradePrevention.CANCEL_INCOMING);
        List<long[]> trades = record(b);
        long other = b.submitLimit(Side.SELL, 10_000, 20, 8, 1);
        long own = b.submitLimit(Side.SELL, 10_001, 30, 7, 2);
        long taker = b.submitLimit(Side.BUY, 10_001, 100, 7, 3);
        // Trades 20 with owner 8 at 10000, then meets its own order: stops,
        // the remaining 80 does not rest.
        assertEquals(1, trades.size());
        assertEquals(20, trades.get(0)[3]);
        assertEquals(30, b.openQuantity(own));
        assertEquals(0, b.openQuantity(taker));
        assertEquals(0, b.qtyAtTick(Side.BUY, 10_001));
        assertEquals(other, trades.get(0)[0]);
    }

    @Test
    void cancelBothRemovesBoth() {
        HftOrderBook b = book().selfTradePrevention(SelfTradePrevention.CANCEL_BOTH);
        List<long[]> trades = record(b);
        long own = b.submitLimit(Side.SELL, 10_000, 30, 7, 1);
        long taker = b.submitLimit(Side.BUY, 10_000, 50, 7, 2);
        assertTrue(trades.isEmpty());
        assertEquals(0, b.openQuantity(own));
        assertEquals(0, b.openQuantity(taker));
        assertEquals(0, b.restingOrders());
        assertEquals(Integer.MAX_VALUE, b.bestAskTick());
    }

    @Test
    void decrementReducesBothWithoutATrade() {
        HftOrderBook b = book().selfTradePrevention(SelfTradePrevention.DECREMENT);
        List<long[]> trades = record(b);
        long own = b.submitLimit(Side.SELL, 10_000, 30, 7, 1);
        long other = b.submitLimit(Side.SELL, 10_000, 40, 8, 2);
        long taker = b.submitLimit(Side.BUY, 10_000, 50, 7, 3);
        // 30 decremented away against own order, 20 traded against owner 8.
        assertEquals(1, trades.size());
        assertEquals(20, trades.get(0)[3]);
        assertEquals(0, b.openQuantity(own));
        assertEquals(20, b.openQuantity(other));
        assertEquals(0, b.openQuantity(taker));
        assertEquals(20, b.qtyAtTick(Side.SELL, 10_000));
    }

    @Test
    void ownerZeroAndPolicyNoneAlwaysTrade() {
        HftOrderBook b = book().selfTradePrevention(SelfTradePrevention.CANCEL_BOTH);
        List<long[]> trades = record(b);
        b.submitLimit(Side.SELL, 10_000, 10, 1);          // owner 0
        b.submitLimit(Side.BUY, 10_000, 10, 2);           // owner 0
        assertEquals(1, trades.size());
        HftOrderBook n = book();                           // policy NONE
        List<long[]> t2 = record(n);
        n.submitLimit(Side.SELL, 10_000, 10, 7, 1);
        n.submitLimit(Side.BUY, 10_000, 10, 7, 2);
        assertEquals(1, t2.size());
    }

    // ------------------------------------------------------------------
    // Modify
    // ------------------------------------------------------------------

    @Test
    void sizeDownKeepsPriorityPriceChangeLosesIt() {
        HftOrderBook b = book();
        List<long[]> trades = record(b);
        long first = b.submitLimit(Side.BUY, 10_000, 50, 1);
        long second = b.submitLimit(Side.BUY, 10_000, 50, 2);
        assertEquals(first, b.modify(first, 20, 10_000, 3));
        assertEquals(70, b.qtyAtTick(Side.BUY, 10_000));
        b.submitLimit(Side.SELL, 10_000, 20, 4);
        assertEquals(first, trades.get(0)[0], "size-down kept the head of the queue");

        long third = b.submitLimit(Side.BUY, 10_000, 10, 5);
        // Size-up loses priority: second goes to the back, behind third.
        assertEquals(second, b.modify(second, 60, 10_000, 6));
        b.submitLimit(Side.SELL, 10_000, 10, 7);
        assertEquals(third, trades.get(1)[0], "size-up went to the back");
        assertEquals(60, b.openQuantity(second));
    }

    @Test
    void repricingThroughTheSpreadTradesOnReentry() {
        HftOrderBook b = book();
        List<long[]> trades = record(b);
        long ask = b.submitLimit(Side.SELL, 10_005, 30, 1);
        long bid = b.submitLimit(Side.BUY, 10_000, 50, 2);
        assertEquals(bid, b.modify(bid, 50, 10_005, 3));
        assertEquals(1, trades.size());
        assertEquals(ask, trades.get(0)[0]);
        assertEquals(bid, trades.get(0)[1], "taker keeps its id across the modify");
        assertEquals(20, b.openQuantity(bid));
        assertEquals(10_005, b.bestBidTick());
    }

    @Test
    void modifyValidates() {
        HftOrderBook b = book();
        long id = b.submitLimit(Side.BUY, 10_000, 50, 1);
        assertEquals(HftOrderBook.REJECT_INVALID, b.modify(999, 10, 10_000, 2));
        assertEquals(HftOrderBook.REJECT_OUT_OF_BAND, b.modify(id, 10, 20_000, 3));
        assertEquals(50, b.openQuantity(id), "a rejected modify leaves the order alone");
        assertEquals(id, b.modify(id, 0, 10_000, 4));
        assertEquals(0, b.openQuantity(id));
    }

    // ------------------------------------------------------------------
    // Market protection band and trade sequence
    // ------------------------------------------------------------------

    @Test
    void marketOrderStopsAtTheProtectionBand() {
        HftOrderBook b = book().marketProtectionTicks(2);
        b.submitLimit(Side.SELL, 10_000, 10, 1);
        b.submitLimit(Side.SELL, 10_002, 10, 2);
        b.submitLimit(Side.SELL, 10_003, 10, 3);          // beyond best + 2
        assertEquals(20, b.submitMarket(Side.BUY, 100, 4));
        assertEquals(10_003, b.bestAskTick(), "the far level was protected");
        assertEquals(1, b.restingOrders(), "the market remainder never rests");
        HftOrderBook open = book();
        open.submitLimit(Side.SELL, 10_000, 10, 1);
        open.submitLimit(Side.SELL, 10_900, 10, 2);
        assertEquals(20, open.submitMarket(Side.BUY, 100, 3), "no band: sweeps the ladder");
        assertThrows(IllegalArgumentException.class, () -> book().marketProtectionTicks(-1));
    }

    @Test
    void tradesCarryStrictlyIncreasingSequenceNumbers() {
        HftOrderBook b = book();
        List<long[]> trades = record(b);
        for (int i = 0; i < 5; i++) {
            b.submitLimit(Side.SELL, 10_000 + i, 10, i);
        }
        b.submitMarket(Side.BUY, 50, 9);
        assertEquals(5, trades.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, trades.get(i)[4]);
        }
        assertEquals(5, b.lastTradeSequence());
    }

    // ------------------------------------------------------------------
    // E2: deferred delivery
    // ------------------------------------------------------------------

    @Test
    void throwingSinkCannotTearTheBook() {
        HftOrderBook b = book();
        long maker = b.submitLimit(Side.SELL, 10_000, 10, 1);
        b.submitLimit(Side.SELL, 10_001, 10, 2);
        b.tradeSink((m, t, tick, qty, ts) -> {
            throw new IllegalStateException("sink bug");
        });
        assertThrows(IllegalStateException.class, () -> b.submitLimit(Side.BUY, 10_001, 25, 3));
        // The book finished matching before the sink ran: both makers gone,
        // the 5-lot remainder rests, nothing half-linked.
        assertEquals(0, b.openQuantity(maker));
        assertEquals(1, b.restingOrders());
        assertEquals(5, b.qtyAtTick(Side.BUY, 10_001));
        assertEquals(10_001, b.bestBidTick());
        assertEquals(Integer.MAX_VALUE, b.bestAskTick());
        assertEquals(2, b.tradeCount());
    }
}

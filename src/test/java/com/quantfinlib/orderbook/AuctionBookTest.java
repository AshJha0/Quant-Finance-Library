package com.quantfinlib.orderbook;

import com.quantfinlib.util.AllocationProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uncross examples worked by hand (see each comment), plus a brute-force cross-check. */
class AuctionBookTest {

    private record Trade(long buy, long sell, int price, long qty) {
    }

    private static AuctionBook book(List<Trade> trades) {
        AuctionBook b = new AuctionBook(1, 100, 1024);
        b.tradeSink((seq, buy, sell, px, q, ts) -> trades.add(new Trade(buy, sell, px, q)));
        b.openCall();
        return b;
    }

    @Test
    void maximumVolumeExample() {
        // Buys 100@10, 200@9; sells 150@8, 100@9.
        // p=8: D=300 S=150 V=150; p=9: D=300 S=250 V=250; p=10: D=100 S=250 V=100.
        List<Trade> trades = new ArrayList<>();
        AuctionBook b = book(trades);
        long a = b.submitLimit(Side.BUY, 10, 100);
        long bb = b.submitLimit(Side.BUY, 9, 200);
        long c = b.submitLimit(Side.SELL, 8, 150);
        long d = b.submitLimit(Side.SELL, 9, 100);
        assertEquals(9, b.computeIndicative());
        assertEquals(250, b.indicativeVolume());
        assertEquals(50, b.indicativeImbalance());
        assertEquals(0, trades.size(), "nothing trades during the call");
        assertEquals(250, b.uncross(5));
        // Priority: buy 100@10 then 200@9; sell 150@8 then 100@9.
        assertEquals(List.of(new Trade(a, c, 9, 100), new Trade(bb, c, 9, 50), new Trade(bb, d, 9, 100)),
                trades);
        assertEquals(50, b.openQuantity(bb));
        assertEquals(1, b.restingOrders());
        assertEquals(50, b.qtyAtTick(Side.BUY, 9));
        assertEquals(AuctionBook.NO_PRICE, b.indicativePrice(), "residual does not cross");
        assertEquals(3, b.tradeCount());
    }

    @Test
    void imbalanceBreaksAVolumeTie() {
        // Buy 100@10; sells 100@9, 50@10. p=9: V=100, I=0; p=10: V=100, I=-50. -> 9.
        AuctionBook b = book(new ArrayList<>());
        b.submitLimit(Side.BUY, 10, 100);
        b.submitLimit(Side.SELL, 9, 100);
        b.submitLimit(Side.SELL, 10, 50);
        b.referencePrice(10);
        assertEquals(9, b.computeIndicative());
        assertEquals(0, b.indicativeImbalance());
    }

    @Test
    void referencePriceBreaksTheRemainingTie() {
        // Buy 100@11, sell 100@9: 9, 10 and 11 all give V=100, I=0.
        for (int[] c : new int[][]{{10, 10}, {12, 11}, {5, 9}, {AuctionBook.NO_PRICE, 9}}) {
            AuctionBook b = book(new ArrayList<>());
            b.submitLimit(Side.BUY, 11, 100);
            b.submitLimit(Side.SELL, 9, 100);
            b.referencePrice(c[0]);
            assertEquals(c[1], b.computeIndicative(), "reference " + c[0]);
        }
    }

    @Test
    void marketOrdersExecuteFirstAndUnfilledOnesAreCancelled() {
        // Market buy 50, buy 100@10; sell 100@9, market sell 0. p=9 and 10: D=150, S=100,
        // V=100, I=+50; reference 10 picks 10. Market buy fills 50 first, then 50 of 100@10.
        List<Trade> trades = new ArrayList<>();
        AuctionBook b = book(trades);
        long m = b.submitMarket(Side.BUY, 50);
        long l = b.submitLimit(Side.BUY, 10, 100);
        long s = b.submitLimit(Side.SELL, 9, 100);
        b.referencePrice(10);
        assertEquals(50, b.marketQty(Side.BUY));
        assertEquals(100, b.uncross(1));
        assertEquals(List.of(new Trade(m, s, 10, 50), new Trade(l, s, 10, 50)), trades);
        assertEquals(50, b.openQuantity(l));

        // A market sell larger than all demand: the excess is cancelled.
        List<Trade> t2 = new ArrayList<>();
        AuctionBook c = book(t2);
        c.submitLimit(Side.BUY, 20, 30);
        long ms = c.submitMarket(Side.SELL, 100);
        c.referencePrice(25);
        assertEquals(20, c.computeIndicative());        // V=30 at 20, nothing executes above
        assertEquals(30, c.uncross(1));
        assertEquals(0, c.openQuantity(ms));
        assertEquals(0, c.restingOrders());
        assertEquals(0, c.marketQty(Side.SELL));
    }

    @Test
    void noCrossMeansNoPriceAndImbalanceIsTotalSurplus() {
        AuctionBook b = book(new ArrayList<>());
        assertEquals(AuctionBook.NO_PRICE, b.computeIndicative());
        b.submitLimit(Side.BUY, 9, 70);
        b.submitLimit(Side.SELL, 10, 20);
        long[] seen = new long[3];
        b.indicativeListener((p, v, imb, ts) -> {
            seen[0] = p;
            seen[1] = v;
            seen[2] = imb;
        });
        assertEquals(AuctionBook.NO_PRICE, b.publishIndicative(3));
        assertEquals(AuctionBook.NO_PRICE, seen[0]);
        assertEquals(0, seen[1]);
        assertEquals(50, seen[2]);
        assertEquals(0, b.uncross(4));
        assertEquals(2, b.restingOrders());
    }

    @Test
    void validationAndPhase() {
        AuctionBook b = new AuctionBook(1, 100, 2);
        assertEquals(AuctionBook.REJECT_NOT_IN_CALL, b.submitLimit(Side.BUY, 5, 1));
        assertEquals(AuctionBook.REJECT_NOT_IN_CALL, b.submitMarket(Side.BUY, 1));
        b.openCall();
        assertTrue(b.isInCall());
        assertEquals(AuctionBook.REJECT_INVALID, b.submitLimit(Side.BUY, 5, 0));
        assertEquals(AuctionBook.REJECT_INVALID, b.submitMarket(null, 1));
        assertEquals(AuctionBook.REJECT_OUT_OF_BAND, b.submitLimit(Side.BUY, 101, 1));
        assertEquals(AuctionBook.REJECT_OUT_OF_BAND, b.submitLimit(Side.BUY, 0, 1));
        long id = b.submitLimit(Side.BUY, 5, 1);
        b.submitLimit(Side.BUY, 5, 1);
        assertEquals(AuctionBook.REJECT_POOL_FULL, b.submitLimit(Side.BUY, 5, 1));
        assertTrue(b.cancel(id));
        assertFalse(b.cancel(id));
        assertEquals(1, b.qtyAtTick(Side.BUY, 5));
        assertEquals(0, b.qtyAtTick(Side.BUY, 500));
        assertThrows(IllegalArgumentException.class, () -> b.referencePrice(1000));
        assertThrows(IllegalStateException.class, () -> b.transferResidual(new HftOrderBook(1, 100, 4), null, 0));
        assertThrows(IllegalArgumentException.class, () -> new AuctionBook(5, 4, 1));
        b.referencePrice(7);
        assertEquals(7, b.referencePrice());
    }

    @Test
    void residualMovesIntoTheContinuousBookInTimeOrder() {
        List<Trade> trades = new ArrayList<>();
        AuctionBook b = book(trades);
        long b1 = b.submitLimit(Side.BUY, 9, 10);
        long b2 = b.submitLimit(Side.BUY, 9, 20);
        long s1 = b.submitLimit(Side.SELL, 12, 5);
        b.uncross(1);
        assertEquals(0, trades.size());
        HftOrderBook cont = new HftOrderBook(1, 100, 16);
        Map<Long, Long> moved = new HashMap<>();
        b.transferResidual(cont, moved::put, 2);
        assertEquals(0, b.restingOrders());
        assertEquals(3, moved.size());
        assertEquals(30, cont.qtyAtTick(Side.BUY, 9));
        assertEquals(5, cont.qtyAtTick(Side.SELL, 12));
        assertTrue(moved.get(b1) < moved.get(b2), "time priority kept at a level");
        assertEquals(5, cont.openQuantity(moved.get(s1)));
        // Next sell at 9 trades with b1's successor first.
        List<long[]> fills = new ArrayList<>();
        cont.tradeSink((maker, taker, px, q, ts) -> fills.add(new long[]{maker, q}));
        cont.submitLimit(Side.SELL, 9, 10, 3);
        assertEquals(moved.get(b1), fills.getFirst()[0]);
    }

    @Test
    void callPhaseIsAllocationFree() {
        AuctionBook b = new AuctionBook(1, 1000, 4096);
        b.openCall();
        b.referencePrice(500);
        long[] sum = {0};
        b.indicativeListener((p, v, imb, ts) -> sum[0] += v);
        Runnable burst = () -> {
            for (int i = 0; i < 500; i++) {
                long id1 = b.submitLimit(Side.BUY, 480 + (i % 40), 1 + (i % 9));
                long id2 = b.submitLimit(Side.SELL, 470 + (i % 50), 1 + (i % 7));
                long id3 = b.submitMarket(i % 2 == 0 ? Side.BUY : Side.SELL, 3);
                if (i % 10 == 0) {
                    b.publishIndicative(i);
                }
                b.cancel(id1);
                b.cancel(id2);
                b.cancel(id3);
            }
        };
        AllocationProbe.assertZeroAllocation(burst, burst, 5);
    }

    /**
     * Brute force over every tick with the three rules written out plainly,
     * against the book, on random auctions; also checks the uncross volume,
     * per-side fills and an uncrossed residual.
     */
    @Test
    void matchesBruteForceOnRandomAuctions() {
        SplittableRandom rng = new SplittableRandom(7L);
        for (int round = 0; round < 300; round++) {
            List<Trade> trades = new ArrayList<>();
            AuctionBook b = new AuctionBook(1, 60, 256);
            b.tradeSink((seq, buy, sell, px, q, ts) -> trades.add(new Trade(buy, sell, px, q)));
            b.openCall();
            int ref = rng.nextInt(4) == 0 ? AuctionBook.NO_PRICE : 1 + rng.nextInt(60);
            b.referencePrice(ref);
            List<long[]> orders = new ArrayList<>();   // {side 0/1, price or -1 market, qty}
            int n = 1 + rng.nextInt(30);
            for (int i = 0; i < n; i++) {
                int side = rng.nextInt(2);
                long q = 1L + rng.nextInt(50);
                if (rng.nextInt(8) == 0) {
                    b.submitMarket(side == 0 ? Side.BUY : Side.SELL, q);
                    orders.add(new long[]{side, -1, q});
                } else {
                    int px = 20 + rng.nextInt(20);
                    b.submitLimit(side == 0 ? Side.BUY : Side.SELL, px, q);
                    orders.add(new long[]{side, px, q});
                }
            }
            long[] expect = bruteForce(orders, ref);
            int price = b.computeIndicative();
            assertEquals(expect[0], price, "round " + round);
            assertEquals(expect[1], b.indicativeVolume(), "round " + round);
            if (price != AuctionBook.NO_PRICE) {
                assertEquals(expect[2], b.indicativeImbalance(), "round " + round);
            }
            long vol = b.uncross(0);
            assertEquals(expect[1], vol);
            assertEquals(vol, trades.stream().mapToLong(Trade::qty).sum());
            assertTrue(trades.stream().allMatch(t -> t.price() == price));
            int bestBid = -1;
            int bestAsk = Integer.MAX_VALUE;
            for (int px = 1; px <= 60; px++) {
                if (b.qtyAtTick(Side.BUY, px) > 0) {
                    bestBid = px;
                }
                if (b.qtyAtTick(Side.SELL, px) > 0 && bestAsk == Integer.MAX_VALUE) {
                    bestAsk = px;
                }
            }
            assertTrue(bestBid < bestAsk, "residual uncrossed, round " + round);
            assertEquals(0, b.marketQty(Side.BUY) + b.marketQty(Side.SELL));
        }
    }

    private static long[] bruteForce(List<long[]> orders, int ref) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (long[] o : orders) {
            if (o[1] >= 0) {
                lo = (int) Math.min(lo, o[1]);
                hi = (int) Math.max(hi, o[1]);
            }
        }
        if (ref != AuctionBook.NO_PRICE) {
            lo = Math.min(lo, ref);
            hi = Math.max(hi, ref);
        }
        long bestP = AuctionBook.NO_PRICE;
        long bestV = 0;
        long bestImb = 0;
        for (int p = lo; p <= hi; p++) {
            long d = 0;
            long s = 0;
            for (long[] o : orders) {
                if (o[0] == 0 && (o[1] < 0 || o[1] >= p)) {
                    d += o[2];
                }
                if (o[0] == 1 && (o[1] < 0 || o[1] <= p)) {
                    s += o[2];
                }
            }
            long v = Math.min(d, s);
            long imb = d - s;
            if (v == 0) {
                continue;
            }
            boolean better;
            if (bestP == AuctionBook.NO_PRICE || v > bestV) {
                better = true;
            } else if (v < bestV) {
                better = false;
            } else if (Math.abs(imb) != Math.abs(bestImb)) {
                better = Math.abs(imb) < Math.abs(bestImb);
            } else {
                better = ref != AuctionBook.NO_PRICE && Math.abs(p - ref) < Math.abs(bestP - ref);
            }
            if (better) {
                bestP = p;
                bestV = v;
                bestImb = imb;
            }
        }
        return new long[]{bestP, bestV, bestImb};
    }
}

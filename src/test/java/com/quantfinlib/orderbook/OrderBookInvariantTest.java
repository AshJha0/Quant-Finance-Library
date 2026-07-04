package com.quantfinlib.orderbook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-based fuzz test: hammers the {@link OrderBook} with 100k random
 * operations while maintaining an independent reference model of resting
 * orders, and checks the structural invariants example-based tests can miss.
 */
class OrderBookInvariantTest {

    private static final class ModelOrder {
        final Side side;
        final double price;
        long remaining;

        ModelOrder(Side side, double price, long remaining) {
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }
    }

    private final OrderBook book = new OrderBook("FUZZ");
    /** Insertion-ordered reference model of orders the book should be resting. */
    private final LinkedHashMap<Long, ModelOrder> model = new LinkedHashMap<>();
    private final Map<Long, Long> takerFills = new HashMap<>();

    @Test
    void randomOperationsPreserveAllInvariants() {
        book.addTradeListener((makerId, takerId, price, qty, ts) -> {
            ModelOrder maker = model.get(makerId);
            if (maker != null) {
                maker.remaining -= qty;
                if (maker.remaining == 0) {
                    model.remove(makerId);
                }
            }
            takerFills.merge(takerId, qty, Long::sum);
        });

        SplittableRandom rnd = new SplittableRandom(20260704);
        List<Long> knownIds = new ArrayList<>();

        for (int op = 0; op < 100_000; op++) {
            int kind = rnd.nextInt(100);
            if (kind < 60) {
                // Limit order on a price grid that regularly crosses.
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                double price = 90 + rnd.nextInt(41) * 0.5;
                long qty = 1 + rnd.nextInt(500);
                takerFills.clear();
                long id = book.submitLimit(side, price, qty, op);
                long filled = takerFills.getOrDefault(id, 0L);
                assertTrue(filled <= qty, "overfilled taker");
                if (filled < qty) {
                    model.put(id, new ModelOrder(side, price, qty - filled));
                    knownIds.add(id);
                }
            } else if (kind < 80 && !knownIds.isEmpty()) {
                // Cancel a random known id — idempotence included.
                long id = knownIds.get(rnd.nextInt(knownIds.size()));
                boolean shouldSucceed = model.containsKey(id);
                assertEquals(shouldSucceed, book.cancel(id), "cancel disagreement for " + id);
                model.remove(id);
                assertFalse(book.cancel(id), "cancel must be idempotent");
            } else if (kind < 90) {
                takerFills.clear();
                book.submitMarket(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        1 + rnd.nextInt(300), op);
            }
            if (op % 500 == 0) {
                assertInvariants();
            }
        }
        assertInvariants();

        // Drain: cancel everything; the book must be exactly empty.
        for (long id : new ArrayList<>(model.keySet())) {
            assertTrue(book.cancel(id));
            model.remove(id);
        }
        assertEquals(0, book.depth(Side.BUY, Integer.MAX_VALUE));
        assertEquals(0, book.depth(Side.SELL, Integer.MAX_VALUE));
        assertTrue(Double.isNaN(book.bestBid()) && Double.isNaN(book.bestAsk()));
    }

    private void assertInvariants() {
        // 1. Never crossed.
        double bid = book.bestBid();
        double ask = book.bestAsk();
        if (!Double.isNaN(bid) && !Double.isNaN(ask)) {
            assertTrue(bid < ask, "crossed book: " + bid + " >= " + ask);
        }
        // 2. Depth equals the model's total resting quantity, per side.
        long modelBuy = 0, modelSell = 0;
        double bestModelBid = Double.NaN, bestModelAsk = Double.NaN;
        for (ModelOrder o : model.values()) {
            if (o.side == Side.BUY) {
                modelBuy += o.remaining;
                bestModelBid = Double.isNaN(bestModelBid) ? o.price : Math.max(bestModelBid, o.price);
            } else {
                modelSell += o.remaining;
                bestModelAsk = Double.isNaN(bestModelAsk) ? o.price : Math.min(bestModelAsk, o.price);
            }
        }
        assertEquals(modelBuy, book.depth(Side.BUY, Integer.MAX_VALUE), "bid depth diverged");
        assertEquals(modelSell, book.depth(Side.SELL, Integer.MAX_VALUE), "ask depth diverged");
        // 3. Top of book equals the model's best prices.
        assertEquals(bestModelBid, bid, 1e-12);
        assertEquals(bestModelAsk, ask, 1e-12);
        // 4. Queue positions: earlier orders at the same price are ahead.
        int checked = 0;
        for (Map.Entry<Long, ModelOrder> e : model.entrySet()) {
            if (checked++ >= 20) {
                break;
            }
            long expectedAhead = 0;
            for (Map.Entry<Long, ModelOrder> other : model.entrySet()) {
                if (other.getKey().equals(e.getKey())) {
                    break;   // insertion order = time priority
                }
                ModelOrder o = other.getValue();
                if (o.side == e.getValue().side && o.price == e.getValue().price) {
                    expectedAhead += o.remaining;
                }
            }
            assertEquals(expectedAhead, book.qtyAhead(e.getKey()),
                    "queue position diverged for order " + e.getKey());
        }
    }
}

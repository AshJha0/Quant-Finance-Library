package com.quantfinlib.trading;

import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperTradingBustTest {

    private static final double CASH = 100_000;

    @Test
    void bustAndCorrectRebuildTheAccount() {
        PaperTradingGateway gw = new PaperTradingGateway(CASH);
        List<String> events = new ArrayList<>();
        gw.addTradeListener(new PaperTradingGateway.TradeListener() {
            @Override
            public void onFill(long execId, long orderId, String symbol, Side side, long qty,
                               double price, long ts) {
                events.add("fill " + execId);
            }

            @Override
            public void onBust(long execId, long execRefId, long orderId, String symbol,
                               Side side, long qty, long ts) {
                events.add("bust " + execId + "->" + execRefId + " " + qty);
            }

            @Override
            public void onCorrect(long execId, long execRefId, long orderId, String symbol,
                                  Side side, long qty, double price, long ts) {
                events.add("correct " + execId + "->" + execRefId + " " + qty + "@" + price);
            }
        });
        gw.onQuote("X", 9.9, 10);
        long o1 = gw.submitMarket("X", Side.BUY, 100);
        gw.onQuote("X", 10.9, 11);
        long o2 = gw.submitMarket("X", Side.BUY, 50);
        assertArrayEquals(new long[] {1}, gw.executionIds(o1));
        assertArrayEquals(new long[] {2}, gw.executionIds(o2));

        assertEquals(3, gw.bustFill(1));
        assertEquals(50, gw.position("X"), 1e-9);
        assertEquals(CASH - 550, gw.cash(), 1e-9);
        // Default (2.3 behaviour): the order is not reopened.
        assertEquals(OrderStatus.FILLED, gw.status(o1));
        assertEquals(100, gw.filledQuantity(o1));

        assertEquals(4, gw.correctFill(2, 40, 10.5));
        assertEquals(40, gw.position("X"), 1e-9);
        assertEquals(CASH - 420, gw.cash(), 1e-9);
        assertEquals(List.of("fill 1", "fill 2", "bust 3->1 100", "correct 4->2 40@10.5"), events);
        assertEquals(2, gw.tradeAdjustmentCount());
    }

    @Test
    void invalidAdjustmentsAreRefusedAndCounted() {
        PaperTradingGateway gw = new PaperTradingGateway(CASH);
        gw.onQuote("X", 9.9, 10);
        gw.submitMarket("X", Side.BUY, 100);
        assertEquals(-1, gw.bustFill(99));                 // unknown exec id
        assertEquals(-1, gw.correctFill(99, 10, 10));
        assertEquals(-1, gw.correctFill(1, 101, 10));      // more than the order quantity
        assertEquals(-1, gw.correctFill(1, 0, 10));
        assertEquals(-1, gw.correctFill(1, 10, Double.NaN));
        assertTrue(gw.bustFill(1) > 0);
        assertEquals(-1, gw.bustFill(1));                  // already busted
        assertEquals(-1, gw.correctFill(1, 10, 10));
        assertEquals(7, gw.rejectedTradeAdjustmentCount());
        assertEquals(1, gw.tradeAdjustmentCount());
        assertEquals(CASH, gw.cash(), 1e-9);
        assertEquals(0, gw.position("X"), 1e-9);
    }

    @Test
    void realisedPnlAndCommissionAreReplayed() {
        PaperTradingGateway gw = new PaperTradingGateway(CASH, 0.001, null);
        gw.onQuote("X", 10, 10);
        gw.submitMarket("X", Side.BUY, 100);   // exec 1 at 10
        gw.onQuote("X", 12, 12);
        gw.submitMarket("X", Side.SELL, 100);  // exec 2 at 12, realises 200
        assertEquals(200, gw.realizedPnl(), 1e-9);
        gw.bustFill(1);                         // the sale now opens a short
        assertEquals(0, gw.realizedPnl(), 1e-9);
        assertEquals(-100, gw.position("X"), 1e-9);
        assertEquals(CASH + 1200 - 1.2, gw.cash(), 1e-9);
    }

    @Test
    void reopenOnBustPutsALimitOrderBackOnTheBook() {
        PaperTradingGateway gw = new PaperTradingGateway(CASH);
        gw.setReopenOnBust(true);
        gw.onQuote("X", 9, 10, 10, 60);
        long o = gw.submitLimit("X", Side.BUY, 100, 10);   // 60 fill, 40 rest
        assertEquals(OrderStatus.PARTIALLY_FILLED, gw.status(o));
        gw.onQuote("X", 9, 10, 10, 100);                   // remaining 40 fill
        assertEquals(OrderStatus.FILLED, gw.status(o));
        long[] ids = gw.executionIds(o);
        assertEquals(2, ids.length);

        gw.bustFill(ids[1]);
        assertEquals(OrderStatus.PARTIALLY_FILLED, gw.status(o));
        assertEquals(60, gw.filledQuantity(o));
        gw.correctFill(ids[0], 30, 10);
        assertEquals(30, gw.filledQuantity(o));
        gw.onQuote("X", 9, 10, 10, 1000);                  // reopened order fills again
        assertEquals(OrderStatus.FILLED, gw.status(o));
        assertEquals(100, gw.position("X"), 1e-9);

        long m = gw.submitMarket("X", Side.BUY, 10);
        gw.bustFill(gw.executionIds(m)[0]);
        assertEquals(OrderStatus.CANCELED, gw.status(m));   // market remainder is IOC
        assertEquals(0, gw.filledQuantity(m));
    }

    @Test
    void ledgerReconcilesAgainstTheDropCopyAfterBustAndCorrect() {
        PaperTradingGateway gw = new PaperTradingGateway(CASH);
        PositionLedger ledger = new PositionLedger(1, 1, 1, 64, 64);
        gw.addTradeListener(LedgerFillListener.forPaperTrades(ledger, 0, 0, s -> 0));
        List<DropCopyReconciler.Fill> dropCopy = new ArrayList<>();
        List<DropCopyReconciler.Adjustment> adjustments = new ArrayList<>();
        gw.addTradeListener(new PaperTradingGateway.TradeListener() {
            @Override
            public void onFill(long execId, long orderId, String symbol, Side side, long qty,
                               double price, long ts) {
                dropCopy.add(DropCopyReconciler.Fill.dropCopy(execId, 0, side, qty, price));
            }

            @Override
            public void onBust(long execId, long execRefId, long orderId, String symbol,
                               Side side, long qty, long ts) {
                adjustments.add(DropCopyReconciler.Adjustment.bust(execId, execRefId));
            }

            @Override
            public void onCorrect(long execId, long execRefId, long orderId, String symbol,
                                  Side side, long qty, double price, long ts) {
                adjustments.add(DropCopyReconciler.Adjustment.correct(execId, execRefId, qty,
                        price));
            }
        });
        gw.onQuote("X", 9.9, 10);
        gw.submitMarket("X", Side.BUY, 100);
        gw.submitMarket("X", Side.BUY, 30);
        gw.submitMarket("X", Side.SELL, 20);
        gw.bustFill(2);
        gw.correctFill(3, 10, 9.95);

        DropCopyReconciler.Report report =
                DropCopyReconciler.reconcile(ledger, dropCopy, adjustments, 1e-9);
        assertTrue(report.isClean(), report.format());
        assertEquals(90, ledger.netPosition(0, 0, 0));
        assertEquals(gw.position("X"), (double) ledger.netPosition(0, 0, 0), 1e-9);
    }
}

package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gateway fills reach the ledger through the opt-in listener and reconcile with a drop copy. */
class LedgerFillWiringTest {

    @Test
    void simulatedSessionWithPartialFillsReconcilesWithTheDropCopy() {
        HftOrderGateway gw = new HftOrderGateway(new HftRiskGate(2));
        PositionLedger ledger = new PositionLedger(2, 1, 1, 64, 1024);
        LedgerFillListener wiring = new LedgerFillListener(ledger, 0, 0);
        gw.addFillListener(wiring);
        List<DropCopyReconciler.Fill> dropCopy = new ArrayList<>();
        gw.addFillListener((exec, order, sym, side, q, px, ts) ->
                dropCopy.add(DropCopyReconciler.Fill.dropCopy(exec, sym, side, q, px)));

        // reports delivered directly on the test thread: a deterministic session
        long a = gw.submit(0, Side.BUY, 300, 10.0, 1);
        long b = gw.submit(1, Side.SELL, 100, 20.0, 2);
        long c = gw.submit(0, Side.SELL, 120, 11.0, 3);
        gw.onAck(a, 4);
        gw.onAck(b, 4);
        gw.onAck(c, 4);
        assertTrue(gw.onFill(a, 1001, 100, 10.0, 5));   // partial, venue exec id
        assertTrue(gw.onFill(a, 1002, 200, 10.1, 6));   // completes a
        assertTrue(gw.onFill(b, 40, 20.0, 7));          // partial, gateway-numbered
        assertTrue(gw.onFill(c, 1003, 120, 11.0, 8));
        assertEquals(false, gw.onFill(c, 1004, 1, 11.0, 9));  // filled order: not published

        assertEquals(180, ledger.position(0, 0, 0));
        assertEquals(-40, ledger.position(0, 0, 1));
        assertEquals(4, ledger.fillCount());
        assertEquals(0, wiring.rejectedCount());
        assertEquals(0, gw.fillListenerErrorCount());
        assertEquals(gw.riskGate().position(0), ledger.position(0, 0, 0));

        DropCopyReconciler.Report clean = DropCopyReconciler.reconcile(ledger, dropCopy, 1e-9);
        assertTrue(clean.isClean(), clean.format());
        assertEquals(4, clean.matched());

        // a fill the venue reported but the gateway never accepted shows up as a break
        dropCopy.add(DropCopyReconciler.Fill.dropCopy(1004, 0, Side.SELL, 1, 11.0));
        DropCopyReconciler.Report broken = DropCopyReconciler.reconcile(ledger, dropCopy, 1e-9);
        assertEquals(1, broken.breaks(DropCopyReconciler.BreakType.MISSING).size());
        assertEquals(Map.of(0, -1L), broken.quantityDifference());
    }

    @Test
    void loopbackVenueSessionOnTheVenueThreadReconciles() throws Exception {
        HftOrderGateway gw = new HftOrderGateway(new HftRiskGate(1));
        PositionLedger ledger = new PositionLedger(1, 1, 1, 64, 1024);
        gw.addFillListener(new LedgerFillListener(ledger, 0, 0));
        List<DropCopyReconciler.Fill> dropCopy = new ArrayList<>();
        gw.addFillListener((exec, order, sym, side, q, px, ts) ->
                dropCopy.add(DropCopyReconciler.Fill.dropCopy(exec, sym, side, q, px)));
        gw.addFillListener((exec, order, sym, side, q, px, ts) -> {
            throw new IllegalStateException("faulty listener is counted, not fatal");
        });
        // half of each order fills on arrival
        gw.addOrderListener(new LoopbackVenue(gw, (id, s, side, q, px, f) -> q / 2, 1024));
        gw.start();
        try {
            for (int i = 0; i < 10; i++) {
                gw.submit(0, i % 3 == 0 ? Side.SELL : Side.BUY, 10, 100.0 + i, i);
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (gw.deliveredCount() < 10 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        } finally {
            gw.stop();
        }
        assertEquals(10, ledger.fillCount());
        assertEquals(10, gw.fillListenerErrorCount());
        // 6 buys and 4 sells of 5 each
        assertEquals(10, ledger.position(0, 0, 0));
        assertTrue(DropCopyReconciler.reconcile(ledger, dropCopy, 0).isClean());
    }

    @Test
    void paperGatewayAdapterBooksFills() {
        PaperTradingGateway paper = new PaperTradingGateway(1_000_000);
        PositionLedger ledger = new PositionLedger(1, 1, 1, 8, 64);
        paper.addExecutionListener(LedgerFillListener.forPaper(ledger, 0, 0, s -> 0));
        paper.onQuote("X", 99.0, 101.0);
        paper.submitMarket("X", Side.BUY, 30);
        paper.submitMarket("X", Side.SELL, 10);
        assertEquals(20, ledger.position(0, 0, 0));
        assertEquals((long) paper.position("X"), ledger.position(0, 0, 0));
        assertEquals(1, ledger.loggedFill(0).execId());
        assertEquals(2, ledger.loggedFill(1).execId());
    }
}

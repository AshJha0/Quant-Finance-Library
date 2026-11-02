package com.quantfinlib.trading;

import com.quantfinlib.fix.ExecutionReport;
import com.quantfinlib.core.Side;
import com.quantfinlib.trading.PositionLedger.CostMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trade busts (ExecType H) and corrections (ExecType G) from gateway to ledger to drop copy. */
class PositionLedgerBustTest {

    private static final double EPS = 1e-9;

    /** Every book of {@code a} equals the book of {@code b} (b fed the surviving tape only). */
    private static void assertSameBook(PositionLedger a, PositionLedger b, int sym) {
        assertEquals(b.position(0, 0, sym), a.position(0, 0, sym));
        assertEquals(b.averageCost(0, 0, sym), a.averageCost(0, 0, sym), EPS);
        for (CostMethod m : CostMethod.values()) {
            assertEquals(b.realised(m, 0, 0, sym), a.realised(m, 0, 0, sym), EPS, m.name());
            assertEquals(b.unrealised(m, 0, 0, sym, s -> 13.0),
                    a.unrealised(m, 0, 0, sym, s -> 13.0), EPS, m.name());
        }
        assertEquals(b.openLots(0, 0, sym), a.openLots(0, 0, sym));
        assertEquals(b.fees(0, 0, sym), a.fees(0, 0, sym), EPS);
        assertEquals(b.boughtQuantity(0, 0, sym), a.boughtQuantity(0, 0, sym));
        assertEquals(b.soldQuantity(0, 0, sym), a.soldQuantity(0, 0, sym));
    }

    @Test
    void bustOfOnePartialFillMatchesTheSurvivingTape() {
        PositionLedger l = new PositionLedger(1, 1, 1, 16, 64);
        assertTrue(l.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0, 1.0));
        assertTrue(l.onFill(2, 0, 0, 0, Side.BUY, 100, 12.0, 1.0));
        assertTrue(l.onFill(3, 0, 0, 0, Side.SELL, 150, 11.0, 1.0));
        assertTrue(l.onBust(4, 2));

        PositionLedger ref = new PositionLedger(1, 1, 1, 16, 64);
        ref.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0, 1.0);
        ref.onFill(3, 0, 0, 0, Side.SELL, 150, 11.0, 1.0);
        assertSameBook(l, ref, 0);
        // worked example in the PositionLedger javadoc
        assertEquals(-50, l.position(0, 0, 0));
        assertEquals(11.0, l.averageCost(0, 0, 0), EPS);
        assertEquals(100.0, l.realised(CostMethod.AVERAGE_COST, 0, 0, 0), EPS);
        assertEquals(100.0, l.realised(CostMethod.FIFO, 0, 0, 0), EPS);

        // audit trail: original kept, marked busted, bust entry appended
        assertEquals(4, l.loggedFills());
        assertEquals(PositionLedger.FillStatus.BUSTED, l.loggedStatus(1));
        assertEquals(12.0, l.loggedFill(1).price(), EPS);
        assertEquals(PositionLedger.EntryKind.BUST, l.loggedKind(3));
        assertEquals(2, l.loggedRefExecId(3));
        assertEquals(1, l.bustCount());
        assertEquals(3, l.fillCount());
        assertEquals(2, l.effectiveFills().size());
    }

    @Test
    void correctionOfQuantityAndPriceThenItsCorrectionAndBust() {
        PositionLedger l = new PositionLedger(1, 1, 1, 16, 64);
        l.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0);
        l.onFill(2, 0, 0, 0, Side.BUY, 100, 12.0);
        l.onFill(3, 0, 0, 0, Side.SELL, 150, 11.0);
        assertTrue(l.onCorrect(10, 2, 80, 11.5));

        PositionLedger ref = new PositionLedger(1, 1, 1, 16, 64);
        ref.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0);
        ref.onFill(2, 0, 0, 0, Side.BUY, 80, 11.5);
        ref.onFill(3, 0, 0, 0, Side.SELL, 150, 11.0);
        assertSameBook(l, ref, 0);
        assertEquals(30, l.position(0, 0, 0));
        assertEquals(PositionLedger.FillStatus.CORRECTED, l.loggedStatus(1));
        assertEquals(100, l.loggedFill(1).quantity());     // as reported, for the audit trail

        // correct the correction (ExecRefID = the correction's id), then bust it
        assertTrue(l.onCorrect(11, 10, 90, 11.0));
        ref = new PositionLedger(1, 1, 1, 16, 64);
        ref.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0);
        ref.onFill(2, 0, 0, 0, Side.BUY, 90, 11.0);
        ref.onFill(3, 0, 0, 0, Side.SELL, 150, 11.0);
        assertSameBook(l, ref, 0);
        assertTrue(l.onBust(12, 11));
        assertEquals(-50, l.position(0, 0, 0));
        assertEquals(2, l.correctionCount());
        assertEquals(3, l.adjustments().size());
    }

    @Test
    void unknownOrRepeatedOrAlreadyBustedReferencesAreRejected() {
        PositionLedger l = new PositionLedger(1, 1, 1, 16, 64);
        l.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0);
        assertFalse(l.onBust(5, 999));                 // unknown ExecRefID
        assertFalse(l.onCorrect(6, 999, 10, 10.0));
        assertFalse(l.onCorrect(7, 1, 0, 10.0));       // bad corrected quantity
        assertFalse(l.onBust(1, 1));                   // repeated report id
        assertTrue(l.onBust(8, 1));
        assertFalse(l.onBust(9, 1));                   // already busted
        assertFalse(l.onBust(10, 8));                  // a bust cannot be busted
        assertEquals(6, l.rejectedAdjustmentCount());
        assertEquals(0, l.position(0, 0, 0));
        assertEquals(2, l.loggedFills());
    }

    @Test
    void fullLogRefusesAdjustments() {
        PositionLedger l = new PositionLedger(1, 1, 1, 16, 1);
        l.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0);
        assertFalse(l.onBust(2, 1));
        assertEquals(100, l.position(0, 0, 0));
    }

    @Test
    void gatewayBustAndCorrectReachLedgerRiskGateAndReconcileClean() {
        HftOrderGateway gw = new HftOrderGateway(new HftRiskGate(1));
        PositionLedger ledger = new PositionLedger(1, 1, 1, 64, 1024);
        LedgerFillListener wiring = new LedgerFillListener(ledger, 0, 0);
        gw.addFillListener(wiring);
        List<DropCopyReconciler.Fill> dc = new ArrayList<>();
        List<DropCopyReconciler.Adjustment> dcAdj = new ArrayList<>();
        gw.addFillListener(new FillListener() {
            @Override
            public void onFill(long e, long o, int s, Side sd, long q, double px, long ts) {
                dc.add(DropCopyReconciler.Fill.dropCopy(e, s, sd, q, px));
            }

            @Override
            public void onBust(long e, long ref, long o, int s, Side sd, long q, long ts) {
                dcAdj.add(DropCopyReconciler.Adjustment.bust(e, ref));
            }

            @Override
            public void onCorrect(long e, long ref, long o, int s, Side sd, long q, double px,
                                  long ts) {
                dcAdj.add(DropCopyReconciler.Adjustment.correct(e, ref, q, px));
            }
        });
        long a = gw.submit(0, Side.BUY, 300, 10.0, 1);
        gw.onAck(a, 2);
        assertTrue(gw.onFill(a, 101, 100, 10.0, 3));
        assertTrue(gw.onFill(a, 102, 100, 10.1, 4));
        assertTrue(gw.onFill(a, 103, 50, 10.2, 5));
        assertTrue(gw.onTradeBust(a, 201, 102, 100, 6));
        assertTrue(gw.onTradeCorrect(a, 202, 103, 50, 40, 10.15, 7));
        assertFalse(gw.onTradeBust(999, 203, 101, 10, 8));           // unknown order
        assertFalse(gw.onTradeBust(a, 204, 101, 10_000, 8));         // more than filled
        assertTrue(gw.onTradeBust(a, 205, 777, 10, 9));              // unknown exec: ledger refuses

        assertEquals(140, ledger.position(0, 0, 0));
        assertEquals((100 * 10.0 + 40 * 10.15) / 140, ledger.averageCost(0, 0, 0), EPS);
        assertEquals(1, wiring.rejectedCount());
        assertEquals(2, gw.rejectedTradeAdjustmentCount());
        // the gateway trusted the caller's quantity for exec 777: risk position differs by 10
        assertEquals(130, gw.riskGate().position(0));

        dcAdj.removeIf(x -> x.execId() == 205);   // the venue never sent that bust
        DropCopyReconciler.Report r = DropCopyReconciler.reconcile(ledger, dc, dcAdj, 1e-9);
        assertTrue(r.isClean(), r.format());
        assertEquals(2, r.matched());

        // drop copy missing the correction: the corrected fill and the adjustment both break
        List<DropCopyReconciler.Adjustment> partial = new ArrayList<>(dcAdj.subList(0, 1));
        DropCopyReconciler.Report broken = DropCopyReconciler.reconcile(ledger, dc, partial, 1e-9);
        assertEquals(1, broken.breaks(DropCopyReconciler.BreakType.MISMATCH).size());
        assertEquals(1, broken.breaks(DropCopyReconciler.BreakType.EXTRA).size());
    }

    @Test
    void dropCopyAdjustmentToUnknownExecutionIsABreak() {
        PositionLedger l = new PositionLedger(1, 1, 1, 16, 64);
        l.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0);
        List<DropCopyReconciler.Fill> dc = List.of(DropCopyReconciler.Fill.dropCopy(1, 0, Side.BUY, 100, 10.0));
        DropCopyReconciler.Report r = DropCopyReconciler.reconcile(l, dc,
                List.of(DropCopyReconciler.Adjustment.bust(9, 55)), 1e-9);
        assertEquals(1, r.breaks(DropCopyReconciler.BreakType.MISMATCH).size());
        assertEquals(1, r.breaks(DropCopyReconciler.BreakType.MISSING).size());
    }

    @Test
    void fixExecTypeHelpers() {
        assertTrue(ExecutionReport.isTradeCancel('H'));
        assertTrue(ExecutionReport.isTradeCorrect('G'));
        assertFalse(ExecutionReport.isTradeCancel('F'));
        assertFalse(ExecutionReport.isTradeCorrect('H'));
    }
}

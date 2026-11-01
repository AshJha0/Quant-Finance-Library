package com.quantfinlib.trading;

import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.util.AllocationProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming quoter (spread, skew, grid snap, conflation, working-order
 * maintenance by cancel/replace, BBO input and crossed checks) and the band
 * auto-hedger (working-hedge tracking, IOC at the touch, ack-driven
 * cooldown), driven through a live gateway with the venue side captured.
 */
class HftQuoterAutoHedgerTest {

    /** One captured venue-side message. */
    record Msg(char type, long id, int symbolId, Side side, long qty, double price, int flags) {
    }

    /** Records every venue message in order (thread-safe for asserts). */
    static final class Capture implements OrderListener {
        final List<Msg> msgs = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onOrder(long id, int sym, Side side, long qty, double px, long ts) {
            onNewOrder(id, sym, side, qty, px, FLAG_NONE, ts);
        }

        @Override
        public void onNewOrder(long id, int sym, Side side, long qty, double px, int flags,
                               long ts) {
            msgs.add(new Msg('N', id, sym, side, qty, px, flags));
        }

        @Override
        public void onCancel(long id, int sym, long ts) {
            msgs.add(new Msg('C', id, sym, null, 0, Double.NaN, 0));
        }

        @Override
        public void onReplace(long id, int sym, Side side, long qty, double px, long ts) {
            msgs.add(new Msg('R', id, sym, side, qty, px, 0));
        }

        @Override
        public void onCancelAll(int sym, long ts) {
            msgs.add(new Msg('A', 0, sym, null, 0, Double.NaN, 0));
        }

        List<Msg> of(char type) {
            synchronized (msgs) {
                return msgs.stream().filter(m -> m.type() == type).toList();
            }
        }
    }

    private static HftRiskGate openGate() {
        HftRiskGate gate = new HftRiskGate(16)
                .maxOrderQuantity(10_000_000)
                .maxOrderNotional(1e15)
                .maxPositionQuantity(Long.MAX_VALUE / 4)
                .priceCollarPct(0.50);
        gate.setReferencePrice(0, 1.0850);
        return gate;
    }

    /** Gateway with a capture listener and (optionally) an acking loopback venue. */
    private static Capture wire(HftOrderGateway gateway, LoopbackVenue.FillPolicy venue) {
        Capture c = new Capture();
        gateway.addOrderListener(c);
        if (venue != null) {
            gateway.addOrderListener(new LoopbackVenue(gateway, venue, 1 << 12));
        }
        gateway.start();
        return c;
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(1);
        }
    }

    // ------------------------------------------------------------------
    // HftQuoter: prices
    // ------------------------------------------------------------------

    @Test
    void quotesBothSidesAroundTheMid() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));

            quoter.onTick(0, 1.0850, 1_000_000, 1L);
            await(() -> c.of('N').size() == 2, "two orders");

            assertEquals(1, quoter.quoteUpdates());
            Msg bid = c.of('N').get(0);
            Msg ask = c.of('N').get(1);
            assertEquals(Side.BUY, bid.side());
            assertEquals(Side.SELL, ask.side());
            assertEquals(1.0850 - 0.0002, bid.price(), 1e-12);
            assertEquals(1.0850 + 0.0002, ask.price(), 1e-12);
            assertEquals(100_000, bid.qty());
        }
    }

    @Test
    void inventorySkewShadesTheQuoteAgainstThePosition() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002).withSkewPerUnit(1e-9));
            // Long 1m: skew = -1e6 * 1e-9 = -0.001, both sides down.
            gate.onFill(0, Side.BUY, 1_000_000);
            quoter.onTick(0, 1.0850, 1_000_000, 1L);
            await(() -> c.of('N').size() == 2, "two orders");
            assertEquals(1.0850 - 0.0002 - 0.001, c.of('N').get(0).price(), 1e-12);
            assertEquals(1.0850 + 0.0002 - 0.001, c.of('N').get(1).price(), 1e-12);
        }
    }

    @Test
    void gridSnapRoundsTowardPassivity() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.00017)
                    .withTickSchedule(TickSizeSchedule.flat(0.0001)));
            quoter.onTick(0, 1.0850, 1_000_000, 1L);
            await(() -> c.of('N').size() == 2, "two orders");
            // Raw bid 1.08483 -> 1.0848; raw ask 1.08517 -> 1.0852.
            assertEquals(1.0848, c.of('N').get(0).price(), 1e-12);
            assertEquals(1.0852, c.of('N').get(1).price(), 1e-12);
        }
    }

    @Test
    void skewedQuotesBelowTheFirstBandNeverThrow() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.0002)
                    .withSkewPerUnit(2e-7)
                    .withTickSchedule(TickSizeSchedule.builder().addBand(1.0, 0.0001).build()));
            gate.onFill(0, Side.BUY, 1_000_000); // skew = -0.2
            quoter.onTick(0, 1.0850, 1e6, 1L);
            await(() -> c.of('N').size() == 2, "two orders");
            assertTrue(c.of('N').get(0).price() < 1.0);
            assertTrue(c.of('N').get(0).price() > 0.88 - 1e-9);
        }
    }

    @Test
    void perSymbolConfigServesAMixedBook() throws Exception {
        HftRiskGate gate = openGate();
        gate.setReferencePrice(1, 155.00);
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002)
                            .withTickSchedule(TickSizeSchedule.flat(0.00001)))
                    .configureSymbol(1, HftQuoter.Config.of(50_000, 0.02)
                            .withTickSchedule(TickSizeSchedule.flat(0.001)));
            quoter.onTick(0, 1.0850, 1e6, 1L);
            quoter.onTick(1, 155.00, 1e6, 2L);
            await(() -> c.of('N').size() == 4, "four orders");
            assertEquals(1.0850 - 0.0002, c.of('N').get(0).price(), 1e-9);
            assertEquals(155.00 - 0.02, c.of('N').get(2).price(), 1e-9);
            assertEquals(155.00 + 0.02, c.of('N').get(3).price(), 1e-9);
            assertEquals(50_000, c.of('N').get(2).qty());
        }
    }

    // ------------------------------------------------------------------
    // HftQuoter: working orders (D1)
    // ------------------------------------------------------------------

    @Test
    void requotesReplaceTheWorkingOrdersInsteadOfStacking() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, LoopbackVenue.NO_FILLS);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            for (int i = 0; i < 50; i++) {
                double mid = 1.0850 + (i % 7) * 1e-5;
                quoter.onTick(0, mid, 1e6, i);
                // Let the venue answer before the next requote.
                await(() -> !gateway.isPendingChange(quoter.workingBidId(0))
                        && !gateway.isPendingChange(quoter.workingAskId(0))
                        && gateway.state(quoter.workingBidId(0)) != OrderStateMachine.PENDING_NEW
                        && gateway.state(quoter.workingAskId(0))
                                != OrderStateMachine.PENDING_NEW, "venue answers");
            }
            assertEquals(2, c.of('N').size(), "exactly one bid and one offer ever sent");
            assertTrue(quoter.replaces() > 0 && c.of('R').size() == quoter.replaces());
            assertTrue(gateway.isLive(quoter.workingBidId(0)));
            assertTrue(gateway.isLive(quoter.workingAskId(0)));
            // Last requote landed: working prices match the last fair value.
            double lastMid = 1.0850 + (49 % 7) * 1e-5;
            assertEquals(lastMid - 0.0002, gateway.orderPrice(quoter.workingBidId(0)), 1e-12);
            assertEquals(lastMid + 0.0002, gateway.orderPrice(quoter.workingAskId(0)), 1e-12);
            // Open exposure in the gate is exactly one quote per side.
            assertEquals(100_000, gateway.riskGate().openQuantity(0, Side.BUY));
            assertEquals(100_000, gateway.riskGate().openQuantity(0, Side.SELL));
        }
    }

    @Test
    void sidesWaitForTheAckBeforeRequoting() throws Exception {
        // No venue: the first orders stay PENDING_NEW, so later updates defer.
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            quoter.onTick(0, 1.0850, 1e6, 1);
            quoter.onTick(0, 1.0860, 1e6, 2);
            quoter.onTick(0, 1.0870, 1e6, 3);
            await(() -> c.of('N').size() == 2, "first quote");
            assertEquals(4, quoter.deferredSides());
            assertEquals(2, c.msgs.size(), "no stacked orders, no replaces");
        }
    }

    @Test
    void conflationSuppressesNoiseButPassesMovesAndTime() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, LoopbackVenue.NO_FILLS);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.0002)
                    .withConflation(1_000_000_000L, 0.0005)); // 1 s / 5 pips
            long t0 = 1_000_000_000L;
            quoter.onTick(0, 1.0850, 1e6, t0);
            await(() -> gateway.ackCount() == 2, "acks");
            quoter.onTick(0, 1.08501, 1e6, t0 + 1000);      // suppressed
            quoter.onTick(0, 1.0860, 1e6, t0 + 2000);       // passes on move: 2 replaces
            await(() -> c.of('R').size() == 2
                    && !gateway.isPendingChange(quoter.workingBidId(0))
                    && !gateway.isPendingChange(quoter.workingAskId(0)), "replaces");
            quoter.onTick(0, 1.08601, 1e6, t0 + 2_000_000_000L); // passes on time
            await(() -> c.of('R').size() == 4, "second replaces");
            assertEquals(3, quoter.quoteUpdates());
            assertEquals(1, quoter.suppressedUpdates());
            assertEquals(2, c.of('N').size());
        }
    }

    @Test
    void haltedSymbolCountsRejectedSides() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            gate.halt(0, true);
            quoter.onTick(0, 1.0850, 1e6, 1L);
            assertEquals(2, quoter.rejectedSides());
            assertEquals(1, quoter.quoteUpdates());
        }
    }

    @Test
    void pullQuotesCancelsBothSides() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, LoopbackVenue.NO_FILLS);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            quoter.onTick(0, 1.0850, 1e6, 1L);
            await(() -> gateway.ackCount() == 2, "acks");
            quoter.pullQuotes(0, 2L);
            await(() -> gateway.canceledCount() == 2, "cancels");
            assertEquals(2, c.of('C').size());
            assertEquals(0, gateway.riskGate().openQuantity(0, Side.BUY));
            assertEquals(0, gateway.riskGate().openQuantity(0, Side.SELL));
        }
    }

    // ------------------------------------------------------------------
    // HftQuoter: inputs (C1 at the source, D3)
    // ------------------------------------------------------------------

    @Test
    void nanAndNonPositiveInputsNeverReachTheGateway() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            quoter.onTick(0, Double.NaN, 1e6, 1L);
            quoter.onTick(0, 0.0, 1e6, 2L);
            quoter.onTick(0, -1.0, 1e6, 3L);
            quoter.onTick(0, Double.POSITIVE_INFINITY, 1e6, 4L);
            quoter.onQuote(0, Double.NaN, 0, 1.0851, 1e6, 5L);  // one-sided book
            assertEquals(5, quoter.invalidInputs());
            assertEquals(0, quoter.quoteUpdates());
            assertEquals(0, gateway.submittedCount());
            assertTrue(c.msgs.isEmpty());
        }
    }

    @Test
    void quotesOffTheBboMidAndIgnoreTradesOnceABookExists() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            Capture c = wire(gateway, null);
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            quoter.onQuote(0, 1.0846, 1e6, 1.0854, 1e6, 1L);   // mid 1.0850
            quoter.onTick(0, 1.0900, 1e6, 2L);                 // a stray print: ignored
            await(() -> c.of('N').size() == 2, "quote");
            assertEquals(1.0848, c.of('N').get(0).price(), 1e-12);
            assertEquals(1.0852, c.of('N').get(1).price(), 1e-12);
            assertEquals(1, quoter.quoteUpdates());
        }
    }

    @Test
    void crossedBookPullsQuotesAndCrossingSidesAreNotSent() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            Capture c = wire(gateway, LoopbackVenue.NO_FILLS);
            // Heavy skew: long inventory pushes the offer below the best bid.
            HftQuoter quoter = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0001).withSkewPerUnit(1e-9));
            gate.onFill(0, Side.BUY, 1_000_000);                  // skew -0.001
            quoter.onQuote(0, 1.0849, 1e6, 1.0851, 1e6, 1L);      // ask 1.0841 <= bid 1.0849
            await(() -> c.of('N').size() == 1, "bid only");
            assertEquals(Side.BUY, c.of('N').get(0).side());
            assertEquals(1, quoter.crossedSides());

            await(() -> gateway.ackCount() == 1, "ack");
            quoter.onQuote(0, 1.0852, 1e6, 1.0851, 1e6, 2L);      // crossed input
            assertEquals(1, quoter.crossedBooks());
            await(() -> c.of('C').size() == 1, "bid pulled");
        }
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(0, 0.0001));
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(100, 0));
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(100, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> HftQuoter.Config.of(100, 0.0001).withSkewPerUnit(-1));
    }

    @Test
    void withMinMoveIsPurelyMoveGated() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            gateway.start();
            HftQuoter moveGated = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002).withMinMove(0.0005));
            long t0 = 1_000L;
            moveGated.onTick(0, 1.0850, 1e6, t0);
            moveGated.onTick(0, 1.08501, 1e6, t0 + 100_000_000_000L);
            moveGated.onTick(0, 1.0860, 1e6, t0 + 200_000_000_000L);
            assertEquals(2, moveGated.quoteUpdates());
            assertEquals(1, moveGated.suppressedUpdates());

            HftQuoter disabled = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002).withConflation(0, 0.0005));
            disabled.onTick(1, 1.0850, 1e6, t0);
            disabled.onTick(1, 1.08501, 1e6, t0 + 1);
            assertEquals(2, disabled.quoteUpdates());
            assertEquals(0, disabled.suppressedUpdates());
        }
    }

    @Test
    void quoterAndHedgerTickPathsAreAllocationFree() {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 14, gate, false)) {
            gateway.addOrderListener(new LoopbackVenue(gateway, LoopbackVenue.NO_FILLS, 1 << 12));
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.0002)
                    .withSkewPerUnit(1e-10)
                    .withConflation(Long.MAX_VALUE / 4, 0.00005)
                    .withTickSchedule(TickSizeSchedule.flat(0.00001)));
            AutoHedger hedger = new AutoHedger(gateway, 16, Long.MAX_VALUE / 8, 0);
            gate.onFill(0, Side.BUY, 1_000);
            long[] t = {0};
            Runnable ticks = () -> {
                for (int i = 0; i < 200_000; i++) {
                    long ts = t[0]++ * 10_000L;
                    double px = 1.0850 + (i % 100) * 1e-6;
                    quoter.onQuote(0, px - 1e-5, 1e6, px + 1e-5, 1e6, ts);
                    quoter.onTick(0, px, 1e6, ts);
                    hedger.onTick(0, px, 1e6, ts);
                }
            };
            AllocationProbe.assertZeroAllocation(ticks, ticks, 5);
            assertTrue(quoter.quoteUpdates() > 0 && quoter.suppressedUpdates() > 0);
            assertTrue(quoter.replaces() + quoter.deferredSides() > 0);
        }
    }

    // ------------------------------------------------------------------
    // AutoHedger (D2)
    // ------------------------------------------------------------------

    @Test
    void breachSendsAnIocForTheExcessAtTheOppositeTouch() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            Capture c = wire(gateway, LoopbackVenue.NO_FILLS);
            AutoHedger hedger = new AutoHedger(gateway, 16, 500_000, 0);
            hedger.onQuote(0, 1.0849, 1e6, 1.0851, 1e6, 1L);

            gate.onFill(0, Side.BUY, 400_000);
            hedger.onTick(0, 1.0850, 1e6, 2L);
            assertEquals(0, hedger.hedgesSubmitted());

            gate.onFill(0, Side.BUY, 400_000);                 // +800k: 300k over the band
            hedger.onTick(0, 1.0850, 1e6, 3L);
            await(() -> c.of('N').size() == 1, "hedge");
            Msg h = c.of('N').get(0);
            assertEquals(Side.SELL, h.side());
            assertEquals(300_000, h.qty());
            assertEquals(1.0849, h.price(), 0.0, "sell at the best bid");
            assertEquals(OrderListener.FLAG_IOC, h.flags());
            // Unfilled IOC: the venue cancels the remainder, the hedge ends.
            await(() -> !gateway.isLive(h.id()), "IOC done");
            assertEquals(OrderStatus.CANCELED, gateway.status(h.id()));
            assertEquals(0, hedger.hedgesWithoutTouch());
        }
    }

    @Test
    void noSecondHedgeWhileTheFirstIsWorking() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            Capture c = wire(gateway, null);                  // venue never answers
            AutoHedger hedger = new AutoHedger(gateway, 16, 100_000, 1_000L);
            gate.onFill(0, Side.BUY, 500_000);
            long t0 = 5_000_000_000L;
            hedger.onTick(0, 1.0850, 1e6, t0);
            // Far past the time cooldown: still held, the hedge is working.
            hedger.onTick(0, 1.0850, 1e6, t0 + 10_000_000_000L);
            hedger.onTick(0, 1.0850, 1e6, t0 + 20_000_000_000L);
            assertEquals(1, hedger.hedgesSubmitted());
            assertEquals(2, hedger.hedgesHeld());
            assertEquals(1, hedger.hedgesWithoutTouch(), "no BBO: priced at the trade");

            // The venue finally reports a partial fill and cancels the rest.
            long id = hedger.workingHedgeId(0);
            assertTrue(gateway.onAck(id, 0));
            assertTrue(gateway.onFill(id, 150_000, 1.0850, 0));
            assertTrue(gateway.onCanceled(id, 0));
            assertEquals(350_000, gate.position(0));
            hedger.onTick(0, 1.0850, 1e6, t0 + 30_000_000_000L);
            assertEquals(2, hedger.hedgesSubmitted());
            await(() -> c.of('N').size() == 2, "second hedge");
            assertEquals(250_000, c.of('N').get(1).qty(), "hedges the remaining excess only");
        }
    }

    @Test
    void firstHedgeFiresEvenWhenTheClockStartsAtZero() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            AutoHedger hedger = new AutoHedger(gateway, 16, 100_000, 1_000_000_000L);
            gate.onFill(0, Side.BUY, 500_000);
            hedger.onTick(0, 1.0850, 1e6, 0L);
            assertEquals(1, hedger.hedgesSubmitted());
            gate.onFill(1, Side.BUY, 500_000);
            gate.setReferencePrice(1, 1.0850);
            hedger.onTick(1, 1.0850, 1e6, -5_000_000_000L);
            assertEquals(2, hedger.hedgesSubmitted());
        }
    }

    @Test
    void refusedHedgesAreCountedForMonitoring() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            AutoHedger hedger = new AutoHedger(gateway, 16, 100_000, 0);
            gate.onFill(0, Side.BUY, 500_000);
            gate.halt(0, true);
            hedger.onTick(0, 1.0850, 1e6, 1L);
            assertEquals(0, hedger.hedgesSubmitted());
            assertEquals(1, hedger.hedgesRejected());
            assertThrows(IllegalArgumentException.class,
                    () -> new AutoHedger(gateway, 16, 0, 0));
        }
    }
}

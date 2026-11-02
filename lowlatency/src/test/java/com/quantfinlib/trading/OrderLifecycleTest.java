package com.quantfinlib.trading;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.core.Side;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.quantfinlib.trading.HftQuoterAutoHedgerTest.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-trade price validity (C1), open-order exposure (C2), cancel paths and
 * the cancelling kill switch (C3), wired controls (C4), the order state
 * machine (K1), failure containment (B1) and the concurrency races between
 * them (L3).
 */
class OrderLifecycleTest {

    private static HftRiskGate gate() {
        return new HftRiskGate(8).maxOrderQuantity(1_000_000).maxOrderNotional(1e12);
    }

    // ------------------------------------------------------------------
    // C1: price validity
    // ------------------------------------------------------------------

    @Test
    void gateRejectsNanZeroNegativeAndInfinitePrices() {
        HftRiskGate g = gate().priceCollarPct(0.05);
        g.setReferencePrice(0, 100);
        for (double bad : new double[]{Double.NaN, 0.0, -0.0, -1.0, -100,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(HftRiskGate.REJECT_PRICE, g.check(0, Side.BUY, 10, bad), "price " + bad);
            assertEquals(HftRiskGate.REJECT_PRICE, g.check(1, Side.SELL, 10, bad),
                    "no reference: price " + bad);
        }
        assertEquals(14, g.rejectionCount(HftRiskGate.REJECT_PRICE));
        assertEquals(HftRiskGate.OK, g.check(0, Side.BUY, 10, 100));
        assertEquals(HftRiskGate.OK, g.check(1, Side.BUY, 10, Double.MIN_VALUE));
        assertEquals("INVALID_PRICE", HftRiskGate.reasonName(HftRiskGate.REJECT_PRICE));
    }

    @Test
    void gatewayNeverPublishesAnInvalidPrice() {
        try (HftOrderGateway gw = new HftOrderGateway(64, gate(), false)) {
            assertEquals(-HftRiskGate.REJECT_PRICE, gw.submit(0, Side.BUY, 10, Double.NaN, 1));
            assertEquals(0, gw.submittedCount());
            assertEquals(0, gw.riskGate().openQuantity(0, Side.BUY));
        }
    }

    @Test
    void collarCanFailClosedWithoutAReference() {
        HftRiskGate g = gate().priceCollarPct(0.05);
        assertEquals(HftRiskGate.OK, g.check(3, Side.BUY, 10, 100), "default: collar skipped");
        g.requireReferencePrice(true);
        assertEquals(HftRiskGate.REJECT_NO_REFERENCE, g.check(3, Side.BUY, 10, 100));
    }

    // ------------------------------------------------------------------
    // C2: open-order exposure
    // ------------------------------------------------------------------

    @Test
    void restingOrdersCountAgainstThePositionLimit() throws Exception {
        HftRiskGate g = gate().maxPositionQuantity(1_000);
        try (HftOrderGateway gw = new HftOrderGateway(64, g, false)) {
            gw.addOrderListener(new LoopbackVenue(gw));
            gw.start();
            long a = gw.submit(0, Side.BUY, 800, 10, 1);
            assertTrue(a > 0);
            assertEquals(800, g.openQuantity(0, Side.BUY));
            // Fills-only accounting would accept this and leave 1,600 working.
            assertEquals(-HftRiskGate.REJECT_POSITION, gw.submit(0, Side.BUY, 800, 10, 2));
            // Selling is not blocked by working buys.
            assertTrue(gw.submit(0, Side.SELL, 800, 10, 3) > 0);

            // Partial fill moves exposure from open to position, total unchanged.
            await(() -> gw.status(a) == OrderStatus.NEW, "ack");
            assertTrue(gw.onFill(a, 300, 10, 4));
            assertEquals(300, g.position(0));
            assertEquals(500, g.openQuantity(0, Side.BUY));
            assertEquals(OrderStatus.PARTIALLY_FILLED, gw.status(a));
            assertEquals(-HftRiskGate.REJECT_POSITION, gw.submit(0, Side.BUY, 201, 10, 5));
            assertTrue(gw.submit(0, Side.BUY, 200, 10, 6) > 0);

            // Cancel releases the working remainder.
            assertTrue(gw.cancel(a, 7));
            await(() -> gw.status(a) == OrderStatus.CANCELED, "cancel");
            assertEquals(200, g.openQuantity(0, Side.BUY));
            assertEquals(300, gw.filledQuantity(a));
        }
    }

    @Test
    void replaceReservesUpsizesAndReleasesDownsizes() throws Exception {
        HftRiskGate g = gate().maxPositionQuantity(1_000);
        try (HftOrderGateway gw = new HftOrderGateway(64, g, false)) {
            gw.addOrderListener(new LoopbackVenue(gw));
            gw.start();
            long id = gw.submit(0, Side.BUY, 400, 10, 1);
            await(() -> gw.status(id) == OrderStatus.NEW, "ack");
            assertEquals(0, gw.replace(id, 900, 10.5, 2));
            assertEquals(900, g.openQuantity(0, Side.BUY), "upsize reserved on request");
            await(() -> !gw.isPendingChange(id), "replace ack");
            assertEquals(10.5, gw.orderPrice(id));
            assertEquals(-HftRiskGate.REJECT_POSITION, gw.replace(id, 1_200, 10.5, 3));
            assertEquals(0, gw.replace(id, 100, 10.5, 4));
            await(() -> !gw.isPendingChange(id), "replace ack");
            assertEquals(100, g.openQuantity(0, Side.BUY), "downsize released on ack");
            assertEquals(100, gw.leavesQuantity(id));
        }
    }

    // ------------------------------------------------------------------
    // K1: order state machine
    // ------------------------------------------------------------------

    @Test
    void stateMachineTransitions() {
        int p = OrderStateMachine.PENDING_NEW;
        assertEquals(OrderStateMachine.NEW, OrderStateMachine.next(p, OrderStateMachine.EVENT_ACK));
        assertEquals(OrderStateMachine.REJECTED,
                OrderStateMachine.next(p, OrderStateMachine.EVENT_REJECT));
        assertEquals(OrderStateMachine.FILLED,
                OrderStateMachine.next(p, OrderStateMachine.EVENT_FULL_FILL));
        int n = OrderStateMachine.NEW;
        assertEquals(OrderStateMachine.ILLEGAL,
                OrderStateMachine.next(n, OrderStateMachine.EVENT_REJECT));
        assertEquals(OrderStateMachine.PARTIALLY_FILLED,
                OrderStateMachine.next(n, OrderStateMachine.EVENT_PARTIAL_FILL));
        for (int terminal : new int[]{OrderStateMachine.FILLED, OrderStateMachine.CANCELED,
                OrderStateMachine.REJECTED}) {
            for (int e = 0; e <= 4; e++) {
                assertEquals(OrderStateMachine.ILLEGAL, OrderStateMachine.next(terminal, e));
            }
            assertTrue(OrderStateMachine.toStatus(terminal).isTerminal());
        }
    }

    @Test
    void venueReportsDriveTheLifecycle() {
        HftRiskGate g = gate();
        try (HftOrderGateway gw = new HftOrderGateway(64, g, false)) {
            long id = gw.submit(0, Side.SELL, 1_000, 5, 1);
            assertEquals(OrderStatus.PENDING_NEW, gw.status(id));
            assertTrue(gw.onAck(id, 2));
            assertTrue(gw.onFill(id, 400, 5, 3));
            assertEquals(OrderStatus.PARTIALLY_FILLED, gw.status(id));
            assertEquals(600, gw.leavesQuantity(id));
            assertTrue(gw.onFill(id, 600, 5, 4));
            assertEquals(OrderStatus.FILLED, gw.status(id));
            assertEquals(-1_000, g.position(0));
            assertEquals(0, g.openQuantity(0, Side.SELL));
            // A late fill or cancel after the terminal state is refused and counted.
            assertFalse(gw.onFill(id, 1, 5, 5));
            assertFalse(gw.onCanceled(id, 5));
            assertEquals(2, gw.illegalTransitionCount());
            assertEquals(-1_000, g.position(0), "an illegal fill must not move the position");
            // Unknown ids are counted, not applied.
            assertFalse(gw.onAck(999_999, 6));
            assertEquals(1, gw.unknownReportCount());
            assertNull(gw.status(999_999));

            long r = gw.submit(0, Side.BUY, 50, 5, 7);
            assertTrue(gw.onReject(r, 8));
            assertEquals(OrderStatus.REJECTED, gw.status(r));
            assertEquals(0, g.openQuantity(0, Side.BUY));
        }
    }

    @Test
    void ackTimeoutFlagsAndCancelsOnce() throws Exception {
        try (HftOrderGateway gw = new HftOrderGateway(64, gate(), false, 64, null, 1_000)) {
            HftQuoterAutoHedgerTest.Capture c = new HftQuoterAutoHedgerTest.Capture();
            gw.addOrderListener(c);
            gw.start();
            long id = gw.submit(0, Side.BUY, 10, 5, 0);
            assertEquals(0, gw.checkAckTimeouts(500));
            assertEquals(1, gw.checkAckTimeouts(2_000));
            assertEquals(0, gw.checkAckTimeouts(3_000), "flagged once");
            assertEquals(1, gw.ackTimeoutCount());
            await(() -> c.of('C').size() == 1, "cancel sent");
            assertEquals(id, c.of('C').get(0).id());
            assertTrue(gw.isLive(id), "exposure stays reserved until the venue answers");
            assertEquals(10, gw.riskGate().openQuantity(0, Side.BUY));
        }
    }

    @Test
    void fullStateTableRefusesNewOrders() {
        try (HftOrderGateway gw = new HftOrderGateway(64, gate(), false, 4, null, 1_000)) {
            for (int i = 0; i < 4; i++) {
                assertTrue(gw.submit(0, Side.BUY, 1, 5, i) > 0);
            }
            assertEquals(-HftRiskGate.REJECT_TOO_MANY_OPEN, gw.submit(0, Side.BUY, 1, 5, 9));
            assertEquals(4, gw.riskGate().openQuantity(0, Side.BUY));
        }
    }

    // ------------------------------------------------------------------
    // C4: throttle, daily caps, duplicates, intraday limit changes
    // ------------------------------------------------------------------

    @Test
    void throttleIsWiredIntoSubmitButNotCancel() {
        OrderThrottle throttle = new OrderThrottle(1.0, 2);
        try (HftOrderGateway gw = new HftOrderGateway(64, gate(), false, 64, throttle,
                1_000_000_000L)) {
            long a = gw.submit(0, Side.BUY, 1, 5, 0);
            assertTrue(a > 0);
            assertTrue(gw.submit(0, Side.BUY, 2, 5, 0) > 0);
            assertEquals(-HftRiskGate.REJECT_THROTTLED, gw.submit(0, Side.BUY, 3, 5, 0));
            assertEquals(3, gw.riskGate().openQuantity(0, Side.BUY), "throttled order not reserved");
            assertTrue(gw.cancel(a, 0), "cancels pass an exhausted throttle");
            assertTrue(gw.submit(0, Side.BUY, 4, 5, 1_000_000_000L) > 0, "refilled after 1 s");
        }
    }

    @Test
    void dailyCapsAndLossLimit() {
        HftRiskGate g = gate().maxDailyOrders(3).maxDailyNotional(1_000).maxDailyLoss(500);
        try (HftOrderGateway gw = new HftOrderGateway(64, g, false)) {
            assertTrue(gw.submit(0, Side.BUY, 10, 40, 1) > 0);    // 400
            assertTrue(gw.submit(0, Side.SELL, 10, 40, 2) > 0);   // 800
            assertEquals(-HftRiskGate.REJECT_DAILY_NOTIONAL, gw.submit(0, Side.BUY, 10, 30, 3));
            assertTrue(gw.submit(0, Side.BUY, 1, 40, 4) > 0);     // 840, third order
            assertEquals(-HftRiskGate.REJECT_DAILY_ORDERS, gw.submit(0, Side.BUY, 1, 1, 5));
            g.resetDaily();
            g.updateDailyPnl(-499.99);
            assertTrue(gw.submit(0, Side.BUY, 1, 1, 6) > 0);
            g.updateDailyPnl(-500);
            assertEquals(-HftRiskGate.REJECT_DAILY_LOSS, gw.submit(0, Side.BUY, 1, 1, 7));
        }
    }

    @Test
    void duplicateOrdersWithinTheWindowAreRejected() {
        HftRiskGate g = gate().duplicateWindowNanos(1_000);
        try (HftOrderGateway gw = new HftOrderGateway(64, g, false)) {
            assertTrue(gw.submit(0, Side.BUY, 10, 5, 100) > 0);
            assertEquals(-HftRiskGate.REJECT_DUPLICATE, gw.submit(0, Side.BUY, 10, 5, 500));
            assertTrue(gw.submit(0, Side.BUY, 10, 5.01, 600) > 0, "different price");
            assertTrue(gw.submit(0, Side.SELL, 10, 5.01, 700) > 0, "different side");
            assertTrue(gw.submit(0, Side.SELL, 10, 5.01, 1_701) > 0, "outside the window");
        }
    }

    @Test
    void limitsCanBeTightenedFromAnotherThread() throws Exception {
        HftRiskGate g = gate();
        assertEquals(HftRiskGate.OK, g.check(0, Side.BUY, 500, 1));
        Thread ops = new Thread(() -> g.maxOrderQuantity(100));
        ops.start();
        ops.join();
        assertEquals(HftRiskGate.REJECT_QUANTITY, g.check(0, Side.BUY, 500, 1));
    }

    // ------------------------------------------------------------------
    // C3: cancels and the cancelling kill switch
    // ------------------------------------------------------------------

    @Test
    void killSwitchCancelsEveryWorkingOrder() throws Exception {
        HftRiskGate g = gate();
        try (HftOrderGateway gw = new HftOrderGateway(256, g, false)) {
            HftQuoterAutoHedgerTest.Capture c = new HftQuoterAutoHedgerTest.Capture();
            gw.addOrderListener(c);
            gw.addOrderListener(new LoopbackVenue(gw));
            gw.start();
            long[] ids = new long[10];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = gw.submit(i % 3, Side.BUY, 10, 5, i);
            }
            await(() -> gw.ackCount() == 10, "acks");
            g.kill(true);
            await(() -> gw.canceledCount() == 10, "mass cancel");
            assertEquals(1, c.of('A').size());
            assertEquals(OrderListener.ALL_SYMBOLS, c.of('A').get(0).symbolId());
            for (long id : ids) {
                assertEquals(OrderStatus.CANCELED, gw.status(id));
            }
            for (int s = 0; s < 3; s++) {
                assertEquals(0, g.openQuantity(s, Side.BUY));
            }
            assertEquals(1, gw.killCancelAllCount());
            assertEquals(-HftRiskGate.REJECT_KILLED, gw.submit(0, Side.BUY, 1, 5, 99));
        }
    }

    @Test
    void cancelAllBySymbol() throws Exception {
        try (HftOrderGateway gw = new HftOrderGateway(256, gate(), false)) {
            gw.addOrderListener(new LoopbackVenue(gw));
            gw.start();
            long a = gw.submit(0, Side.BUY, 10, 5, 1);
            long b = gw.submit(1, Side.BUY, 10, 5, 2);
            await(() -> gw.ackCount() == 2, "acks");
            assertTrue(gw.cancelAll(0, 3));
            await(() -> gw.status(a) == OrderStatus.CANCELED, "cancel");
            assertEquals(OrderStatus.NEW, gw.status(b));
        }
    }

    /**
     * Kill racing submit: the trading thread submits flat out while another
     * thread kills the gate at a random moment. No new order may reach the
     * venue after its cancel-all, and every accepted order ends terminal.
     */
    @RepeatedTest(5)
    void killRacingSubmitLeavesNothingWorking() throws Exception {
        HftRiskGate g = gate();
        try (HftOrderGateway gw = new HftOrderGateway(1 << 12, g, true)) {
            HftQuoterAutoHedgerTest.Capture c = new HftQuoterAutoHedgerTest.Capture();
            gw.addOrderListener(c);
            gw.addOrderListener(new LoopbackVenue(gw, LoopbackVenue.NO_FILLS, 1 << 16));
            gw.start();
            long killAfterNanos = new SplittableRandom(System.nanoTime()).nextLong(200_000, 3_000_000);
            CountDownLatch go = new CountDownLatch(1);
            Thread killer = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                long t0 = System.nanoTime();
                while (System.nanoTime() - t0 < killAfterNanos) {
                    Thread.onSpinWait();
                }
                g.kill(true);
            });
            killer.start();
            go.countDown();
            List<Long> accepted = new java.util.ArrayList<>();
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < 20_000_000L) {
                long id = gw.submit(0, Side.BUY, 1, 5, System.nanoTime());
                if (id > 0) {
                    accepted.add(id);
                }
            }
            killer.join();
            await(() -> c.of('A').size() == 1, "cancel-all");
            await(() -> accepted.stream().noneMatch(gw::isLive), "everything terminal");
            synchronized (c.msgs) {
                int cancelAllAt = -1;
                for (int i = 0; i < c.msgs.size(); i++) {
                    if (c.msgs.get(i).type() == 'A') {
                        cancelAllAt = i;
                    } else if (cancelAllAt >= 0) {
                        assertTrue(c.msgs.get(i).type() != 'N',
                                "new order delivered after the cancel-all");
                    }
                }
            }
            assertEquals(0, g.openQuantity(0, Side.BUY));
        }
    }

    // ------------------------------------------------------------------
    // B1: containment and health
    // ------------------------------------------------------------------

    @Test
    void throwingVenueListenerIsContainedKillsAndCancels() throws Exception {
        HftRiskGate g = gate();
        try (HftOrderGateway gw = new HftOrderGateway(256, g, false)) {
            HftQuoterAutoHedgerTest.Capture c = new HftQuoterAutoHedgerTest.Capture();
            AtomicBoolean explode = new AtomicBoolean();
            gw.addOrderListener(new OrderListener() {
                @Override
                public void onOrder(long id, int sym, Side side, long qty, double px, long ts) {
                    if (explode.get()) {
                        throw new java.io.UncheckedIOException(new java.io.IOException("socket"));
                    }
                }
            });
            gw.addOrderListener(c);
            gw.start();
            assertTrue(gw.submit(0, Side.BUY, 1, 5, 1) > 0);
            await(() -> c.of('N').size() == 1, "first order");
            assertTrue(gw.isHealthy());
            explode.set(true);
            assertTrue(gw.submit(0, Side.BUY, 1, 5, 2) > 0);
            await(() -> c.of('A').size() == 1, "cancel-all to the surviving listener");
            assertFalse(gw.isHealthy());
            assertTrue(g.isKilled());
            assertEquals(1, gw.listenerErrorCount());
            assertTrue(gw.failure() instanceof java.io.UncheckedIOException);
            assertFalse(gw.isVenueThreadDead(), "venue thread keeps running");
            assertEquals(-HftRiskGate.REJECT_KILLED, gw.submit(0, Side.BUY, 1, 5, 3));
        }
    }

    @Test
    void deadVenueThreadMakesSubmitReturnGatewayDown() throws Exception {
        HftRiskGate g = gate();
        try (HftOrderGateway gw = new HftOrderGateway(256, g, false)) {
            gw.addOrderListener((id, sym, side, qty, px, ts) -> {
                throw new StackOverflowError("simulated fatal error");
            });
            gw.start();
            assertTrue(gw.submit(0, Side.BUY, 1, 5, 1) > 0);
            await(gw::isVenueThreadDead, "venue thread death");
            g.kill(false);
            assertEquals(-HftRiskGate.REJECT_GATEWAY_DOWN, gw.submit(0, Side.BUY, 1, 5, 2));
            assertFalse(gw.isHealthy());
            assertEquals(1, g.rejectionCount(HftRiskGate.REJECT_GATEWAY_DOWN));
        }
    }

    @Test
    void throwingBusListenerIsContainedAndTripsTheFailureAction() throws Exception {
        HftRiskGate g = gate();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 8, false)) {
            int sym = bus.registerSymbol("X");
            AtomicLong survivor = new AtomicLong();
            bus.subscribe(sym, (s, px, sz, ts) -> {
                if (px > 100) {
                    throw new IllegalStateException("strategy bug");
                }
            });
            bus.subscribe(sym, (s, px, sz, ts) -> survivor.incrementAndGet());
            bus.onListenerFailure(() -> g.kill(true));
            bus.start();
            bus.publish(sym, 50, 1, 1);
            bus.publish(sym, 150, 1, 2);
            bus.publish(sym, 60, 1, 3);
            await(() -> survivor.get() == 3, "all ticks reach the healthy listener");
            assertFalse(bus.isHealthy());
            assertEquals(1, bus.listenerErrorCount());
            assertTrue(g.isKilled());
            assertFalse(bus.isConsumerDead());
        }
    }

    // ------------------------------------------------------------------
    // L3: races
    // ------------------------------------------------------------------

    /**
     * check() racing fills: the report thread fills a working order piece by
     * piece while another thread samples position + open buys. Because the
     * position is applied before the reservation is released, the sampled
     * worst-case exposure never drops below the order's full size.
     */
    @Test
    void fillsNeverUnderstateExposureToAConcurrentCheck() throws Exception {
        HftRiskGate g = gate();
        try (HftOrderGateway gw = new HftOrderGateway(64, g, false)) {
            long id = gw.submit(0, Side.BUY, 1_000_000, 5, 1);
            assertTrue(gw.onAck(id, 1));
            AtomicBoolean done = new AtomicBoolean();
            AtomicLong minSeen = new AtomicLong(Long.MAX_VALUE);
            Thread sampler = new Thread(() -> {
                while (!done.get()) {
                    long exposure = g.openQuantity(0, Side.BUY) + g.position(0);
                    minSeen.accumulateAndGet(exposure, Math::min);
                }
            });
            sampler.start();
            Thread reporter = new Thread(() -> {
                for (int i = 0; i < 1_000_000; i++) {
                    gw.onFill(id, 1, 5, i);
                }
            });
            reporter.start();
            reporter.join();
            done.set(true);
            sampler.join();
            assertEquals(1_000_000, g.position(0));
            assertEquals(0, g.openQuantity(0, Side.BUY));
            assertTrue(minSeen.get() >= 1_000_000, "exposure understated: " + minSeen.get());
        }
    }

    /** The order ring wraps many times with mixed message types, in order. */
    @Test
    void orderRingWrapsWithMixedMessageTypes() throws Exception {
        OrderRingBuffer ring = new OrderRingBuffer(8);
        int total = 400_000;
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                byte type = (byte) (i & 3);
                while (!ring.publish(type, 0, i, i & 7, Side.BUY, i, i, i)) {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();
        long[] next = {0};
        boolean[] ok = {true};
        OrderListener check = new OrderListener() {
            void see(long id, int expectType) {
                if (id != next[0] || (next[0] & 3) != expectType) {
                    ok[0] = false;
                }
                next[0]++;
            }

            @Override
            public void onOrder(long id, int s, Side sd, long q, double p, long t) {
                see(id, OrderRingBuffer.NEW);
            }

            @Override
            public void onCancel(long id, int s, long t) {
                see(id, OrderRingBuffer.CANCEL);
            }

            @Override
            public void onReplace(long id, int s, Side sd, long q, double p, long t) {
                see(id, OrderRingBuffer.REPLACE);
            }

            @Override
            public void onCancelAll(int s, long t) {
                // CANCEL_ALL carries the symbol, not the id: use the timestamp.
                see(t, OrderRingBuffer.CANCEL_ALL);
            }
        };
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (next[0] < total && System.nanoTime() < deadline) {
            ring.drainTo(check, 64);
        }
        producer.join(5_000);
        assertEquals(total, next[0]);
        assertTrue(ok[0], "messages out of order or with the wrong type");
    }

    // ------------------------------------------------------------------
    // C5 and B6
    // ------------------------------------------------------------------

    @Test
    void aggregatorFailsClosedOnUnpricedExposureAndCountsOpenOrders() throws Exception {
        HftRiskGate g0 = gate();
        g0.setReferencePrice(0, 100);
        try (GlobalRiskAggregator agg = GlobalRiskAggregator.start(List.of(g0), 100_000, 0.5,
                100_000)) {
            g0.reserve(0, Side.BUY, 500);                 // 50k working
            g0.onFill(0, Side.BUY, 400);                  // worst case 900 * 100 = 90k
            await(() -> agg.lastGrossNotional() == 90_000, "sweep");
            assertFalse(agg.isTripped());
            g0.reserve(0, Side.BUY, 200);                 // 110k worst case: open orders trip it
            await(agg::isTripped, "trip on open exposure");
            g0.release(0, Side.BUY, 700);
            g0.onFill(0, Side.SELL, 400);
            await(() -> !agg.isTripped(), "recover");

            g0.onFill(5, Side.BUY, 1);                    // symbol 5 has no reference price
            await(agg::isTripped, "fail closed on an unpriced position");
            assertEquals(1, agg.unpricedSymbols());
            assertTrue(g0.isKilled());
        }
    }

    @Test
    void shardedPublishReportsMissesPerShardAndRefusesBeforeStart() {
        try (ShardedTradingEngine eng = new ShardedTradingEngine(2, 2, 16, 4, false,
                s -> gate())) {
            int h = eng.registerSymbol("X", 0, 1);
            assertThrows(IllegalStateException.class, () -> eng.publish(h, 1, 1, 1));
            // Start only the routing (shards' consumers are started too, but a
            // 2-slot ring on a parked consumer can still fill): fill shard 1.
            eng.start();
            eng.bus(1).stop();
            long mask = 0;
            for (int i = 0; i < 10 && (mask & 2) == 0; i++) {
                mask = eng.publishMissMask(h, 1, 1, i);
            }
            assertEquals(2, mask & 2, "shard 1 reported as missing the tick");
        }
    }
}

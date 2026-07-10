package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HftOrderPathTest {

    // ---- Risk gate --------------------------------------------------------

    @Test
    void riskGatePositionsAreSafeAcrossThreads() throws Exception {
        // The production wiring is multi-threaded (fills from the venue-ack
        // thread, reads from the quoter on the bus consumer thread): fills
        // must be atomic and visible. A plain long[] += would both lose
        // updates under contention and let the JIT serve stale positions.
        HftRiskGate gate = new HftRiskGate(4);
        int fillsPerThread = 100_000;
        Thread buyer = new Thread(() -> {
            for (int i = 0; i < fillsPerThread; i++) {
                gate.onFill(0, Side.BUY, 3);
            }
        });
        Thread anotherBuyer = new Thread(() -> {
            for (int i = 0; i < fillsPerThread; i++) {
                gate.onFill(0, Side.BUY, 2);
            }
        });
        buyer.start();
        anotherBuyer.start();
        buyer.join();
        anotherBuyer.join();
        // Lost updates would land anywhere below the exact sum.
        assertEquals(fillsPerThread * 5L, gate.position(0));
        // Cross-thread halt is visible to the checking side.
        Thread ops = new Thread(() -> gate.halt(1, true));
        ops.start();
        ops.join();
        assertEquals(HftRiskGate.REJECT_HALTED, gate.check(1, Side.BUY, 1, 100));
    }

    @Test
    void riskGateEnforcesEveryLimitWithReasonCodes() {
        HftRiskGate gate = new HftRiskGate(4)
                .maxOrderQuantity(1_000)
                .maxOrderNotional(50_000)
                .maxPositionQuantity(2_000)
                .priceCollarPct(0.02);
        gate.setReferencePrice(0, 100);

        assertEquals(HftRiskGate.OK, gate.check(0, Side.BUY, 400, 100));
        assertEquals(HftRiskGate.REJECT_QUANTITY, gate.check(0, Side.BUY, 5_000, 100));
        assertEquals(HftRiskGate.REJECT_QUANTITY, gate.check(0, Side.BUY, 0, 100));
        assertEquals(HftRiskGate.REJECT_NOTIONAL, gate.check(0, Side.BUY, 900, 100)); // 90k > 50k
        assertEquals(HftRiskGate.REJECT_PRICE_COLLAR, gate.check(0, Side.BUY, 100, 103));

        // Position limit: build up to the cap via fills, then reject the breach.
        gate.onFill(0, Side.BUY, 1_900);
        assertEquals(HftRiskGate.REJECT_POSITION, gate.check(0, Side.BUY, 200, 100));
        assertEquals(HftRiskGate.OK, gate.check(0, Side.SELL, 200, 100));   // reducing is fine
        assertEquals(1_900, gate.position(0));

        // The SHORT side of the cap: |position| is what the limit bounds.
        // Dropping the abs() would leave selling UNBOUNDED and still pass
        // every long-side assertion above — these pin the short side.
        // (A single flip-through order is unreachable here: the quantity
        // and notional gates fire first, by design — so build the short
        // through fills, which bypass order-level caps.)
        gate.onFill(0, Side.SELL, 3_800);                     // now short 1,900
        assertEquals(-1_900, gate.position(0));
        assertEquals(HftRiskGate.REJECT_POSITION, gate.check(0, Side.SELL, 200, 100),
                "a short breach is a breach");
        assertEquals(HftRiskGate.OK, gate.check(0, Side.BUY, 200, 100), "covering is fine");
        gate.onFill(0, Side.BUY, 3_800);                      // back to +1,900 for the rest

        gate.halt(0, true);
        assertEquals(HftRiskGate.REJECT_HALTED, gate.check(0, Side.SELL, 1, 100));
        gate.halt(0, false);

        assertEquals(2, gate.rejectionCount(HftRiskGate.REJECT_QUANTITY));
        assertEquals(1, gate.rejectionCount(HftRiskGate.REJECT_PRICE_COLLAR));
        assertEquals("PRICE_COLLAR", HftRiskGate.reasonName(HftRiskGate.REJECT_PRICE_COLLAR));
        // No reference price on symbol 1: collar check skipped (notional kept small).
        assertEquals(HftRiskGate.OK, gate.check(1, Side.BUY, 10, 999));
    }

    // ---- Order ring --------------------------------------------------------

    @Test
    void orderRingPreservesFifoAcrossThreads() throws Exception {
        OrderRingBuffer ring = new OrderRingBuffer(512);
        int total = 100_000;
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                while (!ring.publish(i, 0, (i & 1) == 0 ? Side.BUY : Side.SELL,
                        i, 1.0 + i, i)) {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();

        long[] received = {0};
        boolean[] ordered = {true};
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (received[0] < total && System.nanoTime() < deadline) {
            ring.drainTo((id, sym, side, qty, px, ts) -> {
                if (id != received[0] || qty != received[0]) {
                    ordered[0] = false;
                }
                received[0]++;
            }, 1024);
        }
        producer.join(5_000);
        assertEquals(total, received[0]);
        assertTrue(ordered[0], "orders arrived out of sequence");
    }

    // ---- Gateway -----------------------------------------------------------

    @Test
    void gatewayDeliversAcceptedOrdersAndEncodesRejections() throws Exception {
        HftRiskGate gate = new HftRiskGate(4).maxOrderQuantity(1_000);
        try (HftOrderGateway gw = new HftOrderGateway(256, gate, false)) {
            List<Long> venueOrders = new ArrayList<>();
            AtomicLong lastQty = new AtomicLong();
            gw.addOrderListener((id, sym, side, qty, px, ts) -> {
                synchronized (venueOrders) {
                    venueOrders.add(id);
                }
                lastQty.set(qty);
            });
            gw.start();

            long id1 = gw.submit(0, Side.BUY, 500, 100, 1);
            long id2 = gw.submit(0, Side.SELL, 700, 100, 2);
            assertTrue(id1 > 0 && id2 == id1 + 1);

            long rejected = gw.submit(0, Side.BUY, 9_999, 100, 3);
            assertEquals(-HftRiskGate.REJECT_QUANTITY, rejected);

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (gw.deliveredCount() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(2, gw.deliveredCount());
            assertEquals(2, gw.submittedCount());
            synchronized (venueOrders) {
                assertEquals(List.of(id1, id2), venueOrders);
            }
            assertEquals(700, lastQty.get());
        }
    }

    @Test
    void gatewayStopDrainsPendingOrders() {
        HftOrderGateway gw = new HftOrderGateway(1024, new HftRiskGate(2), false);
        AtomicLong seen = new AtomicLong();
        gw.addOrderListener((id, sym, side, qty, px, ts) -> seen.incrementAndGet());
        gw.start();
        for (int i = 0; i < 300; i++) {
            assertTrue(gw.submit(0, Side.BUY, 10, 1.0, i) > 0);
        }
        gw.stop();   // must drain before returning
        assertEquals(300, seen.get());
    }

    // ---- The claim itself: zero allocation on the hot path -----------------

    @Test
    void submitPathAllocatesNothingSteadyState() throws Exception {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported());
        mx.setThreadAllocatedMemoryEnabled(true);

        HftRiskGate gate = new HftRiskGate(4).maxOrderQuantity(1_000_000).priceCollarPct(0.10);
        gate.setReferencePrice(0, 100);
        try (HftOrderGateway gw = new HftOrderGateway(1 << 12, gate, false)) {
            gw.addOrderListener((id, sym, side, qty, px, ts) -> { });
            gw.start();

            // Warm up (JIT compilation may allocate).
            for (int i = 0; i < 200_000; i++) {
                while (gw.submit(0, Side.BUY, 100, 100, i) <= 0) {
                    Thread.onSpinWait();
                }
            }
            long tid = Thread.currentThread().threadId();
            long before = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 500_000; i++) {
                while (gw.submit(0, Side.BUY, 100, 100, i) <= 0) {
                    Thread.onSpinWait();
                }
            }
            long allocated = mx.getThreadAllocatedBytes(tid) - before;
            // 500k submits: allow small measurement noise, but no per-order allocation.
            assertTrue(allocated < 100_000,
                    "hot path allocated " + allocated + " bytes over 500k submits");
        }
    }
}

package com.quantfinlib.marketdata;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class L3BookBuilderTest {

    private static final int LOCATE = 42;
    private static final int MIN = 1_700_000;   // $170.0000 in 0.0001 ticks
    private static final int MAX = 1_800_000;

    private static L3BookBuilder book() {
        return new L3BookBuilder(LOCATE, MIN, MAX, 1 << 12);
    }

    @Test
    void attributedAddsBookExactlyLikePlainAdds() {
        // On real TotalView a large share of adds are type 'F' (add with
        // MPID attribution). Dropping that dispatch case would silently
        // ignore them and corrupt the whole book — this pins the path,
        // which needs a hand-crafted frame (no encoder produces F).
        assertEquals(40, ItchCodec.length(ItchCodec.ADD_MPID));
        L3BookBuilder b = book();
        byte[] buf = new byte[64];
        ItchCodec.encodeAdd(buf, 0, LOCATE, 1L, 7, ItchCodec.BUY, 100,
                ItchCodec.packStock("TEST"), 1_750_000);
        buf[0] = ItchCodec.ADD_MPID;                   // same layout + 4 MPID bytes
        buf[36] = 'M';
        buf[37] = 'P';
        buf[38] = 'I';
        buf[39] = 'D';
        assertEquals(40, b.onMessage(buf, 0), "consumed as a 40-byte F frame");
        assertEquals(100, b.openQuantity(7), "the attributed add is IN the book");
        assertEquals(1_750_000, b.bestBidTick());
    }

    // ------------------------------------------------------------------
    // Book semantics
    // ------------------------------------------------------------------

    @Test
    void addsBuildDepthAndBestPrices() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        b.onAdd(2, Side.BUY, 200, 1_750_000);
        b.onAdd(3, Side.BUY, 50, 1_749_900);
        b.onAdd(4, Side.SELL, 80, 1_750_100);
        b.onAdd(5, Side.SELL, 20, 1_750_300);

        assertEquals(1_750_000, b.bestBidTick());
        assertEquals(300, b.bestBidSize());
        assertEquals(1_750_100, b.bestAskTick());
        assertEquals(80, b.bestAskSize());
        assertEquals(50, b.qtyAtTick(Side.BUY, 1_749_900));

        int[] ticks = new int[8];
        long[] qtys = new long[8];
        assertEquals(2, b.snapshot(Side.BUY, ticks, qtys));
        assertEquals(1_750_000, ticks[0]);
        assertEquals(300, qtys[0]);
        assertEquals(1_749_900, ticks[1]);
        assertEquals(2, b.snapshot(Side.SELL, ticks, qtys));
        assertEquals(1_750_100, ticks[0]);
    }

    @Test
    void executionsConsumeAndRemove() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.SELL, 100, 1_750_100);
        b.onExecute(1, 40);
        assertEquals(60, b.bestAskSize());
        assertEquals(60, b.openQuantity(1));
        b.onExecute(1, 60);
        assertEquals(0, b.openQuantity(1));
        assertEquals(Integer.MAX_VALUE, b.bestAskTick());
        assertEquals(0, b.restingOrders());
    }

    @Test
    void cancelReducesInPlaceAndDeleteRemoves() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        b.onAdd(2, Side.BUY, 50, 1_750_000);
        b.onCancel(1, 30);
        assertEquals(70, b.openQuantity(1));
        assertEquals(120, b.bestBidSize());
        b.onDelete(1);
        assertEquals(0, b.openQuantity(1));
        assertEquals(50, b.bestBidSize());
        // Over-cancel clamps, never goes negative.
        b.onCancel(2, 500);
        assertEquals(0, b.restingOrders());
        assertEquals(Integer.MIN_VALUE, b.bestBidTick());
    }

    @Test
    void replaceMovesPriceAndLosesPriority() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.SELL, 100, 1_750_100);
        b.onAdd(2, Side.SELL, 60, 1_750_200);
        b.onReplace(1, 11, 100, 1_750_200);
        assertEquals(0, b.openQuantity(1));
        assertEquals(100, b.openQuantity(11));
        assertEquals(1_750_200, b.bestAskTick());
        assertEquals(160, b.bestAskSize());
        // Priority lost: an execution at the level consumes ref 2 first.
        b.onExecute(2, 60);
        assertEquals(100, b.bestAskSize());
        assertEquals(100, b.openQuantity(11));
    }

    @Test
    void bestPricesAdvanceAcrossEmptiedLevels() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 10, 1_750_000);
        b.onAdd(2, Side.BUY, 20, 1_748_000);
        b.onAdd(3, Side.BUY, 30, 1_745_000);
        b.onDelete(1);
        assertEquals(1_748_000, b.bestBidTick());
        b.onDelete(2);
        assertEquals(1_745_000, b.bestBidTick());
    }

    @Test
    void unknownRefsAndOffBandAddsAreCountedNotThrown() {
        L3BookBuilder b = book();
        b.onExecute(999, 10);
        b.onDelete(998);
        b.onCancel(997, 5);
        b.onReplace(996, 995, 10, 1_750_000);
        assertEquals(4, b.unknownRefCount());
        assertFalse(b.onAdd(1, Side.BUY, 100, MIN - 1));
        assertFalse(b.onAdd(2, Side.BUY, 100, MAX + 1));
        assertEquals(2, b.outOfBandCount());
        assertEquals(0, b.restingOrders());
    }

    // ------------------------------------------------------------------
    // Queue-position tracking
    // ------------------------------------------------------------------

    @Test
    void queuePositionTracksExecutionsCancelsAndDeletesAhead() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);   // ahead
        b.onAdd(2, Side.BUY, 50, 1_750_000);    // ahead
        b.onAdd(3, Side.BUY, 75, 1_750_000);    // ours
        b.onAdd(4, Side.BUY, 40, 1_750_000);    // behind
        assertTrue(b.track(3));
        assertEquals(150, b.sharesAhead(3));

        b.onCancel(1, 30);                       // ahead shrinks in place
        assertEquals(120, b.sharesAhead(3));
        b.onExecute(1, 70);                      // head fully executes
        assertEquals(50, b.sharesAhead(3));
        b.onDelete(4);                           // behind us: no effect
        assertEquals(50, b.sharesAhead(3));
        b.onExecute(2, 50);                      // remaining ahead executes
        assertEquals(0, b.sharesAhead(3));
        b.onExecute(3, 75);                      // we fill: tracking ends
        assertEquals(-1, b.sharesAhead(3));
    }

    @Test
    void ordersJoiningBehindNeverChangeSharesAhead() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.SELL, 100, 1_750_100);
        b.onAdd(2, Side.SELL, 60, 1_750_100);
        assertTrue(b.track(2));
        assertEquals(100, b.sharesAhead(2));
        b.onAdd(3, Side.SELL, 500, 1_750_100);   // joins behind
        assertEquals(100, b.sharesAhead(2));
        b.onDelete(3);                            // behind: still no effect
        assertEquals(100, b.sharesAhead(2));
        // Same-price activity on the OTHER side must not interfere either.
        b.onAdd(4, Side.BUY, 20, 1_749_000);
        b.onDelete(4);
        assertEquals(100, b.sharesAhead(2));
    }

    @Test
    void replaceOfAnAheadOrderCreditsItsFullRemainder() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        b.onAdd(2, Side.BUY, 50, 1_750_000);
        assertTrue(b.track(2));
        assertEquals(100, b.sharesAhead(2));
        // Ahead order replaces away to a new price: it leaves our queue.
        b.onReplace(1, 11, 100, 1_749_000);
        assertEquals(0, b.sharesAhead(2));
        // The replacement itself, being a new order elsewhere, is irrelevant.
        assertEquals(100, b.openQuantity(11));
    }

    @Test
    void trackingOwnReplacedOrderEnds() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        assertTrue(b.track(1));
        b.onReplace(1, 2, 100, 1_750_000);
        assertEquals(-1, b.sharesAhead(1));      // old ref gone
        assertTrue(b.track(2));                  // re-track the new ref
        assertEquals(0, b.sharesAhead(2));
    }

    @Test
    void trackMidQueueInitializesFromAWalk() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.SELL, 10, 1_750_100);
        b.onAdd(2, Side.SELL, 20, 1_750_100);
        b.onAdd(3, Side.SELL, 30, 1_750_100);
        b.onAdd(4, Side.SELL, 40, 1_750_100);
        assertTrue(b.track(3));                  // tracked long after joining
        assertEquals(30, b.sharesAhead(3));
        assertFalse(b.track(999));               // unknown ref
    }

    // ------------------------------------------------------------------
    // Wire dispatch
    // ------------------------------------------------------------------

    @Test
    void wireMessagesProduceTheSameBookAsDirectCalls() {
        L3BookBuilder wire = book();
        L3BookBuilder direct = book();
        long stock = ItchCodec.packStock("TEST");
        byte[] buf = new byte[64];

        ItchCodec.encodeAdd(buf, 0, LOCATE, 1L, 1, ItchCodec.BUY, 100, stock, 1_750_000);
        assertEquals(36, wire.onMessage(buf, 0));
        direct.onAdd(1, Side.BUY, 100, 1_750_000);

        ItchCodec.encodeAdd(buf, 0, LOCATE, 2L, 2, ItchCodec.SELL, 80, stock, 1_750_100);
        wire.onMessage(buf, 0);
        direct.onAdd(2, Side.SELL, 80, 1_750_100);

        ItchCodec.encodeExecuted(buf, 0, LOCATE, 3L, 2, 30, 1L);
        wire.onMessage(buf, 0);
        direct.onExecute(2, 30);

        ItchCodec.encodeReplace(buf, 0, LOCATE, 4L, 1, 11, 60, 1_749_900);
        wire.onMessage(buf, 0);
        direct.onReplace(1, 11, 60, 1_749_900);

        ItchCodec.encodeCancel(buf, 0, LOCATE, 5L, 11, 10);
        wire.onMessage(buf, 0);
        direct.onCancel(11, 10);

        ItchCodec.encodeTrade(buf, 0, LOCATE, 6L, 0, ItchCodec.BUY, 25, stock, 1_750_050, 2L);
        wire.onMessage(buf, 0);
        direct.onTrade(1_750_050);

        assertEquals(direct.bestBidTick(), wire.bestBidTick());
        assertEquals(direct.bestBidSize(), wire.bestBidSize());
        assertEquals(direct.bestAskTick(), wire.bestAskTick());
        assertEquals(direct.bestAskSize(), wire.bestAskSize());
        assertEquals(direct.restingOrders(), wire.restingOrders());
        assertEquals(direct.lastTradeTick(), wire.lastTradeTick());
        assertEquals(1_750_050, wire.lastTradeTick());
    }

    @Test
    void messagesForOtherLocatesAreIgnored() {
        L3BookBuilder b = book();
        byte[] buf = new byte[64];
        ItchCodec.encodeAdd(buf, 0, LOCATE + 1, 1L, 1, ItchCodec.BUY, 100,
                ItchCodec.packStock("OTHR"), 1_750_000);
        assertEquals(0, b.onMessage(buf, 0));
        assertEquals(0, b.restingOrders());
    }

    // ------------------------------------------------------------------
    // Zero allocation
    // ------------------------------------------------------------------

    @Test
    void steadyStateEventStreamIsAllocationFree() {
        L3BookBuilder b = book();
        long[] ring = new long[1024];
        // state[0] = ring size, state[1] = next ref: caller-owned so churn
        // mutates in place — no per-call allocation, no shared statics.
        long[] state = {0, 1};
        SplittableRandom rnd = new SplittableRandom(7);
        for (int i = 0; i < 200_000; i++) {          // JIT warm-up
            churn(b, rnd, ring, state);
        }
        b.track(ring[0]);                             // keep tracking hot too
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            churn(b, rnd, ring, state);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "book building allocated " + allocated + " bytes");
        assertTrue(b.executeCount() > 0 && b.deleteCount() > 0,
                "the loop must exercise executes and deletes");
    }

    @Test
    void wireDecodePathIsAllocationFree() {
        // The full onMessage path: ItchCodec flyweight decode + book apply.
        L3BookBuilder b = book();
        long stock = ItchCodec.packStock("TEST");
        byte[] add = new byte[64];
        byte[] exec = new byte[64];
        byte[] del = new byte[64];
        for (int i = 0; i < 100_000; i++) {           // warm-up
            wireCycle(b, add, exec, del, stock, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 100_000; i < 400_000; i++) {
            wireCycle(b, add, exec, del, stock, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "wire decode allocated " + allocated + " bytes");
        assertEquals(0, b.restingOrders());
    }

    /** Encode + apply one add/execute/delete round on the wire path. */
    private static void wireCycle(L3BookBuilder b, byte[] add, byte[] exec, byte[] del,
                                  long stock, int i) {
        long ref = i + 1;
        int tick = MIN + (i % (MAX - MIN + 1));
        ItchCodec.encodeAdd(add, 0, LOCATE, i, ref,
                (i & 1) == 0 ? ItchCodec.BUY : ItchCodec.SELL, 100, stock, tick);
        b.onMessage(add, 0);
        ItchCodec.encodeExecuted(exec, 0, LOCATE, i, ref, 40, i);
        b.onMessage(exec, 0);
        ItchCodec.encodeDelete(del, 0, LOCATE, i, ref);
        b.onMessage(del, 0);
    }

    @Test
    void duplicateAddsAreRejectedNotCorrupting() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        assertFalse(b.onAdd(1, Side.BUY, 999, 1_751_000), "replayed ref must be dropped");
        assertEquals(1, b.duplicateRefCount());
        assertEquals(100, b.openQuantity(1));
        assertEquals(1, b.restingOrders());
        // The single delete fully removes it: no phantom second node.
        b.onDelete(1);
        assertEquals(0, b.restingOrders());
        assertEquals(0, b.openQuantity(1));
    }

    @Test
    void trackingIsIdempotent() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.BUY, 100, 1_750_000);
        b.onAdd(2, Side.BUY, 50, 1_750_000);
        assertTrue(b.track(2));
        assertTrue(b.track(2), "a retried track must be a no-op, not a duplicate row");
        b.onExecute(1, 100);
        assertEquals(0, b.sharesAhead(2));
        b.onExecute(2, 50);                           // we fill: tracking ends fully
        assertEquals(-1, b.sharesAhead(2));
        // No leaked rows: all 64 tracking slots must be usable afterwards.
        for (int i = 0; i < 64; i++) {
            b.onAdd(100 + i, Side.SELL, 10, 1_750_100);
            assertTrue(b.track(100 + i), "slot " + i + " must be free");
        }
    }

    @Test
    void replaceToOffBandPriceDropsTheOrderAndCountsIt() {
        L3BookBuilder b = book();
        b.onAdd(1, Side.SELL, 100, 1_750_100);
        long before = b.outOfBandCount();
        b.onReplace(1, 2, 100, MAX + 1_000);          // re-price outside the band
        assertEquals(0, b.openQuantity(1), "the original must be gone");
        assertEquals(0, b.openQuantity(2), "the off-band replacement is invisible");
        assertEquals(before + 1, b.outOfBandCount());
        assertEquals(1, b.replaceCount());
        assertEquals(0, b.restingOrders());
    }

    /** One mixed feed event; mutates {@code state} = {ringSize, nextRef} in place. */
    private static void churn(L3BookBuilder b, SplittableRandom rnd, long[] ring,
                              long[] state) {
        int ringSize = (int) state[0];
        long nextRef = state[1];
        int op = rnd.nextInt(10);
        if (op < 5 || ringSize == 0) {
            long ref = nextRef++;
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            if (b.onAdd(ref, side, 1 + rnd.nextInt(500), MIN + rnd.nextInt(MAX - MIN + 1))
                    && ringSize < ring.length) {
                ring[ringSize++] = ref;
            }
        } else {
            int pick = rnd.nextInt(ringSize);
            long ref = ring[pick];
            switch (op) {
                case 5, 6 -> b.onExecute(ref, 1 + rnd.nextInt(200));
                case 7 -> b.onCancel(ref, 1 + rnd.nextInt(200));
                case 8 -> {
                    b.onDelete(ref);
                    ring[pick] = ring[--ringSize];
                }
                default -> {
                    long newRef = nextRef++;
                    b.onReplace(ref, newRef, 1 + rnd.nextInt(500),
                            MIN + rnd.nextInt(MAX - MIN + 1));
                    ring[pick] = newRef;
                }
            }
            if (op == 5 || op == 6 || op == 7) {
                if (b.openQuantity(ref) == 0) {
                    ring[pick] = ring[--ringSize];
                }
            }
        }
        state[0] = ringSize;
        state[1] = nextRef;
    }
}

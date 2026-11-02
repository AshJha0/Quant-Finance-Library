package com.quantfinlib.alloc;

import com.quantfinlib.core.Side;
import com.quantfinlib.feed.BinanceTradeParser;
import com.quantfinlib.fix.FixExecReportView;
import com.quantfinlib.fix.FixMarketDataView;
import com.quantfinlib.fix.FixMessage;
import com.quantfinlib.fix.FixOrderEncoder;
import com.quantfinlib.fix.FixVersion;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.RingBuffer;
import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.marketdata.TickRingBuffer;
import com.quantfinlib.orderbook.AuctionBook;
import com.quantfinlib.orderbook.BookPrimitives;
import com.quantfinlib.orderbook.HftOrderBook;
import com.quantfinlib.sbe.BinaryMarketDataClient;
import com.quantfinlib.sbe.TradeFlyweight;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.trading.LoopbackVenue;
import com.quantfinlib.trading.OrderListener;
import com.quantfinlib.trading.OrderRingBuffer;
import com.quantfinlib.trading.PositionLedger;
import com.quantfinlib.util.HiccupMonitor;
import com.quantfinlib.util.IdleStrategy;
import com.quantfinlib.util.LatencyRecorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.LockSupport;

import static com.quantfinlib.alloc.AllocationProbe.assertAllocates;
import static com.quantfinlib.alloc.AllocationProbe.assertAllocationFree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One test per "allocation-free" / "garbage-free" / "no allocation" claim in
 * the lowlatency javadoc. Inputs are built outside the measured operation;
 * the operation runs the claimed call in steady state and returns a value
 * derived from its result. Paths that run on a library-owned thread (the
 * hiccup sampler, the binary market-data reader) are measured on that thread.
 */
@Tag("allocation")
class LaneAllocationClaimsTest {

    private static final char SOH = 1;

    /** Defeats escape analysis in the probe sanity test. */
    private static volatile long[] escaped;

    private static byte[] fix(String pipeDelimited) {
        return pipeDelimited.replace('|', SOH).getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------
    // Probe sanity and the one documented allocating parser
    // ------------------------------------------------------------------

    @Test
    void probeSeesAllocation() throws Exception {
        assertAllocates(i -> (escaped = new long[1 + (i & 3)]).length);
    }

    @Test
    void binanceParserAllocatesPerMessageAsDocumented() throws Exception {
        // feed/BinanceTradeParser: the javadoc says it allocates the result
        // and short-lived strings; it is not a garbage-free path.
        BinanceTradeParser p = new BinanceTradeParser();
        String msg = "{\"e\":\"trade\",\"s\":\"BTCUSDT\",\"p\":\"50000.1\",\"q\":\"0.05\",\"T\":1690000000000}";
        assertAllocates(i -> p.parseTrade(msg).price());
    }

    // ------------------------------------------------------------------
    // FIX
    // ------------------------------------------------------------------

    @Test
    void fixExecReportViewWrapAndFieldReads() throws Exception {
        byte[] bytes = FixMessage.builder("8")
                .field(FixMessage.ORDER_ID, "V-1")
                .field(FixMessage.EXEC_ID, "E-1")
                .field(FixMessage.EXEC_TYPE, "F")
                .field(FixMessage.ORD_STATUS, "2")
                .field(FixMessage.CL_ORD_ID, "42")
                .field(FixMessage.SYMBOL, "EURUSD")
                .field(FixMessage.SIDE, "1")
                .field(FixMessage.LAST_QTY, "100")
                .field(FixMessage.LAST_PX, "1.08505")
                .field(FixMessage.CUM_QTY, "100")
                .field(FixMessage.LEAVES_QTY, "0")
                .encode("VENUE", "QFL", 7, "20260706-12:00:00.000");
        byte[] eurusd = "EURUSD".getBytes(StandardCharsets.US_ASCII);
        FixExecReportView v = new FixExecReportView();
        assertTrue(v.wrap(bytes, 0, bytes.length));
        assertEquals(108505, v.lastPxMantissa());
        assertAllocationFree("FixExecReportView: garbage-free wrap and field reads", i -> {
            if (!v.wrap(bytes, 0, bytes.length) || !v.symbolEquals(eurusd)) {
                throw new AssertionError("wrap failed");
            }
            return v.clOrdId() + v.execType() + v.ordStatus() + v.side() + v.lastQty()
                    + v.cumQty() + v.leavesQty() + v.lastPxMantissa() + v.lastPxDecimals()
                    + v.avgPxMantissa() + v.execIdLength() + v.orderIdLength()
                    + (v.possDup() ? 1 : 0) + v.byteAt(0);
        });
    }

    @Test
    void fixMarketDataViewWrapAndEntryReads() throws Exception {
        byte[] m44 = fix("8=FIX.4.4|9=120|35=W|55=EURUSD|268=4|"
                + "269=0|270=1.08500|271=1000000|269=0|270=1.08498|271=5000000|"
                + "269=1|270=1.08502|271=1000000|269=1|270=1.08504|271=5000000|10=123|");
        byte[] m42 = fix("8=FIX.4.2|9=80|35=X|268=2|"
                + "279=0|269=0|55=EURUSD|270=1.08500|271=1000000|"
                + "279=2|269=1|55=EURUSD|270=1.08502|271=1000000|10=123|");
        byte[] eurusd = "EURUSD".getBytes(StandardCharsets.US_ASCII);
        FixMarketDataView v = new FixMarketDataView();
        assertTrue(v.wrap(m44, 0, m44.length));
        assertTrue(v.wrap(m42, 0, m42.length));
        assertEquals(2, v.entryCount());
        assertAllocationFree("FixMarketDataView: garbage-free wrap (dictionary tables picked per wrap)",
                i -> {
                    byte[] m = (i & 1) == 0 ? m44 : m42;
                    if (!v.wrap(m, 0, m.length)) {
                        throw new AssertionError("wrap failed");
                    }
                    long acc = v.entryCount() + (v.isSnapshot() ? 1 : 0) + v.declaredEntries();
                    for (int e = 0; e < v.entryCount(); e++) {
                        acc += v.pxMantissa(e) + v.pxDecimals(e) + v.size(e) + v.type(e)
                                + v.action(e);
                    }
                    return acc + v.price(0) + (v.symbolEquals(eurusd) ? 1 : 0);
                });
    }

    @Test
    void fixOrderEncoderEveryMessageEveryVersion() throws Exception {
        for (FixVersion version : FixVersion.values()) {
            FixOrderEncoder enc = new FixOrderEncoder("QFL", "VENUE", 4, 512, version)
                    .registerSymbol(0, "EURUSD");
            ByteBuffer heap = ByteBuffer.allocate(512);
            ByteBuffer direct = ByteBuffer.allocateDirect(512);
            assertAllocationFree("FixOrderEncoder (" + version
                    + "): garbage-free NewOrderSingle/cancel/replace into the reused buffer + writeTo", i -> {
                long t = 1_800_000_000_000L + i;
                Side s = (i & 1) == 0 ? Side.BUY : Side.SELL;
                int n;
                switch (i & 3) {
                    case 0 -> n = enc.encodeLimit(i, i + 1, 0, s, 1_000_000, 108_505 + (i & 15), 5, t);
                    case 1 -> n = enc.encodeMarket(i, i + 1, 0, s, 1_000_000, t);
                    case 2 -> n = enc.encodeCancel(i, i + 1, i, 0, s, 1_000_000, t);
                    default -> n = enc.encodeReplace(i, i + 1, i, 0, s, 2_000_000, 108_510, 5, t);
                }
                heap.clear();
                direct.clear();
                n += enc.writeTo(heap) + enc.writeTo(direct);
                return n + enc.byteAt(0);
            });
        }
    }

    // ------------------------------------------------------------------
    // Market data
    // ------------------------------------------------------------------

    @Test
    void ringBufferOfferPoll() throws Exception {
        RingBuffer<Object> ring = new RingBuffer<>(64);
        Object[] items = {new Object(), new Object(), new Object()};
        assertAllocationFree("RingBuffer: no allocation by the ring itself", i -> {
            ring.offer(items[i % 3]);
            ring.offer(items[(i + 1) % 3]);
            return (ring.poll() == null ? 0 : 1) + (ring.poll() == null ? 0 : 1);
        });
    }

    @Test
    void tickRingBufferPublishAndDrainToListener() throws Exception {
        TickRingBuffer ring = new TickRingBuffer(1024);
        double[] sum = new double[1];
        TickListener sink = (sym, px, sz, ts) -> sum[0] += sym + px + sz + ts;
        assertAllocationFree("TickRingBuffer/TickListener: zero-allocation publish + drainTo", i -> {
            for (int k = 0; k < 8; k++) {
                ring.publish(k, 1.0850 + k * 1e-5, 1_000_000, i);
            }
            return ring.drainTo(sink, 64) + sum[0];
        });
    }

    @Test
    void sbeTradeFlyweightEncodeDecode() throws Exception {
        ByteBuffer buf = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        TradeFlyweight w = new TradeFlyweight();
        TradeFlyweight r = new TradeFlyweight();
        assertAllocationFree("TradeFlyweight: encode/decode with no allocation", i -> {
            w.wrap(buf, 0).encode(i & 7, 108_505L + i, -5, 1_000_000L, 0, i);
            r.wrap(buf, 0);
            return r.symbolId() + r.price() + r.size() + r.timestampNanos() + r.priceMantissa()
                    + r.priceExponent() + r.sizeMantissa() + r.sizeExponent();
        });
    }

    /** Endless in-memory feed of whole trade frames; read() copies bytes, allocates nothing. */
    private static final class EndlessTradeChannel implements ReadableByteChannel {
        private final byte[] frame;
        private volatile boolean open = true;

        EndlessTradeChannel(byte[] frame) {
            this.frame = frame;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (!open) {
                return -1;
            }
            int frames = dst.remaining() / frame.length;
            for (int f = 0; f < frames; f++) {
                dst.put(frame, 0, frame.length);
            }
            if (frames == 0) {
                Thread.onSpinWait();
            }
            return frames * frame.length;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Test
    void binaryMarketDataClientPerMessageOnReaderThread() throws Exception {
        ByteBuffer wire = ByteBuffer.allocate(TradeFlyweight.ENCODED_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        new TradeFlyweight().wrap(wire, 0).encode(0, 1.0851, -4, 1_000_000, 0, 111L);
        byte[] frame = wire.array();
        Thread[] reader = new Thread[1];
        EndlessTradeChannel channel = new EndlessTradeChannel(frame);
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 12, 4, false)) {
            bus.registerSymbol("EURUSD");
            bus.start();
            try (BinaryMarketDataClient client = new BinaryMarketDataClient(channel, bus)
                    .withPublishRetryNanos(0)
                    .threadFactory(r -> {
                        Thread t = new Thread(r, "alloc-test-reader");
                        t.setDaemon(true);
                        reader[0] = t;
                        return t;
                    })) {
                client.start();
                assertThreadAllocationFree(
                        "BinaryMarketDataClient: no allocation per message (reader thread)",
                        reader[0], () -> client.messagesDecoded() + client.droppedTicks(), 1_000_000);
                assertEquals(null, client.failureReason());
                channel.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Order books
    // ------------------------------------------------------------------

    @Test
    void auctionBookCallPhase() throws Exception {
        AuctionBook book = new AuctionBook(0, 1023, 1024);
        book.referencePrice(500);
        book.openCall();
        long[] resting = new long[64];
        for (int k = 0; k < 64; k++) {
            resting[k] = book.submitLimit(k < 32 ? Side.BUY : Side.SELL, 490 + (k % 20), 100);
        }
        assertAllocationFree("AuctionBook: call phase (submit, cancel, indicative) is allocation-free", i -> {
            long a = book.submitLimit((i & 1) == 0 ? Side.BUY : Side.SELL, 480 + (i & 31), 10 + (i & 7));
            long b = book.submitMarket((i & 1) == 0 ? Side.SELL : Side.BUY, 5);
            int px = book.computeIndicative();
            int pub = book.publishIndicative(i);
            boolean c = book.cancel(a) & book.cancel(b);
            if (!c) {
                throw new AssertionError("cancel failed");
            }
            return px + pub + book.indicativeVolume() + book.indicativeImbalance();
        });
        assertEquals(64, book.restingOrders());
        assertTrue(resting[63] > 0);
    }

    @Test
    void bookPrimitives() throws Exception {
        long[] bits = new long[16];
        long[] keys = new long[256];
        int[] vals = new int[256];
        int mask = 255;   // empty key is 0; ids are positive
        long[] seed = new long[1];
        for (int k = 1; k <= 64; k++) {
            BookPrimitives.mapPut(keys, vals, mask, k, k);   // live keys so probes run collide
        }
        assertAllocationFree("BookPrimitives: zero-allocation bitmap scans and id map", i -> {
            int bit = i & 1023;
            bits[bit >>> 6] |= 1L << bit;
            int up = BookPrimitives.nextSetAtOrAbove(bits, i & 511);
            int down = BookPrimitives.nextSetAtOrBelow(bits, 1023 - (i & 511));
            long key = 1000L + i;
            BookPrimitives.mapPut(keys, vals, mask, key, i);
            int slot = BookPrimitives.mapFind(keys, mask, key);
            int v = vals[slot];
            BookPrimitives.mapRemoveAt(keys, vals, mask, slot);
            seed[0] = BookPrimitives.mix(seed[0] + i);
            return up + down + v + (seed[0] & 1);
        });
    }

    @Test
    void hftOrderBookSteadyStateSubmitMatchCancel() throws Exception {
        HftOrderBook book = new HftOrderBook(0, 1023, 1024);
        long[] fills = new long[1];
        book.tradeSink((maker, taker, px, qty, ts) -> fills[0] += qty);
        assertAllocationFree("HftOrderBook: zero-allocation submit / match / modify / cancel", i -> {
            int tick = 500 + (i & 7);
            long rest = book.submitLimit(Side.BUY, tick, 10, i);
            long take = book.submitLimit(Side.SELL, tick, 4, i);      // partial fill of rest
            long ioc = book.submitIoc(Side.SELL, tick, 2, i);
            long po = book.submitPostOnly(Side.SELL, tick + 20, 3, i);
            book.modify(po, 2, tick + 21, i);
            long mkt = book.submitMarket(Side.SELL, 1, i);
            if (!book.cancel(rest) || !book.cancel(po)) {
                throw new AssertionError("cancel failed");
            }
            return take + ioc + mkt + book.bestBidTick() + book.bestAskTick() + fills[0];
        });
        assertEquals(0, book.restingOrders());
    }

    @Test
    void hftOrderBookSweepOfOneThousandRestingOrders() throws Exception {
        int resting = 1_000;
        HftOrderBook book = new HftOrderBook(0, 2047, 1024);
        long[] fills = new long[1];
        book.tradeSink((maker, taker, px, qty, ts) -> fills[0]++);
        assertAllocationFree("HftOrderBook: one market order filling 1,000 resting orders (> 256 fills)", i -> {
            for (int k = 0; k < resting; k++) {
                book.submitLimit(Side.SELL, 1000 + (k % 50), 1, i);
            }
            long before = fills[0];
            book.submitMarket(Side.BUY, resting, i);
            if (fills[0] - before != resting || book.restingOrders() != 0) {
                throw new AssertionError("sweep did not fill all resting orders");
            }
            return fills[0];
        });
    }

    // ------------------------------------------------------------------
    // Trading
    // ------------------------------------------------------------------

    @Test
    void hftRiskGateCheck() throws Exception {
        HftRiskGate gate = new HftRiskGate(4)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e9)
                .maxPositionQuantity(10_000_000)
                .priceCollarPct(5)
                .maxDailyNotional(1e12)
                .maxDailyOrders(Long.MAX_VALUE)
                .duplicateWindowNanos(1_000);
        for (int s = 0; s < 4; s++) {
            gate.setReferencePrice(s, 100);
        }
        assertAllocationFree("HftRiskGate.check: zero-allocation pre-trade check", i -> {
            Side side = (i & 1) == 0 ? Side.BUY : Side.SELL;
            int ok = gate.check(i & 3, side, 100 + (i & 15), 100.0 + (i & 3) * 0.01, i * 10_000L);
            int bad = gate.check(i & 3, side, 100, 200.0, i * 10_000L);   // outside the collar
            return ok + bad;
        });
    }

    @Test
    void hftOrderGatewaySubmitThroughLoopbackVenueFill() throws Exception {
        HftRiskGate gate = new HftRiskGate(2);
        gate.setReferencePrice(0, 100);
        try (HftOrderGateway gw = new HftOrderGateway(1 << 17, gate, IdleStrategy.busySpin(),
                1 << 10, null, Long.MAX_VALUE)) {
            LoopbackVenue venue = new LoopbackVenue(gw, LoopbackVenue.FILL_ALL, 1 << 10);
            assertAllocationFree("HftOrderGateway submit + OrderStateTable + LoopbackVenue ack/fill: "
                    + "no allocation per message", i -> {
                Side side = (i & 1) == 0 ? Side.BUY : Side.SELL;
                long id = gw.submit(0, side, 100, 100.0, i);
                if (id <= 0) {
                    throw new AssertionError("submit returned " + id);
                }
                venue.onNewOrder(id, 0, side, 100, 100.0, OrderListener.FLAG_NONE, i);
                return gw.filledQuantity(id) + gw.state(id);
            });
        }
    }

    @Test
    void hftOrderGatewayCancelThroughLoopbackVenue() throws Exception {
        HftRiskGate gate = new HftRiskGate(2);
        gate.setReferencePrice(0, 100);
        try (HftOrderGateway gw = new HftOrderGateway(1 << 18, gate, IdleStrategy.busySpin(),
                1 << 10, null, Long.MAX_VALUE)) {
            LoopbackVenue venue = new LoopbackVenue(gw, LoopbackVenue.NO_FILLS, 1 << 10);
            assertAllocationFree("HftOrderGateway cancel + LoopbackVenue onCancel: no allocation", i -> {
                long id = gw.submit(0, Side.BUY, 100, 100.0, i);
                if (id <= 0) {
                    throw new AssertionError("submit returned " + id);
                }
                venue.onNewOrder(id, 0, Side.BUY, 100, 100.0, OrderListener.FLAG_NONE, i);
                boolean c = gw.cancel(id, i);
                venue.onCancel(id, 0, i);
                return (c ? 1 : 0) + gw.state(id);
            });
        }
    }

    @Test
    void orderRingBufferPublishAndDrainToListener() throws Exception {
        OrderRingBuffer ring = new OrderRingBuffer(1024);
        double[] sum = new double[1];
        OrderListener sink = new OrderListener() {
            @Override
            public void onOrder(long orderId, int symbolId, Side side, long quantity, double price,
                                long timestampNanos) {
                sum[0] += orderId + symbolId + side.sign() + quantity + price + timestampNanos;
            }
        };
        assertAllocationFree("OrderRingBuffer/OrderListener: zero-allocation publish + drainTo", i -> {
            for (int k = 0; k < 8; k++) {
                ring.publish(i * 8L + k, k, (k & 1) == 0 ? Side.BUY : Side.SELL, 100, 100.5, i);
            }
            return ring.drainTo(sink, 64) + sum[0];
        });
    }

    @Test
    void positionLedgerOnFill() throws Exception {
        PositionLedger ledger = new PositionLedger(2, 2, 2, 16, 1 << 18);
        assertAllocationFree("PositionLedger.onFill: allocation-free", i -> {
            Side side = ((i >> 3) & 1) == 0 ? Side.BUY : Side.SELL;   // every book alternates
            boolean ok = ledger.onFill(i, i & 1, (i >> 1) & 1, (i >> 2) & 1, side, 100 + (i & 3),
                    100.0 + (i & 7) * 0.01, 0.5);
            if (!ok) {
                throw new AssertionError("fill rejected");
            }
            return ledger.fillCount();
        });
        assertEquals(0, ledger.lotOverflowCount());
    }

    @Test
    void positionLedgerOnFillWithFullFillLog() throws Exception {
        PositionLedger ledger = new PositionLedger(1, 1, 1, 16, 8);
        assertAllocationFree("PositionLedger.onFill: allocation-free (fill log full)", i -> {
            Side side = (i & 1) == 0 ? Side.BUY : Side.SELL;
            ledger.onFill(i, 0, 0, 0, side, 100, 100.0, 0.0);
            return ledger.fillCount();
        });
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    @Test
    void latencyRecorderRecord() throws Exception {
        LatencyRecorder rec = new LatencyRecorder();
        assertAllocationFree("LatencyRecorder.record: zero-allocation", i -> {
            rec.record(((long) i * 2_654_435_761L) & 0xFFFFFFFFFL);
            return rec.count();
        });
    }

    @Test
    void idleStrategiesWithoutParking() throws Exception {
        IdleStrategy spin = IdleStrategy.busySpin();
        IdleStrategy yielding = IdleStrategy.yielding();
        IdleStrategy backoffNoPark = IdleStrategy.backoff(100, 2, 1, 1);
        assertAllocationFree("IdleStrategy: busySpin/yielding/backoff idle path allocates nothing", i -> {
            spin.idle(i & 1);
            spin.idle();
            if ((i & 15) == 0) {
                yielding.idle(0);
            }
            backoffNoPark.idle(0);
            if ((i & 63) == 0) {
                backoffNoPark.idle(1);   // reset before the yield/park stages
            }
            return i;
        });
    }

    @Test
    void idleStrategiesThatPark() {
        IdleStrategy parking = IdleStrategy.parking(1);
        IdleStrategy backoff = IdleStrategy.backoff(1, 1, 1, 2);   // spins, yields, then parks
        assertSmallBatchFree("IdleStrategy: parking/backoff idle path (tiny park) allocates nothing", 2_000, 1_000,
                i -> {
                    parking.idle(0);
                    backoff.idle(0);
                    if ((i & 7) == 0) {
                        backoff.idle(1);
                    }
                });
    }

    @Test
    void hiccupMonitorWhileSampling() {
        for (boolean spin : new boolean[] {false, true}) {
            HiccupMonitor monitor = spin ? HiccupMonitor.spinning(100_000) : new HiccupMonitor(100_000);
            Thread sampler = null;
            try {
                monitor.start();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if ("hiccup-monitor".equals(t.getName()) && t.isAlive()) {
                        sampler = t;   // a stopped earlier monitor may linger; take the live one with samples
                    }
                }
                assumeTrue(sampler != null, "sampler thread not found");
                assertThreadAllocationFree("HiccupMonitor (" + (spin ? "spin" : "park")
                        + "): allocation-free while sampling", sampler, monitor::samples, spin ? 2_000 : 300);
            } finally {
                monitor.close();
                if (sampler != null) {
                    try {
                        sampler.join(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers for paths the probe cannot drive on the test thread
    // ------------------------------------------------------------------

    private interface Step {
        void run(int i);
    }

    private interface Progress {
        long get();
    }

    private static com.sun.management.ThreadMXBean mx() {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        assumeTrue(b instanceof com.sun.management.ThreadMXBean, "no com.sun.management.ThreadMXBean");
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) b;
        assumeTrue(t.isThreadAllocatedMemorySupported(), "thread allocation counting unsupported");
        if (!t.isThreadAllocatedMemoryEnabled()) {
            t.setThreadAllocatedMemoryEnabled(true);
        }
        return t;
    }

    /** As the probe, with call counts small enough for operations that park. */
    private static void assertSmallBatchFree(String claim, int warmup, int measured, Step op) {
        com.sun.management.ThreadMXBean t = mx();
        for (int i = 0; i < warmup; i++) {
            op.run(i);
        }
        long best = Long.MAX_VALUE;
        for (int a = 0; a < AllocationProbe.ATTEMPTS && best > AllocationProbe.TOLERANCE_BYTES; a++) {
            long before = t.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < measured; i++) {
                op.run(i);
            }
            best = Math.min(best, t.getCurrentThreadAllocatedBytes() - before);
        }
        if (best > AllocationProbe.TOLERANCE_BYTES) {
            fail(claim + ": best batch of " + measured + " calls allocated " + best + " bytes");
        }
    }

    /**
     * Measures another thread: after a warm-up of {@code minEvents} events,
     * windows of at least {@code minEvents} events must allocate at most the
     * probe tolerance on that thread (best of the probe's attempts).
     */
    private static void assertThreadAllocationFree(String claim, Thread thread, Progress events,
                                                   long minEvents) {
        com.sun.management.ThreadMXBean t = mx();
        long id = thread.threadId();
        awaitEvents(events, 3 * minEvents, thread);
        long best = Long.MAX_VALUE;
        long counted = 0;
        for (int a = 0; a < AllocationProbe.ATTEMPTS && best > AllocationProbe.TOLERANCE_BYTES; a++) {
            long e0 = events.get();
            long before = t.getThreadAllocatedBytes(id);
            awaitEvents(events, e0 + minEvents, thread);
            long used = t.getThreadAllocatedBytes(id) - before;
            counted = events.get() - e0;
            best = Math.min(best, used);
        }
        assumeTrue(thread.isAlive(), "thread ended during measurement");
        if (best > AllocationProbe.TOLERANCE_BYTES) {
            fail(claim + ": best window allocated " + best + " bytes over about " + counted + " events");
        }
    }

    private static void awaitEvents(Progress events, long target, Thread thread) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (events.get() < target) {
            if (!thread.isAlive() || System.nanoTime() > deadline) {
                fail("thread stopped making progress at " + events.get() + " of " + target);
            }
            LockSupport.parkNanos(1_000_000);
        }
    }
}

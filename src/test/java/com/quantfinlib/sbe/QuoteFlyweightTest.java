package com.quantfinlib.sbe;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quote flyweight: field round-trips at arbitrary offsets, NaN one-sided
 * quotes, type discrimination against the sibling codecs, and the
 * zero-allocation proof that defines this package.
 */
class QuoteFlyweightTest {

    @Test
    void roundTripsAllFieldsAtAnOffset() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        QuoteFlyweight quote = new QuoteFlyweight();
        quote.wrap(buf, 64).encode(7, 1.08505, 2_000_000, 1.08515, 500_000, 123_456_789L);

        QuoteFlyweight reader = new QuoteFlyweight().wrap(buf, 64);
        assertEquals(7, reader.symbolId());
        assertEquals(1.08505, reader.bidPrice());
        assertEquals(2_000_000, reader.bidSize());
        assertEquals(1.08515, reader.askPrice());
        assertEquals(500_000, reader.askSize());
        assertEquals(123_456_789L, reader.timestampNanos());
        assertEquals(QuoteFlyweight.MESSAGE_TYPE, QuoteFlyweight.typeAt(buf, 64));
        assertEquals(48, QuoteFlyweight.BLOCK_LENGTH);
    }

    @Test
    void oneSidedQuotesCarryNaN() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        QuoteFlyweight quote = new QuoteFlyweight().wrap(buf, 0);
        quote.encode(1, Double.NaN, 0, 1.0852, 1_000_000, 1L);
        assertTrue(Double.isNaN(quote.bidPrice()));
        assertEquals(1.0852, quote.askPrice());
    }

    @Test
    void typeDiscriminatesFromSiblingCodecs() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        new TradeFlyweight().wrap(buf, 0).encode(1, 1.0, 1, 1L);
        new OrderFlyweight().wrap(buf, 64).encode(1L, 1, com.quantfinlib.orderbook.Side.BUY,
                100, 1.0, 1L);
        new QuoteFlyweight().wrap(buf, 128).encode(1, 1.0, 1, 1.1, 1, 1L);
        // The three message types are distinct on the wire.
        assertEquals(TradeFlyweight.MESSAGE_TYPE, QuoteFlyweight.typeAt(buf, 0));
        assertEquals(OrderFlyweight.MESSAGE_TYPE, QuoteFlyweight.typeAt(buf, 64));
        assertEquals(QuoteFlyweight.MESSAGE_TYPE, QuoteFlyweight.typeAt(buf, 128));
        assertTrue(TradeFlyweight.MESSAGE_TYPE != QuoteFlyweight.MESSAGE_TYPE
                && OrderFlyweight.MESSAGE_TYPE != QuoteFlyweight.MESSAGE_TYPE);
    }

    @Test
    void encodeDecodeLoopIsAllocationFree() {
        ByteBuffer buf = ByteBuffer.allocateDirect(QuoteFlyweight.BLOCK_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        QuoteFlyweight quote = new QuoteFlyweight().wrap(buf, 0);
        // JIT warmup before the measured window.
        for (int i = 0; i < 100_000; i++) {
            quote.encode(i, 1.0850, 1e6, 1.0852, 1e6, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        double blackhole = 0;
        for (int i = 0; i < 500_000; i++) {
            quote.encode(i, 1.0850 + i * 1e-9, 1e6, 1.0852 + i * 1e-9, 1e6, i);
            blackhole += quote.bidPrice() + quote.askPrice() + quote.symbolId();
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "codec loop allocated " + allocated + " bytes (blackhole " + blackhole + ")");
    }
}

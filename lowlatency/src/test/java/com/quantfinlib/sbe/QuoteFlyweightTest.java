package com.quantfinlib.sbe;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quote flyweight: header, integer prices, aligned layout, pulled sides, and
 * zero allocation per encode/decode.
 */
class QuoteFlyweightTest {

    @Test
    void roundTripsAllFieldsAtAnOffsetWithHeader() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        new QuoteFlyweight().wrap(buf, 64)
                .encode(7, 1.08505, 2_000_000, 1.08515, 500_000, -5, 0, 123_456_789L);

        QuoteFlyweight reader = new QuoteFlyweight().wrap(buf, 64);
        assertEquals(48, MessageHeader.blockLength(buf, 64));
        assertEquals(QuoteFlyweight.TEMPLATE_ID, MessageHeader.templateId(buf, 64));
        assertEquals(MessageHeader.SCHEMA_ID, MessageHeader.schemaId(buf, 64));
        assertEquals(MessageHeader.SCHEMA_VERSION, MessageHeader.version(buf, 64));
        assertEquals(7, reader.symbolId());
        assertEquals(108_505L, reader.bidMantissa());     // exact integer on the wire
        assertEquals(108_515L, reader.askMantissa());
        assertEquals(-5, reader.priceExponent());
        assertEquals(1.08505, reader.bidPrice());
        assertEquals(2_000_000, reader.bidSize());
        assertEquals(1.08515, reader.askPrice());
        assertEquals(500_000, reader.askSize());
        assertEquals(123_456_789L, reader.timestampNanos());
        assertEquals(56, QuoteFlyweight.ENCODED_LENGTH);
    }

    @Test
    void pulledSideReadsBackAsNaN() {
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        QuoteFlyweight quote = new QuoteFlyweight().wrap(buf, 0);
        quote.encode(1, Double.NaN, 0, 1.0852, 1_000_000, -4, 0, 1L);
        assertEquals(Decimal64.NULL_MANTISSA, quote.bidMantissa());
        assertTrue(Double.isNaN(quote.bidPrice()));
        assertEquals(1.0852, quote.askPrice());
    }

    @Test
    void templateIdsDiscriminateTheSiblingCodecs() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        new TradeFlyweight().wrap(buf, 0).encode(1, 1.0, 0, 1, 0, 1L);
        new OrderFlyweight().wrap(buf, 64).encode(1L, 1, com.quantfinlib.core.Side.BUY,
                100, 1.0, 0, 1L);
        new QuoteFlyweight().wrap(buf, 128).encode(1, 1.0, 1, 1.1, 1, -1, 0, 1L);
        assertEquals(TradeFlyweight.TEMPLATE_ID, MessageHeader.templateId(buf, 0));
        assertEquals(OrderFlyweight.TEMPLATE_ID, MessageHeader.templateId(buf, 64));
        assertEquals(QuoteFlyweight.TEMPLATE_ID, MessageHeader.templateId(buf, 128));
        assertTrue(!MessageHeader.isV1Frame(buf, 0) && !MessageHeader.isV1Frame(buf, 64)
                && !MessageHeader.isV1Frame(buf, 128));
    }

    @Test
    void encodeDecodeLoopAllocatesNothing() {
        ByteBuffer buf = ByteBuffer.allocateDirect(QuoteFlyweight.ENCODED_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        QuoteFlyweight quote = new QuoteFlyweight().wrap(buf, 0);
        long allocated = SbeCodecTest.minAllocatedBytes(() -> {
            long blackhole = 0;
            for (int i = 0; i < 200_000; i++) {
                quote.encode(i, 108_500L + i, 1_000_000L, 108_520L + i, 1_000_000L, -5, 0, i);
                blackhole += quote.bidMantissa() + quote.askMantissa() + quote.symbolId();
            }
            return blackhole;
        });
        assertEquals(0, allocated, "quote codec allocated in steady state");
    }
}

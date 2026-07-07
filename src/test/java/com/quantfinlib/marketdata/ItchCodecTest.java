package com.quantfinlib.marketdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItchCodecTest {

    private final ItchCodec.View v = new ItchCodec.View();
    private final byte[] buf = new byte[64];

    @Test
    void addOrderRoundTrips() {
        long stock = ItchCodec.packStock("AAPL");
        int len = ItchCodec.encodeAdd(buf, 0, 42, 34_200_000_000_000L,
                987_654_321L, ItchCodec.BUY, 300, stock, 1_755_000);
        assertEquals(36, len);
        assertEquals(len, ItchCodec.length(ItchCodec.ADD));
        v.wrap(buf, 0);
        assertEquals(ItchCodec.ADD, v.type());
        assertEquals(42, v.stockLocate());
        assertEquals(34_200_000_000_000L, v.timestampNanos());
        assertEquals(987_654_321L, v.orderRef());
        assertEquals(ItchCodec.BUY, v.side());
        assertEquals(300, v.shares());
        assertEquals(stock, v.stock());
        assertEquals(1_755_000, v.priceTick());
    }

    @Test
    void executedRoundTrips() {
        int len = ItchCodec.encodeExecuted(buf, 0, 7, 123L, 55L, 200, 999L);
        assertEquals(31, len);
        v.wrap(buf, 0);
        assertEquals(ItchCodec.EXECUTED, v.type());
        assertEquals(55L, v.orderRef());
        assertEquals(200, v.deltaShares());
        assertEquals(999L, v.matchNumber());
    }

    @Test
    void cancelAndDeleteRoundTrip() {
        assertEquals(23, ItchCodec.encodeCancel(buf, 0, 7, 1L, 55L, 40));
        v.wrap(buf, 0);
        assertEquals(ItchCodec.CANCEL, v.type());
        assertEquals(55L, v.orderRef());
        assertEquals(40, v.deltaShares());

        assertEquals(19, ItchCodec.encodeDelete(buf, 0, 7, 2L, 66L));
        v.wrap(buf, 0);
        assertEquals(ItchCodec.DELETE, v.type());
        assertEquals(66L, v.orderRef());
    }

    @Test
    void replaceRoundTrips() {
        int len = ItchCodec.encodeReplace(buf, 0, 7, 3L, 55L, 56L, 150, 1_760_000);
        assertEquals(35, len);
        v.wrap(buf, 0);
        assertEquals(ItchCodec.REPLACE, v.type());
        assertEquals(55L, v.origRef());
        assertEquals(56L, v.newRef());
        assertEquals(150, v.shares());
        assertEquals(1_760_000, v.priceTick());
    }

    @Test
    void tradeRoundTrips() {
        long stock = ItchCodec.packStock("MSFT");
        int len = ItchCodec.encodeTrade(buf, 0, 9, 4L, 77L, ItchCodec.SELL,
                500, stock, 4_101_500, 12_345L);
        assertEquals(44, len);
        v.wrap(buf, 0);
        assertEquals(ItchCodec.TRADE, v.type());
        assertEquals(77L, v.orderRef());
        assertEquals(ItchCodec.SELL, v.side());
        assertEquals(500, v.shares());
        assertEquals(stock, v.stock());
        assertEquals(4_101_500, v.priceTick());
        assertEquals(12_345L, v.matchNumber());
    }

    @Test
    void stockPackingHandlesShortAndFullSymbols() {
        assertEquals("A", ItchCodec.unpackStock(ItchCodec.packStock("A")));
        assertEquals("BRKB", ItchCodec.unpackStock(ItchCodec.packStock("BRKB")));
        assertEquals("ABCDEFGH", ItchCodec.unpackStock(ItchCodec.packStock("ABCDEFGH")));
    }

    @Test
    void messagesEncodeAtNonZeroOffsets() {
        int off = 13;
        ItchCodec.encodeDelete(buf, off, 7, 5L, 88L);
        v.wrap(buf, off);
        assertEquals(ItchCodec.DELETE, v.type());
        assertEquals(88L, v.orderRef());
    }

    @Test
    void unknownTypeHasNegativeLength() {
        assertEquals(-1, ItchCodec.length((byte) 'Z'));
    }
}

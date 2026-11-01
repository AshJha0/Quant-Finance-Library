package com.quantfinlib.sbe;

import java.nio.ByteBuffer;

/**
 * SBE flyweight for a two-sided quote (templateId 3, schema version 2): the
 * outbound format of a market maker and the inbound format of top-of-book
 * feeds.
 *
 * <p>Layout: the 8-byte {@link MessageHeader}, then a 48-byte block with
 * every 64-bit field at a multiple of 8 from the message start:</p>
 * <pre>
 * offset  8  int64  bidPriceMantissa  ({@link Decimal64#NULL_MANTISSA} = side pulled)
 * offset 16  int64  bidSizeMantissa
 * offset 24  int64  askPriceMantissa
 * offset 32  int64  askSizeMantissa
 * offset 40  int64  timestampNanos
 * offset 48  int32  symbolId
 * offset 52  int8   priceExponent
 * offset 53  int8   sizeExponent
 * offset 54  2 bytes padding
 * </pre>
 * A pulled side reads back as NaN price, the convention
 * {@code fx.AggregatedBook} consumes.
 */
public final class QuoteFlyweight {

    /** SBE templateId. */
    public static final int TEMPLATE_ID = 3;
    /** Body length in bytes, as written in the header. */
    public static final int BLOCK_LENGTH = 48;
    /** Header plus body. */
    public static final int ENCODED_LENGTH = MessageHeader.ENCODED_LENGTH + BLOCK_LENGTH;

    private static final int BID = 8;
    private static final int BID_SIZE = 16;
    private static final int ASK = 24;
    private static final int ASK_SIZE = 32;
    private static final int TIMESTAMP = 40;
    private static final int SYMBOL = 48;
    private static final int PRICE_EXP = 52;
    private static final int SIZE_EXP = 53;

    private ByteBuffer buffer;
    private int offset;

    /**
     * Positions this flyweight over {@code buffer} at {@code offset} (the header start).
     * The buffer is not copied: the caller keeps ownership, and reads and
     * writes through this flyweight go straight to its bytes.
     */
    public QuoteFlyweight wrap(ByteBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /** Encodes header and body with integer prices and sizes. */
    public QuoteFlyweight encode(int symbolId, long bidMantissa, long bidSizeMantissa,
                                 long askMantissa, long askSizeMantissa, int priceExponent,
                                 int sizeExponent, long timestampNanos) {
        MessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID);
        buffer.putLong(offset + BID, bidMantissa);
        buffer.putLong(offset + BID_SIZE, bidSizeMantissa);
        buffer.putLong(offset + ASK, askMantissa);
        buffer.putLong(offset + ASK_SIZE, askSizeMantissa);
        buffer.putLong(offset + TIMESTAMP, timestampNanos);
        buffer.putInt(offset + SYMBOL, symbolId);
        buffer.put(offset + PRICE_EXP, (byte) priceExponent);
        buffer.put(offset + SIZE_EXP, (byte) sizeExponent);
        buffer.putShort(offset + 54, (short) 0);
        return this;
    }

    /** Encodes from doubles on the given grids; a NaN price marks the side pulled. */
    public QuoteFlyweight encode(int symbolId, double bidPrice, double bidSize, double askPrice,
                                 double askSize, int priceExponent, int sizeExponent,
                                 long timestampNanos) {
        return encode(symbolId,
                Decimal64.toMantissa(bidPrice, priceExponent),
                Double.isNaN(bidPrice) ? 0 : Decimal64.toMantissa(bidSize, sizeExponent),
                Decimal64.toMantissa(askPrice, priceExponent),
                Double.isNaN(askPrice) ? 0 : Decimal64.toMantissa(askSize, sizeExponent),
                priceExponent, sizeExponent, timestampNanos);
    }

    public int symbolId() {
        return buffer.getInt(offset + SYMBOL);
    }

    public long bidMantissa() {
        return buffer.getLong(offset + BID);
    }

    public long askMantissa() {
        return buffer.getLong(offset + ASK);
    }

    public int priceExponent() {
        return buffer.get(offset + PRICE_EXP);
    }

    public int sizeExponent() {
        return buffer.get(offset + SIZE_EXP);
    }

    public double bidPrice() {
        return Decimal64.toDouble(bidMantissa(), priceExponent());
    }

    public double bidSize() {
        return Decimal64.toDouble(buffer.getLong(offset + BID_SIZE), sizeExponent());
    }

    public double askPrice() {
        return Decimal64.toDouble(askMantissa(), priceExponent());
    }

    public double askSize() {
        return Decimal64.toDouble(buffer.getLong(offset + ASK_SIZE), sizeExponent());
    }

    public long timestampNanos() {
        return buffer.getLong(offset + TIMESTAMP);
    }
}

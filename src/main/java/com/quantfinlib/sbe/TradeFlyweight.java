package com.quantfinlib.sbe;

import java.nio.ByteBuffer;

/**
 * SBE flyweight for a market-data trade (templateId 1, schema version 2):
 * fixed offsets over a little-endian {@link ByteBuffer}, so encode and
 * decode are absolute primitive reads and writes with no allocation.
 *
 * <p>Layout: the 8-byte {@link MessageHeader}, then a 32-byte block with
 * every 64-bit field at a multiple of 8 from the message start:</p>
 * <pre>
 * offset  8  int64  priceMantissa
 * offset 16  int64  sizeMantissa
 * offset 24  int64  timestampNanos   (exchange event time)
 * offset 32  int32  symbolId         (dense id shared by both ends)
 * offset 36  int8   priceExponent    price = mantissa × 10^exponent
 * offset 37  int8   sizeExponent
 * offset 38  2 bytes padding
 * </pre>
 * Wrap at an offset that is a multiple of 8 for aligned access.
 */
public final class TradeFlyweight {

    /** SBE templateId. */
    public static final int TEMPLATE_ID = 1;
    /** Body length in bytes, as written in the header. */
    public static final int BLOCK_LENGTH = 32;
    /** Header plus body. */
    public static final int ENCODED_LENGTH = MessageHeader.ENCODED_LENGTH + BLOCK_LENGTH;

    private static final int PRICE = 8;
    private static final int SIZE = 16;
    private static final int TIMESTAMP = 24;
    private static final int SYMBOL = 32;
    private static final int PRICE_EXP = 36;
    private static final int SIZE_EXP = 37;

    private ByteBuffer buffer;
    private int offset;

    /**
     * Positions this flyweight over {@code buffer} at {@code offset} (the header start).
     * The buffer is not copied: the caller keeps ownership, and reads and
     * writes through this flyweight go straight to its bytes.
     */
    public TradeFlyweight wrap(ByteBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /** Encodes header and body with integer price and size. */
    public TradeFlyweight encode(int symbolId, long priceMantissa, int priceExponent,
                                 long sizeMantissa, int sizeExponent, long timestampNanos) {
        MessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID);
        buffer.putLong(offset + PRICE, priceMantissa);
        buffer.putLong(offset + SIZE, sizeMantissa);
        buffer.putLong(offset + TIMESTAMP, timestampNanos);
        buffer.putInt(offset + SYMBOL, symbolId);
        buffer.put(offset + PRICE_EXP, (byte) priceExponent);
        buffer.put(offset + SIZE_EXP, (byte) sizeExponent);
        buffer.putShort(offset + 38, (short) 0);
        return this;
    }

    /**
     * Encodes from doubles on the given grids; throws when a value is not
     * on its grid (see {@link Decimal64#toMantissa}).
     */
    public TradeFlyweight encode(int symbolId, double price, int priceExponent, double size,
                                 int sizeExponent, long timestampNanos) {
        return encode(symbolId, Decimal64.toMantissa(price, priceExponent), priceExponent,
                Decimal64.toMantissa(size, sizeExponent), sizeExponent, timestampNanos);
    }

    public int symbolId() {
        return buffer.getInt(offset + SYMBOL);
    }

    public long priceMantissa() {
        return buffer.getLong(offset + PRICE);
    }

    public int priceExponent() {
        return buffer.get(offset + PRICE_EXP);
    }

    public long sizeMantissa() {
        return buffer.getLong(offset + SIZE);
    }

    public int sizeExponent() {
        return buffer.get(offset + SIZE_EXP);
    }

    /** Price as a double (for double-domain consumers such as the bus). */
    public double price() {
        return Decimal64.toDouble(priceMantissa(), priceExponent());
    }

    public double size() {
        return Decimal64.toDouble(sizeMantissa(), sizeExponent());
    }

    public long timestampNanos() {
        return buffer.getLong(offset + TIMESTAMP);
    }
}

package com.quantfinlib.sbe;

import com.quantfinlib.core.Side;

import java.nio.ByteBuffer;

/**
 * SBE flyweight for an order-entry message (templateId 2, schema version
 * 2), the binary counterpart of a FIX NewOrderSingle.
 *
 * <p>Layout: the 8-byte {@link MessageHeader}, then a 40-byte block with
 * every 64-bit field at a multiple of 8 from the message start (version 1
 * put them at offsets congruent to 4 mod 8):</p>
 * <pre>
 * offset  8  int64  orderId
 * offset 16  int64  quantity
 * offset 24  int64  priceMantissa   ({@link Decimal64#NULL_MANTISSA} = market order)
 * offset 32  int64  timestampNanos
 * offset 40  int32  symbolId
 * offset 44  int8   side            (0 = BUY, 1 = SELL)
 * offset 45  int8   priceExponent
 * offset 46  2 bytes padding
 * </pre>
 */
public final class OrderFlyweight {
    /** Creates an instance; the explicit form of the default constructor. */
    public OrderFlyweight() {
    }


    /** SBE templateId. */
    public static final int TEMPLATE_ID = 2;
    /** Body length in bytes, as written in the header. */
    public static final int BLOCK_LENGTH = 40;
    /** Header plus body. */
    public static final int ENCODED_LENGTH = MessageHeader.ENCODED_LENGTH + BLOCK_LENGTH;

    private static final int ORDER_ID = 8;
    private static final int QUANTITY = 16;
    private static final int PRICE = 24;
    private static final int TIMESTAMP = 32;
    private static final int SYMBOL = 40;
    private static final int SIDE = 44;
    private static final int PRICE_EXP = 45;

    private ByteBuffer buffer;
    private int offset;

    /**
     * Positions this flyweight over {@code buffer} at {@code offset} (the header start).
     * The buffer is not copied: the caller keeps ownership, and reads and
     * writes through this flyweight go straight to its bytes.
     *
     * @param buffer the message buffer
     * @param offset index of the header start
     * @return this flyweight
     */
    public OrderFlyweight wrap(ByteBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /** Encodes header and body; {@code priceMantissa = NULL_MANTISSA} is a market order. */
    public OrderFlyweight encode(long orderId, int symbolId, Side side, long quantity,
                                 long priceMantissa, int priceExponent, long timestampNanos) {
        MessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID);
        buffer.putLong(offset + ORDER_ID, orderId);
        buffer.putLong(offset + QUANTITY, quantity);
        buffer.putLong(offset + PRICE, priceMantissa);
        buffer.putLong(offset + TIMESTAMP, timestampNanos);
        buffer.putInt(offset + SYMBOL, symbolId);
        buffer.put(offset + SIDE, (byte) (side == Side.BUY ? 0 : 1));
        buffer.put(offset + PRICE_EXP, (byte) priceExponent);
        buffer.putShort(offset + 46, (short) 0);
        return this;
    }

    /** Encodes from a double price on the {@code 10^priceExponent} grid; NaN = market. */
    public OrderFlyweight encode(long orderId, int symbolId, Side side, long quantity,
                                 double price, int priceExponent, long timestampNanos) {
        return encode(orderId, symbolId, side, quantity,
                Decimal64.toMantissa(price, priceExponent), priceExponent, timestampNanos);
    }

    public long orderId() {
        return buffer.getLong(offset + ORDER_ID);
    }

    public int symbolId() {
        return buffer.getInt(offset + SYMBOL);
    }

    public Side side() {
        return buffer.get(offset + SIDE) == 0 ? Side.BUY : Side.SELL;
    }

    public long quantity() {
        return buffer.getLong(offset + QUANTITY);
    }

    public long priceMantissa() {
        return buffer.getLong(offset + PRICE);
    }

    public int priceExponent() {
        return buffer.get(offset + PRICE_EXP);
    }

    /** True for a market order (null price). */
    public boolean isMarket() {
        return priceMantissa() == Decimal64.NULL_MANTISSA;
    }

    /** Price as a double; NaN for a market order. */
    public double price() {
        return Decimal64.toDouble(priceMantissa(), priceExponent());
    }

    public long timestampNanos() {
        return buffer.getLong(offset + TIMESTAMP);
    }
}

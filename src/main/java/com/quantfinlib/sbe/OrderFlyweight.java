package com.quantfinlib.sbe;

import com.quantfinlib.orderbook.Side;

import java.nio.ByteBuffer;

/**
 * SBE-style flyweight codec for an order-entry message — the binary
 * counterpart of a FIX NewOrderSingle, at fixed offsets with zero
 * allocation and zero parsing (see {@link TradeFlyweight} for the pattern).
 *
 * <p>Wire layout (little-endian, 44 bytes; 3 bytes padding keep the 64-bit
 * fields naturally aligned):</p>
 * <pre>
 * offset  0  int32   messageType    = 2
 * offset  4  int64   orderId
 * offset 12  int32   symbolId
 * offset 16  int8    side           (0 = BUY, 1 = SELL)
 * offset 17  int8[3] padding
 * offset 20  int64   quantity
 * offset 28  double  price          (NaN = market order)
 * offset 36  int64   timestampNanos
 * </pre>
 */
public final class OrderFlyweight {

    /** Message type discriminator at offset 0. */
    public static final int MESSAGE_TYPE = 2;
    /** Total encoded size in bytes. */
    public static final int BLOCK_LENGTH = 44;

    private static final int TYPE_OFFSET = 0;
    private static final int ORDER_ID_OFFSET = 4;
    private static final int SYMBOL_OFFSET = 12;
    private static final int SIDE_OFFSET = 16;
    private static final int QUANTITY_OFFSET = 20;
    private static final int PRICE_OFFSET = 28;
    private static final int TIMESTAMP_OFFSET = 36;

    private ByteBuffer buffer;
    private int offset;

    public OrderFlyweight wrap(ByteBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /** Encodes a full order message at the wrap position (writes the type header). */
    public OrderFlyweight encode(long orderId, int symbolId, Side side, long quantity,
                                 double price, long timestampNanos) {
        buffer.putInt(offset + TYPE_OFFSET, MESSAGE_TYPE);
        buffer.putLong(offset + ORDER_ID_OFFSET, orderId);
        buffer.putInt(offset + SYMBOL_OFFSET, symbolId);
        buffer.put(offset + SIDE_OFFSET, (byte) (side == Side.BUY ? 0 : 1));
        buffer.putLong(offset + QUANTITY_OFFSET, quantity);
        buffer.putDouble(offset + PRICE_OFFSET, price);
        buffer.putLong(offset + TIMESTAMP_OFFSET, timestampNanos);
        return this;
    }

    public long orderId() {
        return buffer.getLong(offset + ORDER_ID_OFFSET);
    }

    public int symbolId() {
        return buffer.getInt(offset + SYMBOL_OFFSET);
    }

    public Side side() {
        return buffer.get(offset + SIDE_OFFSET) == 0 ? Side.BUY : Side.SELL;
    }

    public long quantity() {
        return buffer.getLong(offset + QUANTITY_OFFSET);
    }

    public double price() {
        return buffer.getDouble(offset + PRICE_OFFSET);
    }

    public long timestampNanos() {
        return buffer.getLong(offset + TIMESTAMP_OFFSET);
    }
}

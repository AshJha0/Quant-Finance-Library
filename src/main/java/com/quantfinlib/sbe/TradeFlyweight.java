package com.quantfinlib.sbe;

import java.nio.ByteBuffer;

/**
 * SBE-style flyweight codec for a market-data trade message: fixed field
 * offsets over a {@link ByteBuffer}, so encode/decode is a handful of
 * absolute primitive reads/writes — <b>zero allocation, zero parsing, zero
 * copying</b>. This is the wire format real HFT feeds use (ITCH/SBE family),
 * as opposed to the text protocols (JSON/FIX tag-value) of the retail edges.
 *
 * <p>Wire layout (little-endian, 32 bytes):</p>
 * <pre>
 * offset  0  int32   messageType   = 1
 * offset  4  int32   symbolId          (dense id shared by both ends)
 * offset  8  double  price
 * offset 16  double  size
 * offset 24  int64   timestampNanos    (exchange event time)
 * </pre>
 *
 * <p>Usage: {@code wrap(buffer, offset)} then read/write fields. The
 * flyweight holds no state besides the wrap position — reuse one instance
 * for millions of messages.</p>
 */
public final class TradeFlyweight {

    /** Message type discriminator at offset 0. */
    public static final int MESSAGE_TYPE = 1;
    /** Total encoded size in bytes. */
    public static final int BLOCK_LENGTH = 32;

    private static final int TYPE_OFFSET = 0;
    private static final int SYMBOL_OFFSET = 4;
    private static final int PRICE_OFFSET = 8;
    private static final int SIZE_OFFSET = 16;
    private static final int TIMESTAMP_OFFSET = 24;

    private ByteBuffer buffer;
    private int offset;

    /** Positions this flyweight over {@code buffer} at {@code offset}. */
    public TradeFlyweight wrap(ByteBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /** Encodes a full trade message at the wrap position (writes the type header). */
    public TradeFlyweight encode(int symbolId, double price, double size, long timestampNanos) {
        buffer.putInt(offset + TYPE_OFFSET, MESSAGE_TYPE);
        buffer.putInt(offset + SYMBOL_OFFSET, symbolId);
        buffer.putDouble(offset + PRICE_OFFSET, price);
        buffer.putDouble(offset + SIZE_OFFSET, size);
        buffer.putLong(offset + TIMESTAMP_OFFSET, timestampNanos);
        return this;
    }

    public int symbolId() {
        return buffer.getInt(offset + SYMBOL_OFFSET);
    }

    public double price() {
        return buffer.getDouble(offset + PRICE_OFFSET);
    }

    public double size() {
        return buffer.getDouble(offset + SIZE_OFFSET);
    }

    public long timestampNanos() {
        return buffer.getLong(offset + TIMESTAMP_OFFSET);
    }

    /** Reads the message-type discriminator at {@code offset} without wrapping. */
    public static int typeAt(ByteBuffer buffer, int offset) {
        return buffer.getInt(offset);
    }
}

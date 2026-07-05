package com.quantfinlib.sbe;

import java.nio.ByteBuffer;

/**
 * SBE-style flyweight codec for a two-sided quote message — the outbound
 * format of a market maker (and the inbound format of venue top-of-book
 * feeds), completing the binary codec family: {@link TradeFlyweight} (trade
 * in), {@link OrderFlyweight} (order out), quote (two-sided out).
 *
 * <p>Wire layout (little-endian, 48 bytes):</p>
 * <pre>
 * offset  0  int32   messageType   = 3
 * offset  4  int32   symbolId          (dense id shared by both ends)
 * offset  8  double  bidPrice
 * offset 16  double  bidSize
 * offset 24  double  askPrice
 * offset 32  double  askSize
 * offset 40  int64   timestampNanos    (quote creation time)
 * </pre>
 *
 * <p>One-sided quotes carry NaN on the pulled side — the same convention
 * {@code fx.AggregatedBook} consumes. Like its siblings, the flyweight
 * holds only the wrap position: reuse a single instance for millions of
 * messages, zero allocation per encode/decode.</p>
 */
public final class QuoteFlyweight {

    /** Message type discriminator at offset 0. */
    public static final int MESSAGE_TYPE = 3;
    /** Total encoded size in bytes. */
    public static final int BLOCK_LENGTH = 48;

    private static final int TYPE_OFFSET = 0;
    private static final int SYMBOL_OFFSET = 4;
    private static final int BID_OFFSET = 8;
    private static final int BID_SIZE_OFFSET = 16;
    private static final int ASK_OFFSET = 24;
    private static final int ASK_SIZE_OFFSET = 32;
    private static final int TIMESTAMP_OFFSET = 40;

    private ByteBuffer buffer;
    private int offset;

    /** Positions this flyweight over {@code buffer} at {@code offset}. */
    public QuoteFlyweight wrap(ByteBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /** Encodes a full quote message at the wrap position (writes the type header). */
    public QuoteFlyweight encode(int symbolId, double bidPrice, double bidSize,
                                 double askPrice, double askSize, long timestampNanos) {
        buffer.putInt(offset + TYPE_OFFSET, MESSAGE_TYPE);
        buffer.putInt(offset + SYMBOL_OFFSET, symbolId);
        buffer.putDouble(offset + BID_OFFSET, bidPrice);
        buffer.putDouble(offset + BID_SIZE_OFFSET, bidSize);
        buffer.putDouble(offset + ASK_OFFSET, askPrice);
        buffer.putDouble(offset + ASK_SIZE_OFFSET, askSize);
        buffer.putLong(offset + TIMESTAMP_OFFSET, timestampNanos);
        return this;
    }

    public int symbolId() {
        return buffer.getInt(offset + SYMBOL_OFFSET);
    }

    public double bidPrice() {
        return buffer.getDouble(offset + BID_OFFSET);
    }

    public double bidSize() {
        return buffer.getDouble(offset + BID_SIZE_OFFSET);
    }

    public double askPrice() {
        return buffer.getDouble(offset + ASK_OFFSET);
    }

    public double askSize() {
        return buffer.getDouble(offset + ASK_SIZE_OFFSET);
    }

    public long timestampNanos() {
        return buffer.getLong(offset + TIMESTAMP_OFFSET);
    }

    /** Reads the type discriminator without wrapping a flyweight. */
    public static int typeAt(ByteBuffer buffer, int offset) {
        return buffer.getInt(offset);
    }
}

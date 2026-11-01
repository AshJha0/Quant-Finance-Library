package com.quantfinlib.sbe;

import java.nio.ByteBuffer;

/**
 * Stream framing shared by the binary readers: finds the next complete
 * message at the buffer position, version 2 (with {@link MessageHeader})
 * or version 1 (bare 32-bit type number, kept readable for migration).
 */
final class SbeFrames {

    /** Largest block a reader accepts; anything larger is treated as corruption. */
    static final int MAX_BLOCK_LENGTH = 4096;
    /** Version-1 frame lengths by type number (index 1..3). */
    private static final int[] V1_LENGTH = {0, 32, 44, 48};

    // Version-1 field offsets.
    static final int V1_TRADE_SYMBOL = 4;
    static final int V1_TRADE_PRICE = 8;
    static final int V1_TRADE_SIZE = 16;
    static final int V1_TRADE_TS = 24;
    static final int V1_ORDER_ID = 4;
    static final int V1_ORDER_SYMBOL = 12;
    static final int V1_ORDER_SIDE = 16;
    static final int V1_ORDER_QTY = 20;
    static final int V1_ORDER_PRICE = 28;
    static final int V1_ORDER_TS = 36;

    private SbeFrames() {
    }

    /**
     * Length of the complete frame at {@code pos}, or 0 when more bytes are
     * needed. Version-1 frames report their type as a negative number
     * {@code -type} in {@code kind[0]}; version-2 frames their templateId.
     *
     * @throws IllegalStateException on a foreign schema, an impossible block
     *         length, or a known template whose block is shorter than its
     *         fixed fields
     */
    static int frameLength(ByteBuffer buffer, int pos, int[] kind) {
        int remaining = buffer.limit() - pos;
        if (remaining < 4) {
            return 0;
        }
        if (MessageHeader.isV1Frame(buffer, pos)) {
            int type = buffer.getInt(pos);
            kind[0] = -type;
            return remaining >= V1_LENGTH[type] ? V1_LENGTH[type] : 0;
        }
        if (remaining < MessageHeader.ENCODED_LENGTH) {
            return 0;
        }
        int schema = MessageHeader.schemaId(buffer, pos);
        if (schema != MessageHeader.SCHEMA_ID) {
            throw new IllegalStateException("unknown schema id " + schema
                    + " (template " + MessageHeader.templateId(buffer, pos) + ") on binary stream");
        }
        int block = MessageHeader.blockLength(buffer, pos);
        int template = MessageHeader.templateId(buffer, pos);
        int needed = switch (template) {
            case TradeFlyweight.TEMPLATE_ID -> TradeFlyweight.BLOCK_LENGTH;
            case OrderFlyweight.TEMPLATE_ID -> OrderFlyweight.BLOCK_LENGTH;
            case QuoteFlyweight.TEMPLATE_ID -> QuoteFlyweight.BLOCK_LENGTH;
            default -> 0;
        };
        if (block < needed || block > MAX_BLOCK_LENGTH) {
            throw new IllegalStateException("template " + template + " with block length "
                    + block + " (version " + MessageHeader.version(buffer, pos) + ")");
        }
        kind[0] = template;
        int total = MessageHeader.ENCODED_LENGTH + block;
        return remaining >= total ? total : 0;
    }
}

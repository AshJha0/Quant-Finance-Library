package com.quantfinlib.sbe;

import java.nio.ByteBuffer;

/**
 * The standard SBE message header (FIX Simple Binary Encoding 1.0, section
 * "Message header"): four little-endian uint16 fields in front of every
 * message body.
 * <pre>
 * offset 0  uint16 blockLength   body length in bytes (fixed block)
 * offset 2  uint16 templateId    message type
 * offset 4  uint16 schemaId      {@link #SCHEMA_ID}
 * offset 6  uint16 version       schema version of the sender
 * </pre>
 *
 * <p>Evolution follows the SBE rules: a newer sender may append fields to a
 * block, so a decoder reads the fields it knows and advances by the
 * {@code blockLength} on the wire, not by its own constant. A sender with
 * a different {@code schemaId} or a block shorter than the decoder needs is
 * a protocol error.</p>
 *
 * <p>Schema version 1 of this library had no header: a 32-bit type number
 * (1, 2 or 3) at offset 0. Read as this header, those bytes give a
 * templateId of 0, which no version-2 message uses, so {@link #isV1Frame}
 * can tell them apart and the readers keep decoding version-1 senders.</p>
 */
public final class MessageHeader {

    /** Header size in bytes. */
    public static final int ENCODED_LENGTH = 8;
    /** Schema id of this library's messages. */
    public static final int SCHEMA_ID = 0x5146;
    /** Current schema version: header, integer prices, aligned layouts. */
    public static final int SCHEMA_VERSION = 2;

    private MessageHeader() {
    }

    /** Writes a header for {@code templateId} with this schema's id and version. */
    public static void write(ByteBuffer buffer, int offset, int blockLength, int templateId) {
        buffer.putShort(offset, (short) blockLength);
        buffer.putShort(offset + 2, (short) templateId);
        buffer.putShort(offset + 4, (short) SCHEMA_ID);
        buffer.putShort(offset + 6, (short) SCHEMA_VERSION);
    }

    public static int blockLength(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset) & 0xFFFF;
    }

    public static int templateId(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset + 2) & 0xFFFF;
    }

    public static int schemaId(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset + 4) & 0xFFFF;
    }

    public static int version(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset + 6) & 0xFFFF;
    }

    /** True when the 4 bytes at {@code offset} are a schema-version-1 type number (1..3). */
    public static boolean isV1Frame(ByteBuffer buffer, int offset) {
        int type = buffer.getInt(offset);
        return type >= 1 && type <= 3;
    }
}

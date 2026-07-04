package com.quantfinlib.fix;

import java.util.Arrays;

/**
 * Incremental stream framer for FIX messages: feed raw socket bytes in any
 * fragmentation, poll complete validated {@link FixMessage}s out. Framing
 * uses BodyLength(9), so message boundaries are exact regardless of TCP
 * segmentation.
 */
final class FixDecoder {

    private byte[] buffer = new byte[8192];
    private int length;

    void feed(byte[] data, int offset, int count) {
        if (length + count > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + count));
        }
        System.arraycopy(data, offset, buffer, length, count);
        length += count;
    }

    /** Next complete message, or null if more bytes are needed. */
    FixMessage poll() {
        if (length < 20) {
            return null;
        }
        // Locate "9=<digits>SOH" following the BeginString field.
        int firstSoh = indexOf(FixMessage.SOH, 0);
        if (firstSoh < 0) {
            return null;
        }
        if (buffer[firstSoh + 1] != '9' || buffer[firstSoh + 2] != '=') {
            throw new IllegalStateException("stream corrupt: BodyLength not after BeginString");
        }
        int lenEnd = indexOf(FixMessage.SOH, firstSoh + 3);
        if (lenEnd < 0) {
            return null;
        }
        int bodyLen = 0;
        for (int i = firstSoh + 3; i < lenEnd; i++) {
            bodyLen = bodyLen * 10 + (buffer[i] - '0');
        }
        int total = lenEnd + 1 + bodyLen + 7;   // "10=xxx" + SOH trailer
        if (length < total) {
            return null;
        }
        byte[] message = Arrays.copyOfRange(buffer, 0, total);
        System.arraycopy(buffer, total, buffer, 0, length - total);
        length -= total;
        return FixMessage.parse(message);
    }

    private int indexOf(char c, int from) {
        for (int i = from; i < length; i++) {
            if (buffer[i] == c) {
                return i;
            }
        }
        return -1;
    }
}

package com.quantfinlib.fix;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FIX 4.4 wire-format message: tag=value fields delimited by SOH, framed by
 * BeginString(8) / BodyLength(9) / CheckSum(10). Parsing validates the
 * checksum and body length; building computes them. No repeating-group
 * support — sufficient for the session and single-order message types this
 * engine speaks.
 */
public final class FixMessage {

    public static final char SOH = (char) 1;
    public static final String BEGIN_STRING = "FIX.4.4";

    // Common tags.
    public static final int AVG_PX = 6;
    public static final int BEGIN_STRING_TAG = 8;
    public static final int BODY_LENGTH = 9;
    public static final int CHECK_SUM = 10;
    public static final int CL_ORD_ID = 11;
    public static final int CUM_QTY = 14;
    public static final int EXEC_ID = 17;
    public static final int LAST_PX = 31;
    public static final int LAST_QTY = 32;
    public static final int MSG_SEQ_NUM = 34;
    public static final int MSG_TYPE = 35;
    public static final int ORDER_ID = 37;
    public static final int ORDER_QTY = 38;
    public static final int ORD_STATUS = 39;
    public static final int ORD_TYPE = 40;
    public static final int PRICE = 44;
    public static final int SENDER_COMP_ID = 49;
    public static final int SENDING_TIME = 52;
    public static final int SIDE = 54;
    public static final int SYMBOL = 55;
    public static final int TARGET_COMP_ID = 56;
    public static final int TIME_IN_FORCE = 59;
    public static final int TRANSACT_TIME = 60;
    public static final int ENCRYPT_METHOD = 98;
    public static final int HEART_BT_INT = 108;
    public static final int TEST_REQ_ID = 112;
    public static final int EXEC_TYPE = 150;
    public static final int LEAVES_QTY = 151;

    // Message types.
    public static final String HEARTBEAT = "0";
    public static final String TEST_REQUEST = "1";
    public static final String LOGON = "A";
    public static final String LOGOUT = "5";
    public static final String NEW_ORDER_SINGLE = "D";
    public static final String EXECUTION_REPORT = "8";

    private final Map<Integer, String> fields;

    private FixMessage(Map<Integer, String> fields) {
        this.fields = fields;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Parses and validates one complete framed message. */
    public static FixMessage parse(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!raw.startsWith("8=" + BEGIN_STRING + SOH)) {
            throw new IllegalArgumentException("missing BeginString: " + printable(raw));
        }
        int checksumField = raw.lastIndexOf(SOH + "10=");
        if (checksumField < 0 || !raw.endsWith(String.valueOf(SOH))) {
            throw new IllegalArgumentException("missing CheckSum: " + printable(raw));
        }
        int sum = 0;
        for (int i = 0; i <= checksumField; i++) {
            sum += bytes[i] & 0xFF;
        }
        String declared = raw.substring(checksumField + 4, raw.length() - 1);
        if (!String.format("%03d", sum % 256).equals(declared)) {
            throw new IllegalArgumentException("checksum mismatch: declared " + declared
                    + " computed " + sum % 256);
        }

        Map<Integer, String> fields = new LinkedHashMap<>();
        for (String pair : raw.substring(0, raw.length() - 1).split(String.valueOf(SOH))) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("malformed field: " + printable(pair));
            }
            fields.put(Integer.parseInt(pair.substring(0, eq)), pair.substring(eq + 1));
        }
        // Validate body length: bytes after the BodyLength field's SOH, up to CheckSum.
        int bodyStart = raw.indexOf(SOH, raw.indexOf("9=")) + 1;
        int declaredLen = Integer.parseInt(fields.get(BODY_LENGTH));
        int actualLen = checksumField + 1 - bodyStart;
        if (declaredLen != actualLen) {
            throw new IllegalArgumentException(
                    "body length mismatch: declared " + declaredLen + " actual " + actualLen);
        }
        return new FixMessage(fields);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String msgType() {
        return fields.get(MSG_TYPE);
    }

    public boolean has(int tag) {
        return fields.containsKey(tag);
    }

    public String getString(int tag) {
        String v = fields.get(tag);
        if (v == null) {
            throw new IllegalArgumentException("missing tag " + tag + " in " + this);
        }
        return v;
    }

    public String getString(int tag, String defaultValue) {
        return fields.getOrDefault(tag, defaultValue);
    }

    public long getLong(int tag) {
        return Long.parseLong(getString(tag));
    }

    public double getDouble(int tag) {
        return Double.parseDouble(getString(tag));
    }

    public double getDouble(int tag, double defaultValue) {
        String v = fields.get(tag);
        return v == null ? defaultValue : Double.parseDouble(v);
    }

    public char getChar(int tag) {
        return getString(tag).charAt(0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(k).append('=').append(v).append('|'));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    public static Builder builder(String msgType) {
        return new Builder(msgType);
    }

    /** Body-field builder; the session supplies header fields at encode time. */
    public static final class Builder {

        private final String msgType;
        private final StringBuilder body = new StringBuilder(128);

        private Builder(String msgType) {
            this.msgType = msgType;
        }

        public Builder field(int tag, String value) {
            body.append(tag).append('=').append(value).append(SOH);
            return this;
        }

        public Builder field(int tag, long value) {
            return field(tag, Long.toString(value));
        }

        public Builder field(int tag, char value) {
            return field(tag, String.valueOf(value));
        }

        /** Plain-decimal rendering (FIX forbids scientific notation). */
        public Builder field(int tag, double value) {
            BigDecimal decimal = BigDecimal.valueOf(value);
            if (decimal.signum() != 0) {
                decimal = decimal.stripTrailingZeros();
            }
            return field(tag, decimal.toPlainString());
        }

        public String msgType() {
            return msgType;
        }

        /** Frames the message with header, body length and checksum. */
        public byte[] encode(String senderCompId, String targetCompId, long seqNum,
                             String sendingTimeUtc) {
            String afterLength = "35=" + msgType + SOH
                    + "49=" + senderCompId + SOH
                    + "56=" + targetCompId + SOH
                    + "34=" + seqNum + SOH
                    + "52=" + sendingTimeUtc + SOH
                    + body;
            String head = "8=" + BEGIN_STRING + SOH + "9=" + afterLength.length() + SOH;
            byte[] payload = (head + afterLength).getBytes(StandardCharsets.ISO_8859_1);
            int sum = 0;
            for (byte b : payload) {
                sum += b & 0xFF;
            }
            String full = head + afterLength + "10=" + String.format("%03d", sum % 256) + SOH;
            return full.getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    private static String printable(String raw) {
        return raw.replace(SOH, '|');
    }
}

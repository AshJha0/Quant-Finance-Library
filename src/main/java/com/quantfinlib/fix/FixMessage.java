package com.quantfinlib.fix;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FIX 4.4 wire-format message: tag=value fields delimited by SOH, framed by
 * BeginString(8) / BodyLength(9) / CheckSum(10). Parsing validates the frame
 * (BeginString first, BodyLength second, the three-digit CheckSum last) and
 * then every field.
 *
 * <p>Fields are kept in wire order, so repeated tags (the members of a
 * repeating group) are all retained: {@link #getString(int)} returns the
 * first occurrence and {@link #getAll(int)} every occurrence in order. Data
 * fields whose value may contain SOH (RawData(96), XmlData(213),
 * EncodedText(355), Signature(89), SecureData(91) and the other
 * {@code Encoded*} fields) are read by the byte count of their preceding
 * length field, as FIX 4.4 Vol 1 ("data" data type) requires. There is no
 * data dictionary, so group structure itself is not checked.</p>
 *
 * <p>Parse failures throw {@link FixParseException}, which separates a
 * garbled frame (discarded without consuming its sequence number, FIX 4.4
 * Vol 2 "Message Recovery") from a well-framed message with a bad field
 * (answered with a session Reject(35=3) carrying SessionRejectReason(373)).</p>
 */
public final class FixMessage {

    public static final char SOH = (char) 1;
    public static final String BEGIN_STRING = "FIX.4.4";

    // Common tags.
    public static final int AVG_PX = 6;
    public static final int BEGIN_SEQ_NO = 7;
    public static final int BEGIN_STRING_TAG = 8;
    public static final int BODY_LENGTH = 9;
    public static final int CHECK_SUM = 10;
    public static final int CL_ORD_ID = 11;
    public static final int CUM_QTY = 14;
    public static final int END_SEQ_NO = 16;
    public static final int EXEC_ID = 17;
    public static final int LAST_PX = 31;
    public static final int LAST_QTY = 32;
    public static final int MSG_SEQ_NUM = 34;
    public static final int MSG_TYPE = 35;
    public static final int NEW_SEQ_NO = 36;
    public static final int ORDER_ID = 37;
    public static final int ORDER_QTY = 38;
    public static final int ORD_STATUS = 39;
    public static final int ORD_TYPE = 40;
    public static final int ORIG_CL_ORD_ID = 41;
    public static final int POSS_DUP_FLAG = 43;
    public static final int PRICE = 44;
    public static final int REF_SEQ_NUM = 45;
    public static final int SENDER_COMP_ID = 49;
    public static final int SENDING_TIME = 52;
    public static final int SIDE = 54;
    public static final int SYMBOL = 55;
    public static final int TARGET_COMP_ID = 56;
    public static final int TEXT = 58;
    public static final int TIME_IN_FORCE = 59;
    public static final int TRANSACT_TIME = 60;
    public static final int RAW_DATA_LENGTH = 95;
    public static final int RAW_DATA = 96;
    public static final int POSS_RESEND = 97;
    public static final int ENCRYPT_METHOD = 98;
    public static final int CXL_REJ_REASON = 102;
    public static final int ORD_REJ_REASON = 103;
    public static final int HEART_BT_INT = 108;
    public static final int TEST_REQ_ID = 112;
    public static final int ORIG_SENDING_TIME = 122;
    public static final int GAP_FILL_FLAG = 123;
    public static final int RESET_SEQ_NUM_FLAG = 141;
    /** NextExpectedMsgSeqNum(789), on Logon: the next MsgSeqNum the sender expects to receive. */
    public static final int NEXT_EXPECTED_MSG_SEQ_NUM = 789;
    public static final int EXEC_TYPE = 150;
    public static final int LEAVES_QTY = 151;
    public static final int ENCODED_TEXT_LEN = 354;
    public static final int ENCODED_TEXT = 355;
    public static final int REF_TAG_ID = 371;
    public static final int REF_MSG_TYPE = 372;
    public static final int SESSION_REJECT_REASON = 373;
    public static final int BUSINESS_REJECT_REASON = 380;
    public static final int CXL_REJ_RESPONSE_TO = 434;
    public static final int USERNAME = 553;
    public static final int PASSWORD = 554;

    // Message types.
    public static final String HEARTBEAT = "0";
    public static final String TEST_REQUEST = "1";
    public static final String RESEND_REQUEST = "2";
    public static final String REJECT = "3";
    public static final String SEQUENCE_RESET = "4";
    public static final String LOGON = "A";
    public static final String LOGOUT = "5";
    public static final String NEW_ORDER_SINGLE = "D";
    public static final String EXECUTION_REPORT = "8";
    public static final String ORDER_CANCEL_REJECT = "9";
    public static final String ORDER_CANCEL_REQUEST = "F";
    public static final String ORDER_CANCEL_REPLACE_REQUEST = "G";
    public static final String BUSINESS_MESSAGE_REJECT = "j";

    // SessionRejectReason(373) values, FIX 4.4 Vol 6.
    public static final int REJECT_INVALID_TAG_NUMBER = 0;
    public static final int REJECT_REQUIRED_TAG_MISSING = 1;
    public static final int REJECT_TAG_WITHOUT_VALUE = 4;
    public static final int REJECT_VALUE_INCORRECT = 5;
    public static final int REJECT_INCORRECT_DATA_FORMAT = 6;
    public static final int REJECT_COMP_ID_PROBLEM = 9;
    public static final int REJECT_SENDING_TIME_ACCURACY = 10;
    public static final int REJECT_INVALID_MSG_TYPE = 11;
    public static final int REJECT_OTHER = 99;

    /** FIX 4.4 length tag to the data tag it measures. */
    private static final Map<Integer, Integer> DATA_FIELD_FOR_LENGTH = Map.ofEntries(
            Map.entry(90, 91), Map.entry(93, 89), Map.entry(95, 96), Map.entry(212, 213),
            Map.entry(348, 349), Map.entry(350, 351), Map.entry(352, 353), Map.entry(354, 355),
            Map.entry(356, 357), Map.entry(360, 361), Map.entry(362, 363), Map.entry(364, 365),
            Map.entry(445, 446), Map.entry(618, 619), Map.entry(621, 622));

    private static final byte[] PREFIX = ("8=" + BEGIN_STRING + SOH)
            .getBytes(StandardCharsets.ISO_8859_1);

    private final int[] tags;
    private final String[] values;
    private final Map<Integer, String> first;

    private FixMessage(int[] tags, String[] values) {
        this.tags = tags;
        this.values = values;
        this.first = new HashMap<>(tags.length * 2);
        for (int i = 0; i < tags.length; i++) {
            first.putIfAbsent(tags[i], values[i]);
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * Parses and validates one complete framed message.
     *
     * @throws FixParseException {@link FixParseException#garbled()} for a bad
     *         frame (BeginString, BodyLength, CheckSum, MsgType position); a
     *         field fault otherwise, carrying the reject reason and, when it
     *         could be read, the MsgSeqNum
     */
    public static FixMessage parse(byte[] bytes) {
        int len = bytes.length;
        if (len < PREFIX.length + 8 || !Arrays.equals(bytes, 0, PREFIX.length,
                PREFIX, 0, PREFIX.length)) {
            throw FixParseException.garbled("missing BeginString: " + printable(bytes));
        }
        // Trailer: SOH "10=" d d d SOH at the very end.
        int csField = len - 7;
        if (bytes[len - 1] != SOH || bytes[csField - 1] != SOH || bytes[csField] != '1'
                || bytes[csField + 1] != '0' || bytes[csField + 2] != '='
                || !isDigit(bytes[csField + 3]) || !isDigit(bytes[csField + 4])
                || !isDigit(bytes[csField + 5])) {
            throw FixParseException.garbled("missing CheckSum: " + printable(bytes));
        }
        int sum = 0;
        for (int i = 0; i < csField; i++) {
            sum += bytes[i] & 0xFF;
        }
        int declaredSum = (bytes[csField + 3] - '0') * 100 + (bytes[csField + 4] - '0') * 10
                + (bytes[csField + 5] - '0');
        if (declaredSum != sum % 256) {
            throw FixParseException.garbled("checksum mismatch: declared " + declaredSum
                    + " computed " + sum % 256);
        }
        // BodyLength is the second field and counts the bytes after its SOH
        // up to and including the SOH before CheckSum.
        int p = PREFIX.length;
        if (bytes[p] != '9' || bytes[p + 1] != '=') {
            throw FixParseException.garbled("BodyLength not after BeginString");
        }
        int declaredLen = 0;
        int q = p + 2;
        while (q < csField && bytes[q] != SOH) {
            if (!isDigit(bytes[q]) || declaredLen > 1 << 24) {
                throw FixParseException.garbled("BodyLength not numeric");
            }
            declaredLen = declaredLen * 10 + (bytes[q] - '0');
            q++;
        }
        int bodyStart = q + 1;
        if (q == p + 2 || declaredLen != csField - bodyStart) {
            throw FixParseException.garbled("body length mismatch: declared " + declaredLen
                    + " actual " + (csField - bodyStart));
        }

        int[] tags = new int[16];
        String[] values = new String[16];
        int n = 0;
        int pos = 0;
        int pendingDataTag = -1;
        int pendingDataLen = -1;
        int errorReason = -1;
        int errorTag = 0;
        String errorText = null;
        long seq = -1;
        while (pos < len) {
            int eq = pos;
            int tag = 0;
            boolean tagOk = true;
            while (eq < len && bytes[eq] != '=' && bytes[eq] != SOH) {
                if (!isDigit(bytes[eq]) || tag > 100_000_000) {
                    tagOk = false;
                } else {
                    tag = tag * 10 + (bytes[eq] - '0');
                }
                eq++;
            }
            if (eq >= len || bytes[eq] != '=' || eq == pos) {
                tagOk = false;
            }
            int valueStart = Math.min(eq + 1, len);
            int valueEnd;
            if (tagOk && tag == pendingDataTag && pendingDataLen >= 0
                    && valueStart + pendingDataLen < csField   // never into the trailer
                    && bytes[valueStart + pendingDataLen] == SOH) {
                valueEnd = valueStart + pendingDataLen;
            } else {
                valueEnd = valueStart;
                while (valueEnd < len && bytes[valueEnd] != SOH) {
                    valueEnd++;
                }
                if (tagOk && tag == pendingDataTag && errorReason < 0) {
                    errorReason = REJECT_INCORRECT_DATA_FORMAT;
                    errorTag = tag;
                    errorText = "data field " + tag + " does not match its length field";
                }
            }
            pendingDataTag = -1;
            if (!tagOk) {
                if (errorReason < 0) {
                    errorReason = REJECT_INVALID_TAG_NUMBER;
                    errorText = "invalid tag number: "
                            + printable(Arrays.copyOfRange(bytes, pos, Math.min(eq, len)));
                }
                pos = valueEnd + 1;
                continue;
            }
            String value = new String(bytes, valueStart, valueEnd - valueStart,
                    StandardCharsets.ISO_8859_1);
            if (value.isEmpty() && errorReason < 0) {
                errorReason = REJECT_TAG_WITHOUT_VALUE;
                errorTag = tag;
                errorText = "tag " + tag + " specified without a value";
            }
            Integer dataTag = DATA_FIELD_FOR_LENGTH.get(tag);
            if (dataTag != null) {
                pendingDataTag = dataTag;
                pendingDataLen = parseNonNegativeInt(value);
                if (pendingDataLen < 0 && errorReason < 0) {
                    errorReason = REJECT_INCORRECT_DATA_FORMAT;
                    errorTag = tag;
                    errorText = "length field " + tag + " is not a non-negative integer";
                }
            }
            if (tag == MSG_SEQ_NUM && seq < 0) {
                int v = parseNonNegativeInt(value);
                seq = v;
                if (v < 0 && errorReason < 0) {
                    errorReason = REJECT_INCORRECT_DATA_FORMAT;
                    errorTag = tag;
                    errorText = "MsgSeqNum is not numeric";
                }
            }
            if (n == tags.length) {
                tags = Arrays.copyOf(tags, n * 2);
                values = Arrays.copyOf(values, n * 2);
            }
            tags[n] = tag;
            values[n] = value;
            n++;
            pos = valueEnd + 1;
        }
        if (n < 3 || tags[2] != MSG_TYPE) {
            throw FixParseException.garbled("MsgType(35) is not the third field");
        }
        if (errorReason >= 0) {
            throw FixParseException.field(errorReason, errorTag, errorText, seq, values[2]);
        }
        return new FixMessage(Arrays.copyOf(tags, n), Arrays.copyOf(values, n));
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    /** Non-negative int, or -1 when empty, non-numeric or longer than nine digits. */
    private static int parseNonNegativeInt(String s) {
        if (s.isEmpty() || s.length() > 9) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String msgType() {
        return first.get(MSG_TYPE);
    }

    public boolean has(int tag) {
        return first.containsKey(tag);
    }

    /** Every occurrence of {@code tag} in wire order (repeating-group members). */
    public List<String> getAll(int tag) {
        List<String> out = new ArrayList<>(2);
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == tag) {
                out.add(values[i]);
            }
        }
        return out;
    }

    /** Number of fields, header and trailer included. */
    public int fieldCount() {
        return tags.length;
    }

    /** Tag of the {@code i}-th field in wire order. */
    public int tagAt(int i) {
        return tags[i];
    }

    /** Value of the {@code i}-th field in wire order. */
    public String valueAt(int i) {
        return values[i];
    }

    /** First occurrence of {@code tag}; throws if absent. */
    public String getString(int tag) {
        String v = first.get(tag);
        if (v == null) {
            throw new IllegalArgumentException("missing tag " + tag + " in " + this);
        }
        return v;
    }

    public String getString(int tag, String defaultValue) {
        return first.getOrDefault(tag, defaultValue);
    }

    public long getLong(int tag) {
        return Long.parseLong(getString(tag));
    }

    public double getDouble(int tag) {
        return Double.parseDouble(getString(tag));
    }

    public double getDouble(int tag, double defaultValue) {
        String v = first.get(tag);
        return v == null ? defaultValue : Double.parseDouble(v);
    }

    public char getChar(int tag) {
        return getString(tag).charAt(0);
    }

    /** True when {@code tag} is present with value "Y". */
    public boolean isFlagSet(int tag) {
        return "Y".equals(first.get(tag));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tags.length; i++) {
            sb.append(tags[i]).append('=').append(values[i]).append('|');
        }
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

        /** Rebuilds a builder from a stored message body (resend path). */
        static Builder restore(String msgType, String storedBody) {
            Builder b = new Builder(msgType);
            b.body.append(storedBody);
            return b;
        }

        /** Raw body fields (for the session's outbound message store). */
        String body() {
            return body.toString();
        }

        /**
         * Appends {@code tag=value}. A value containing SOH would split into
         * two fields on the wire, so it is refused; send such content with
         * {@link #dataField(int, int, String)}.
         */
        public Builder field(int tag, String value) {
            if (value.indexOf(SOH) >= 0) {
                throw new IllegalArgumentException("tag " + tag
                        + " value contains SOH; use dataField with its length tag");
            }
            body.append(tag).append('=').append(value).append(SOH);
            return this;
        }

        /**
         * Appends a length-prefixed data field pair, for example
         * {@code dataField(RAW_DATA_LENGTH, RAW_DATA, value)}. The value may
         * contain SOH; characters are written as ISO-8859-1 bytes.
         */
        public Builder dataField(int lengthTag, int dataTag, String value) {
            body.append(lengthTag).append('=').append(value.length()).append(SOH)
                    .append(dataTag).append('=').append(value).append(SOH);
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
            return encode(senderCompId, targetCompId, seqNum, sendingTimeUtc, false, null);
        }

        /**
         * Framing with resend header fields: {@code possDup} sets
         * PossDupFlag(43)=Y and {@code origSendingTime} adds
         * OrigSendingTime(122), both required when replaying stored messages.
         */
        public byte[] encode(String senderCompId, String targetCompId, long seqNum,
                             String sendingTimeUtc, boolean possDup, String origSendingTime) {
            String afterLength = "35=" + msgType + SOH
                    + "49=" + senderCompId + SOH
                    + "56=" + targetCompId + SOH
                    + "34=" + seqNum + SOH
                    + (possDup ? "43=Y" + SOH : "")
                    + "52=" + sendingTimeUtc + SOH
                    + (origSendingTime != null ? "122=" + origSendingTime + SOH : "")
                    + body;
            String head = "8=" + BEGIN_STRING + SOH + "9=" + afterLength.length() + SOH;
            byte[] payload = (head + afterLength).getBytes(StandardCharsets.ISO_8859_1);
            int sum = 0;
            for (byte b : payload) {
                sum += b & 0xFF;
            }
            int cs = sum % 256;
            byte[] full = Arrays.copyOf(payload, payload.length + 7);
            int i = payload.length;
            full[i] = '1';
            full[i + 1] = '0';
            full[i + 2] = '=';
            full[i + 3] = (byte) ('0' + cs / 100);
            full[i + 4] = (byte) ('0' + cs / 10 % 10);
            full[i + 5] = (byte) ('0' + cs % 10);
            full[i + 6] = SOH;
            return full;
        }
    }

    private static String printable(byte[] raw) {
        String s = new String(raw, StandardCharsets.ISO_8859_1).replace(SOH, '|');
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}

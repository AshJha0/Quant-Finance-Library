package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Garbage-free FIX 4.4 NewOrderSingle encoder — the hot-lane counterpart of
 * the String-based {@link NewOrderSingle}/{@link FixMessage} codec, for
 * venues that only speak FIX (where the {@code sbe} binary adapters aren't
 * an option, order entry IS the FIX edge, and per-order String building
 * would put allocation back on the measured path).
 *
 * <p>Techniques, all standard in commercial garbage-free FIX engines:</p>
 * <ul>
 *   <li><b>One reusable byte buffer</b> — the body is written at a fixed
 *       offset, then the {@code 8=FIX.4.4|9=len|} prefix is written
 *       backwards in front of it (no copy, no second pass), and
 *       {@code 10=checksum|} appended;</li>
 *   <li><b>ASCII digit writers</b> — longs and scaled decimal prices are
 *       rendered digit-by-digit; no {@code Long.toString}, no format calls;</li>
 *   <li><b>Prices as scaled longs</b> — {@code mantissa × 10^-decimals}
 *       (e.g. 1.08505 = mantissa 108505, decimals 5), the venue-grade
 *       representation; doubles never touch the encoder;</li>
 *   <li><b>Cached timestamp date part</b> — the {@code yyyyMMdd-} prefix is
 *       recomputed only when the UTC day changes (once per day, off the
 *       per-order path); intraday time renders from millis by division;</li>
 *   <li><b>Symbols pre-registered</b> — dense symbol id → ASCII bytes at
 *       setup; the hot path never encodes a String.</li>
 * </ul>
 *
 * <p>Correctness is pinned by round-trip tests: every encoded message is
 * parsed back by the validated {@link FixMessage#parse} (which checks
 * BodyLength and CheckSum) and field-compared. Zero allocation per encode
 * is asserted with the allocation-counter test, like every hot-path claim
 * in this library. Single-threaded: one encoder per session, like the
 * session itself.</p>
 */
public final class FixOrderEncoder {

    private static final byte SOH = 1;
    /** Room reserved for {@code 8=FIX.4.4|9=<len>|} written backwards. */
    private static final int PREFIX_RESERVE = 32;

    private final byte[] buffer;
    private final byte[] header34; // "35=D|49=SENDER|56=TARGET|34=" — constant per session
    private final byte[][] symbols;
    private int start;
    private int end;

    // Timestamp cache: the yyyyMMdd- prefix, refreshed when the UTC day rolls.
    private final byte[] datePrefix = new byte[9];
    private long cachedEpochDay = Long.MIN_VALUE;

    /**
     * @param senderCompId session sender (tag 49)
     * @param targetCompId session target (tag 56)
     * @param maxSymbols   dense symbol-id capacity
     * @param bufferSize   message buffer (512 is ample for a NewOrderSingle)
     */
    public FixOrderEncoder(String senderCompId, String targetCompId, int maxSymbols,
                           int bufferSize) {
        if (senderCompId.isEmpty() || targetCompId.isEmpty() || maxSymbols <= 0
                || bufferSize < 256) {
            throw new IllegalArgumentException(
                    "need comp ids, maxSymbols > 0, bufferSize >= 256");
        }
        this.buffer = new byte[bufferSize];
        this.symbols = new byte[maxSymbols][];
        // The constant chunk of every message: 35=D|49=...|56=...|34=
        String head = "35=D" + (char) SOH + "49=" + senderCompId + (char) SOH
                + "56=" + targetCompId + (char) SOH + "34=";
        this.header34 = head.getBytes(StandardCharsets.US_ASCII);
    }

    /** Registers a tradeable symbol (cold path, before trading). */
    public FixOrderEncoder registerSymbol(int symbolId, String symbol) {
        symbols[symbolId] = symbol.getBytes(StandardCharsets.US_ASCII);
        return this;
    }

    // ------------------------------------------------------------------
    // The hot path
    // ------------------------------------------------------------------

    /**
     * Encodes a limit NewOrderSingle into the reusable buffer. Zero
     * allocation. The encoded message occupies {@code [offset(), offset() +
     * length())} of {@link #buffer()} until the next encode.
     *
     * @param msgSeqNum      session sequence number (tag 34)
     * @param clOrdId        client order id, numeric (tag 11)
     * @param priceMantissa  price × 10^{@code priceDecimals} as a long
     * @param priceDecimals  decimal places (5 for EURUSD, 3 for USDJPY)
     * @param epochMillis    UTC time for tags 52/60
     * @return message length in bytes
     */
    public int encodeLimit(long msgSeqNum, long clOrdId, int symbolId, Side side,
                           long quantity, long priceMantissa, int priceDecimals,
                           long epochMillis) {
        return encode(msgSeqNum, clOrdId, symbolId, side, quantity,
                priceMantissa, priceDecimals, false, epochMillis);
    }

    /** Market NewOrderSingle (40=1, no price tag). Zero allocation. */
    public int encodeMarket(long msgSeqNum, long clOrdId, int symbolId, Side side,
                            long quantity, long epochMillis) {
        return encode(msgSeqNum, clOrdId, symbolId, side, quantity, 0, 0, true, epochMillis);
    }

    private int encode(long msgSeqNum, long clOrdId, int symbolId, Side side, long quantity,
                       long priceMantissa, int priceDecimals, boolean market,
                       long epochMillis) {
        byte[] symbol = symbols[symbolId];
        if (symbol == null) {
            throw new IllegalStateException("symbol id " + symbolId + " not registered");
        }
        refreshDate(epochMillis);

        // ---- body: 35=D|49|56|34=seq|52=ts|11|55|54|38|40[|44]|60=ts| ----
        int p = PREFIX_RESERVE;
        p = raw(p, header34);
        p = digits(p, msgSeqNum);
        buffer[p++] = SOH;
        p = tag(p, '5', '2');
        p = timestamp(p, epochMillis);
        buffer[p++] = SOH;
        p = tag(p, '1', '1');
        p = digits(p, clOrdId);
        buffer[p++] = SOH;
        p = tag(p, '5', '5');
        p = raw(p, symbol);
        buffer[p++] = SOH;
        p = tag(p, '5', '4');
        buffer[p++] = side == Side.BUY ? (byte) '1' : (byte) '2';
        buffer[p++] = SOH;
        p = tag(p, '3', '8');
        p = digits(p, quantity);
        buffer[p++] = SOH;
        p = tag(p, '4', '0');
        buffer[p++] = market ? (byte) '1' : (byte) '2';   // 1=market, 2=limit
        buffer[p++] = SOH;
        if (!market) {
            p = tag(p, '4', '4');
            p = price(p, priceMantissa, priceDecimals);
            buffer[p++] = SOH;
        }
        p = tag(p, '6', '0');
        p = timestamp(p, epochMillis);
        buffer[p++] = SOH;
        int bodyEnd = p;

        // ---- prefix, written BACKWARDS in front of the body: 8=..|9=len| ----
        int bodyLen = bodyEnd - PREFIX_RESERVE;
        int q = PREFIX_RESERVE;
        buffer[--q] = SOH;
        long len = bodyLen;
        do {
            buffer[--q] = (byte) ('0' + (int) (len % 10));
            len /= 10;
        } while (len > 0);
        buffer[--q] = '=';
        buffer[--q] = '9';
        buffer[--q] = SOH;
        buffer[--q] = '4';
        buffer[--q] = '.';
        buffer[--q] = '4';
        buffer[--q] = '.';
        buffer[--q] = 'X';
        buffer[--q] = 'I';
        buffer[--q] = 'F';
        buffer[--q] = '=';
        buffer[--q] = '8';
        this.start = q;

        // ---- trailer: 10=NNN| over everything from 8= through the body ----
        int sum = 0;
        for (int i = start; i < bodyEnd; i++) {
            sum += buffer[i];
        }
        sum &= 0xFF;
        p = bodyEnd;
        p = tag(p, '1', '0');
        buffer[p++] = (byte) ('0' + sum / 100);
        buffer[p++] = (byte) ('0' + (sum / 10) % 10);
        buffer[p++] = (byte) ('0' + sum % 10);
        buffer[p++] = SOH;
        this.end = p;
        return end - start;
    }

    // ------------------------------------------------------------------
    // Buffer access (valid until the next encode)
    // ------------------------------------------------------------------

    /** The reusable buffer holding the last encoded message. */
    public byte[] buffer() {
        return buffer;
    }

    /** Start offset of the last message within {@link #buffer()}. */
    public int offset() {
        return start;
    }

    /** Length of the last message. */
    public int length() {
        return end - start;
    }

    // ------------------------------------------------------------------
    // ASCII writers (all zero-allocation)
    // ------------------------------------------------------------------

    /** Writes {@code XY=}. */
    private int tag(int p, char a, char b) {
        buffer[p++] = (byte) a;
        buffer[p++] = (byte) b;
        buffer[p++] = '=';
        return p;
    }

    private int raw(int p, byte[] bytes) {
        System.arraycopy(bytes, 0, buffer, p, bytes.length);
        return p + bytes.length;
    }

    /** Non-negative long as ASCII digits. */
    private int digits(int p, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value: " + value);
        }
        // Count digits, then fill right-to-left — no temporary buffer.
        int n = 1;
        for (long v = value; v >= 10; v /= 10) {
            n++;
        }
        int endPos = p + n;
        long v = value;
        for (int i = endPos - 1; i >= p; i--) {
            buffer[i] = (byte) ('0' + (int) (v % 10));
            v /= 10;
        }
        return endPos;
    }

    /** Scaled decimal: mantissa 108505, decimals 5 → {@code 1.08505}. */
    private int price(int p, long mantissa, int decimals) {
        if (mantissa < 0 || decimals < 0) {
            throw new IllegalArgumentException("mantissa and decimals must be >= 0");
        }
        if (decimals == 0) {
            return digits(p, mantissa);
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        p = digits(p, mantissa / scale);      // integer part (0 included)
        buffer[p++] = '.';
        // Fractional part, zero-padded to exactly `decimals` digits.
        long frac = mantissa % scale;
        for (long s = scale / 10; s > 0; s /= 10) {
            buffer[p++] = (byte) ('0' + (int) (frac / s));
            frac %= s;
        }
        return p;
    }

    /** {@code yyyyMMdd-HH:mm:ss.SSS} — date part cached per UTC day. */
    private int timestamp(int p, long epochMillis) {
        System.arraycopy(datePrefix, 0, buffer, p, 9);
        p += 9;
        int millisOfDay = (int) (epochMillis - cachedEpochDay * 86_400_000L);
        int hh = millisOfDay / 3_600_000;
        int mm = (millisOfDay / 60_000) % 60;
        int ss = (millisOfDay / 1_000) % 60;
        int ms = millisOfDay % 1_000;
        buffer[p++] = (byte) ('0' + hh / 10);
        buffer[p++] = (byte) ('0' + hh % 10);
        buffer[p++] = ':';
        buffer[p++] = (byte) ('0' + mm / 10);
        buffer[p++] = (byte) ('0' + mm % 10);
        buffer[p++] = ':';
        buffer[p++] = (byte) ('0' + ss / 10);
        buffer[p++] = (byte) ('0' + ss % 10);
        buffer[p++] = '.';
        buffer[p++] = (byte) ('0' + ms / 100);
        buffer[p++] = (byte) ('0' + (ms / 10) % 10);
        buffer[p++] = (byte) ('0' + ms % 10);
        return p;
    }

    /** Refreshes the cached {@code yyyyMMdd-} prefix when the UTC day rolls. */
    private void refreshDate(long epochMillis) {
        long epochDay = Math.floorDiv(epochMillis, 86_400_000L);
        if (epochDay != cachedEpochDay) {
            // Cold path: once per day. Allocation here is fine and expected.
            String date = LocalDate.ofEpochDay(epochDay)
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
            for (int i = 0; i < 8; i++) {
                datePrefix[i] = (byte) date.charAt(i);
            }
            datePrefix[8] = '-';
            cachedEpochDay = epochDay;
        }
    }
}

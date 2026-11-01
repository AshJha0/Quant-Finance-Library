package com.quantfinlib.marketdata;

/**
 * ITCH 5.0 binary market-data codec, after the Nasdaq TotalView-ITCH 5.0
 * specification (2020 revision): exact field layouts and big-endian
 * encoding for the messages that move a full-depth (L3) book (A, F, E, C,
 * X, D, U), the trade prints (P non-cross, Q cross, B broken), and the
 * session and state messages (S system event, H trading action);
 * {@link #length} also knows the wire length of every other 5.0 message
 * (R, Y, L, V, W, K, J, h, I, N, O) so a decoder can skip them. Not a
 * certified implementation.
 *
 * <p>Bounds: {@link #complete} tells whether a whole message of the type at
 * an offset lies inside the available bytes; decoders call it before
 * wrapping, so a short datagram is skipped instead of reading past the
 * buffer.</p>
 *
 * <p>Decoding is a flyweight: {@link View#wrap} points at a message inside a
 * caller-owned buffer and every getter reads the bytes directly — no parsing
 * step, no objects, zero allocation. Symbols travel as a {@code long} of 8
 * ASCII bytes ({@link #packStock}) so the hot path never touches
 * {@link String}.</p>
 *
 * <p>Prices are unsigned 32-bit integers with four implied decimals — i.e.
 * the raw value <em>is</em> the price in 0.0001 ticks, which plugs straight
 * into tick-indexed books like {@code L3BookBuilder}. <b>Domain limit</b>:
 * this library's tick-indexed pipeline is signed-{@code int} throughout, so
 * prices above 2³¹−1 ticks ($214,748.36) decode negative and are dropped by
 * band checks downstream — symbols above that price (BRK.A territory) need
 * a coarser tick or a scaled feed, the same constraint as
 * {@code HftOrderBook}'s ladder.</p>
 *
 * <p>Encoders exist for simulators, replay tooling and tests; a production
 * participant only decodes.</p>
 */
public final class ItchCodec {

    public static final byte ADD = 'A';
    public static final byte ADD_MPID = 'F';
    public static final byte EXECUTED = 'E';
    public static final byte CANCEL = 'X';
    public static final byte DELETE = 'D';
    public static final byte REPLACE = 'U';
    public static final byte TRADE = 'P';
    public static final byte EXECUTED_WITH_PRICE = 'C';
    public static final byte SYSTEM_EVENT = 'S';
    public static final byte TRADING_ACTION = 'H';
    public static final byte CROSS_TRADE = 'Q';
    public static final byte BROKEN_TRADE = 'B';

    /** Trading states of the H message (ITCH 5.0 section 1.2.2). */
    public static final byte STATE_HALTED = 'H';
    public static final byte STATE_PAUSED = 'P';
    public static final byte STATE_QUOTATION_ONLY = 'Q';
    public static final byte STATE_TRADING = 'T';

    /** System event codes of the S message (ITCH 5.0 section 1.1). */
    public static final byte EVENT_START_OF_MESSAGES = 'O';
    public static final byte EVENT_START_OF_SYSTEM_HOURS = 'S';
    public static final byte EVENT_START_OF_MARKET_HOURS = 'Q';
    public static final byte EVENT_END_OF_MARKET_HOURS = 'M';
    public static final byte EVENT_END_OF_SYSTEM_HOURS = 'E';
    public static final byte EVENT_END_OF_MESSAGES = 'C';

    public static final byte BUY = 'B';
    public static final byte SELL = 'S';

    private ItchCodec() {
    }

    /** Wire length of an ITCH 5.0 message type; -1 for an unknown type. */
    public static int length(byte type) {
        return switch (type) {
            case SYSTEM_EVENT -> 12;
            case 'R' -> 39;                 // stock directory
            case TRADING_ACTION -> 25;
            case 'Y' -> 20;                 // Reg SHO restriction
            case 'L' -> 26;                 // market participant position
            case 'V' -> 35;                 // MWCB decline level
            case 'W' -> 12;                 // MWCB status
            case 'K' -> 28;                 // IPO quoting period
            case 'J' -> 35;                 // LULD auction collar
            case 'h' -> 21;                 // operational halt
            case ADD -> 36;
            case ADD_MPID -> 40;
            case EXECUTED -> 31;
            case EXECUTED_WITH_PRICE -> 36;
            case CANCEL -> 23;
            case DELETE -> 19;
            case REPLACE -> 35;
            case TRADE -> 44;
            case CROSS_TRADE -> 40;
            case BROKEN_TRADE -> 19;
            case 'I' -> 50;                 // net order imbalance indicator
            case 'N' -> 20;                 // retail price improvement indicator
            case 'O' -> 48;                 // direct listing with capital raise
            default -> -1;
        };
    }

    /**
     * True when a whole message of the type at {@code offset} lies within
     * {@code [offset, offset + available)} and the type is known.
     */
    public static boolean complete(byte[] buf, int offset, int available) {
        if (available < 1 || offset < 0 || offset >= buf.length) {
            return false;
        }
        int len = length(buf[offset]);
        return len > 0 && len <= available && offset + len <= buf.length;
    }

    /** Packs up to 8 ASCII chars into a big-endian long, space-padded (ITCH alpha style). */
    public static long packStock(String symbol) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            char c = i < symbol.length() ? symbol.charAt(i) : ' ';
            v = (v << 8) | (c & 0xFF);
        }
        return v;
    }

    /** Inverse of {@link #packStock}: trailing spaces stripped. Test/logging use only. */
    public static String unpackStock(long packed) {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 7; i >= 0; i--) {
            sb.append((char) ((packed >>> (i * 8)) & 0xFF));
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end);
    }

    // ------------------------------------------------------------------
    // Flyweight decoder
    // ------------------------------------------------------------------

    /**
     * Mutable flyweight over one message in a caller-owned buffer. Reuse a
     * single instance per decoding thread; every getter is a direct
     * big-endian read. Getters are only meaningful for the message types
     * that carry the field (per the ITCH layout) — reading a field the type
     * does not carry returns garbage, exactly like pointing a C struct at
     * the wrong bytes. Type-specific getters {@code assert} the type, so a
     * misdispatched read fails loudly in every test run and costs nothing
     * in production (assertions disabled).
     */
    public static final class View {
        private byte[] buf;
        private int off;

        /** Points this view at a message; returns {@code this} for chaining. */
        public View wrap(byte[] buffer, int offset) {
            this.buf = buffer;
            this.off = offset;
            return this;
        }

        public byte type() {
            return buf[off];
        }

        /** Per-symbol locate code — the feed's symbol id for the day. */
        public int stockLocate() {
            return u16(off + 1);
        }

        public int trackingNumber() {
            return u16(off + 3);
        }

        /** Nanoseconds since midnight (48-bit wire field). */
        public long timestampNanos() {
            return u48(off + 5);
        }

        /** Order reference (A/F/E/C/X/D/P, and the original ref of U via {@link #origRef}). */
        public long orderRef() {
            assert "AFECXDUP".indexOf(buf[off]) >= 0 : "no orderRef on type " + (char) buf[off];
            return u64(off + 11);
        }

        /** U only: the replaced (original) order reference. */
        public long origRef() {
            assert buf[off] == REPLACE : "origRef only on U, not " + (char) buf[off];
            return u64(off + 11);
        }

        /** U only: the new order reference. */
        public long newRef() {
            assert buf[off] == REPLACE : "newRef only on U, not " + (char) buf[off];
            return u64(off + 19);
        }

        /** A/F/P: {@link #BUY} or {@link #SELL}. */
        public byte side() {
            assert "AFP".indexOf(buf[off]) >= 0 : "no side on type " + (char) buf[off];
            return buf[off + 19];
        }

        /** A/F/P: displayed shares. U: the new total shares. */
        public long shares() {
            assert "AFPU".indexOf(buf[off]) >= 0 : "no shares on type " + (char) buf[off];
            return switch (buf[off]) {
                case REPLACE -> u32(off + 27);
                default -> u32(off + 20);
            };
        }

        /** E/C: executed shares. X: cancelled shares. */
        public long deltaShares() {
            assert buf[off] == EXECUTED || buf[off] == EXECUTED_WITH_PRICE || buf[off] == CANCEL
                    : "no deltaShares on type " + (char) buf[off];
            return u32(off + 19);
        }

        /** A/F/P: symbol as 8 packed ASCII bytes (compare against {@link #packStock}). */
        public long stock() {
            assert "AFP".indexOf(buf[off]) >= 0 : "no stock on type " + (char) buf[off];
            return u64(off + 24);
        }

        /**
         * Price in 0.0001 ticks (A/F/P; U: the new price). Signed-int
         * domain: wire prices above 2³¹−1 ticks decode negative (see the
         * class doc's domain-limit note).
         */
        public int priceTick() {
            assert "AFPU".indexOf(buf[off]) >= 0 : "no price on type " + (char) buf[off];
            return switch (buf[off]) {
                case REPLACE -> (int) u32(off + 31);
                default -> (int) u32(off + 32);
            };
        }

        /** E/C/P/Q/B: the venue's match (execution) number. */
        public long matchNumber() {
            assert "ECPQB".indexOf(buf[off]) >= 0
                    : "no matchNumber on type " + (char) buf[off];
            return switch (buf[off]) {
                case TRADE -> u64(off + 36);
                case CROSS_TRADE -> u64(off + 31);
                case BROKEN_TRADE -> u64(off + 11);
                default -> u64(off + 23);
            };
        }

        /** C: printable flag, 'Y' when the execution should print to the tape. */
        public byte printable() {
            assert buf[off] == EXECUTED_WITH_PRICE : "no printable on type " + (char) buf[off];
            return buf[off + 31];
        }

        /** C: execution price in 0.0001 ticks. */
        public int executionPriceTick() {
            assert buf[off] == EXECUTED_WITH_PRICE : "no execution price on " + (char) buf[off];
            return (int) u32(off + 32);
        }

        /** S: system event code ({@link #EVENT_START_OF_MESSAGES} and so on). */
        public byte eventCode() {
            assert buf[off] == SYSTEM_EVENT : "no event code on type " + (char) buf[off];
            return buf[off + 11];
        }

        /** H: trading state ({@link #STATE_HALTED}, {@link #STATE_TRADING}, ...). */
        public byte tradingState() {
            assert buf[off] == TRADING_ACTION : "no trading state on type " + (char) buf[off];
            return buf[off + 19];
        }

        /** H/Q: symbol as 8 packed ASCII bytes. */
        public long stateStock() {
            assert buf[off] == TRADING_ACTION || buf[off] == CROSS_TRADE
                    : "no stock on type " + (char) buf[off];
            return buf[off] == CROSS_TRADE ? u64(off + 19) : u64(off + 11);
        }

        /** Q: shares matched in the cross (64-bit on the wire). */
        public long crossShares() {
            assert buf[off] == CROSS_TRADE : "no cross shares on type " + (char) buf[off];
            return u64(off + 11);
        }

        /** Q: cross price in 0.0001 ticks. */
        public int crossPriceTick() {
            assert buf[off] == CROSS_TRADE : "no cross price on type " + (char) buf[off];
            return (int) u32(off + 27);
        }

        /** Q: cross type ('O' opening, 'C' closing, 'H' halt/IPO, 'I' intraday). */
        public byte crossType() {
            assert buf[off] == CROSS_TRADE : "no cross type on type " + (char) buf[off];
            return buf[off + 39];
        }

        private int u16(int i) {
            return ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        }

        private long u32(int i) {
            return ((long) (buf[i] & 0xFF) << 24) | ((buf[i + 1] & 0xFF) << 16)
                    | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
        }

        private long u48(int i) {
            return ((long) u16(i) << 32) | u32(i + 2);
        }

        private long u64(int i) {
            return (u32(i) << 32) | u32(i + 4);
        }
    }

    // ------------------------------------------------------------------
    // Encoders (simulator / replay / test side)
    // ------------------------------------------------------------------

    /** Encodes an Add Order (A); returns bytes written. */
    public static int encodeAdd(byte[] buf, int off, int stockLocate, long timestampNanos,
                                long orderRef, byte side, long shares, long packedStock,
                                int priceTick) {
        header(buf, off, ADD, stockLocate, timestampNanos);
        p64(buf, off + 11, orderRef);
        buf[off + 19] = side;
        p32(buf, off + 20, shares);
        p64(buf, off + 24, packedStock);
        p32(buf, off + 32, priceTick);
        return 36;
    }

    /** Encodes an Order Executed (E); returns bytes written. */
    public static int encodeExecuted(byte[] buf, int off, int stockLocate, long timestampNanos,
                                     long orderRef, long executedShares, long matchNumber) {
        header(buf, off, EXECUTED, stockLocate, timestampNanos);
        p64(buf, off + 11, orderRef);
        p32(buf, off + 19, executedShares);
        p64(buf, off + 23, matchNumber);
        return 31;
    }

    /** Encodes an Order Cancel (X, partial cancel); returns bytes written. */
    public static int encodeCancel(byte[] buf, int off, int stockLocate, long timestampNanos,
                                   long orderRef, long cancelledShares) {
        header(buf, off, CANCEL, stockLocate, timestampNanos);
        p64(buf, off + 11, orderRef);
        p32(buf, off + 19, cancelledShares);
        return 23;
    }

    /** Encodes an Order Delete (D); returns bytes written. */
    public static int encodeDelete(byte[] buf, int off, int stockLocate, long timestampNanos,
                                   long orderRef) {
        header(buf, off, DELETE, stockLocate, timestampNanos);
        p64(buf, off + 11, orderRef);
        return 19;
    }

    /** Encodes an Order Replace (U); returns bytes written. */
    public static int encodeReplace(byte[] buf, int off, int stockLocate, long timestampNanos,
                                    long origRef, long newRef, long shares, int priceTick) {
        header(buf, off, REPLACE, stockLocate, timestampNanos);
        p64(buf, off + 11, origRef);
        p64(buf, off + 19, newRef);
        p32(buf, off + 27, shares);
        p32(buf, off + 31, priceTick);
        return 35;
    }

    /** Encodes a non-cross Trade (P); returns bytes written. */
    public static int encodeTrade(byte[] buf, int off, int stockLocate, long timestampNanos,
                                  long orderRef, byte side, long shares, long packedStock,
                                  int priceTick, long matchNumber) {
        header(buf, off, TRADE, stockLocate, timestampNanos);
        p64(buf, off + 11, orderRef);
        buf[off + 19] = side;
        p32(buf, off + 20, shares);
        p64(buf, off + 24, packedStock);
        p32(buf, off + 32, priceTick);
        p64(buf, off + 36, matchNumber);
        return 44;
    }

    /** Encodes an Order Executed With Price (C); returns bytes written. */
    public static int encodeExecutedWithPrice(byte[] buf, int off, int stockLocate,
                                              long timestampNanos, long orderRef,
                                              long executedShares, long matchNumber,
                                              boolean printable, int priceTick) {
        header(buf, off, EXECUTED_WITH_PRICE, stockLocate, timestampNanos);
        p64(buf, off + 11, orderRef);
        p32(buf, off + 19, executedShares);
        p64(buf, off + 23, matchNumber);
        buf[off + 31] = (byte) (printable ? 'Y' : 'N');
        p32(buf, off + 32, priceTick);
        return 36;
    }

    /** Encodes a System Event (S, stock locate 0); returns bytes written. */
    public static int encodeSystemEvent(byte[] buf, int off, long timestampNanos, byte eventCode) {
        header(buf, off, SYSTEM_EVENT, 0, timestampNanos);
        buf[off + 11] = eventCode;
        return 12;
    }

    /** Encodes a Stock Trading Action (H); returns bytes written. */
    public static int encodeTradingAction(byte[] buf, int off, int stockLocate,
                                          long timestampNanos, long packedStock,
                                          byte tradingState, int reasonCode) {
        header(buf, off, TRADING_ACTION, stockLocate, timestampNanos);
        p64(buf, off + 11, packedStock);
        buf[off + 19] = tradingState;
        buf[off + 20] = ' ';
        p32(buf, off + 21, reasonCode & 0xFFFFFFFFL);
        return 25;
    }

    /** Encodes a Cross Trade (Q); returns bytes written. */
    public static int encodeCrossTrade(byte[] buf, int off, int stockLocate, long timestampNanos,
                                       long shares, long packedStock, int crossPriceTick,
                                       long matchNumber, byte crossType) {
        header(buf, off, CROSS_TRADE, stockLocate, timestampNanos);
        p64(buf, off + 11, shares);
        p64(buf, off + 19, packedStock);
        p32(buf, off + 27, crossPriceTick);
        p64(buf, off + 31, matchNumber);
        buf[off + 39] = crossType;
        return 40;
    }

    /** Encodes a Broken Trade (B); returns bytes written. */
    public static int encodeBrokenTrade(byte[] buf, int off, int stockLocate, long timestampNanos,
                                        long matchNumber) {
        header(buf, off, BROKEN_TRADE, stockLocate, timestampNanos);
        p64(buf, off + 11, matchNumber);
        return 19;
    }

    private static void header(byte[] buf, int off, byte type, int locate, long tsNanos) {
        buf[off] = type;
        buf[off + 1] = (byte) (locate >>> 8);
        buf[off + 2] = (byte) locate;
        buf[off + 3] = 0;                        // tracking number (unused here)
        buf[off + 4] = 0;
        buf[off + 5] = (byte) (tsNanos >>> 40);
        buf[off + 6] = (byte) (tsNanos >>> 32);
        p32(buf, off + 7, tsNanos & 0xFFFFFFFFL);
    }

    private static void p32(byte[] buf, int off, long v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    private static void p64(byte[] buf, int off, long v) {
        p32(buf, off, v >>> 32);
        p32(buf, off + 4, v & 0xFFFFFFFFL);
    }
}

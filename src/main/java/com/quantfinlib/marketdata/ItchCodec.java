package com.quantfinlib.marketdata;

/**
 * ITCH 5.0-style binary market-data codec: the message subset that drives a
 * full-depth (L3) book — add, add-with-attribution, execute, cancel, delete,
 * replace, and off-book trade — with the exact field layout and big-endian
 * encoding of the Nasdaq TotalView-ITCH 5.0 specification. Styled after the
 * spec for realism; not a certified implementation.
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

    public static final byte BUY = 'B';
    public static final byte SELL = 'S';

    private ItchCodec() {
    }

    /** Wire length of a message type; -1 for types outside the subset. */
    public static int length(byte type) {
        return switch (type) {
            case ADD -> 36;
            case ADD_MPID -> 40;
            case EXECUTED -> 31;
            case CANCEL -> 23;
            case DELETE -> 19;
            case REPLACE -> 35;
            case TRADE -> 44;
            default -> -1;
        };
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

        /** Order reference (A/F/E/X/D/P, and the original ref of U via {@link #origRef}). */
        public long orderRef() {
            assert "AFEXDUP".indexOf(buf[off]) >= 0 : "no orderRef on type " + (char) buf[off];
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

        /** E: executed shares. X: cancelled shares. */
        public long deltaShares() {
            assert buf[off] == EXECUTED || buf[off] == CANCEL
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

        /** E/P: the venue's match (execution) number. */
        public long matchNumber() {
            assert buf[off] == EXECUTED || buf[off] == TRADE
                    : "no matchNumber on type " + (char) buf[off];
            return switch (buf[off]) {
                case TRADE -> u64(off + 36);
                default -> u64(off + 23);
            };
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

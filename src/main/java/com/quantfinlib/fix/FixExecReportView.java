package com.quantfinlib.fix;

/**
 * Garbage-free FIX 4.4 ExecutionReport reader — the inbound half of the
 * FIX hot path, completing the round trip {@link FixOrderEncoder} started:
 * order out garbage-free, fill in garbage-free. Where {@link FixMessage}
 * materializes Strings per tag (right for session management and
 * research), this is a <em>flyweight view</em>: {@link #wrap} performs one
 * pass over the framed bytes recording primitive values and field
 * positions, and every getter returns a primitive. Nothing is allocated
 * after construction.
 *
 * <p>The fields extracted are exactly what a fill handler on the trading
 * path needs to call {@code HftRiskGate.onFill} and update order state:
 * ClOrdID (numeric, as issued by {@code FixOrderEncoder}), ExecType,
 * OrdStatus, Side, LastQty, LastPx, CumQty, LeavesQty, AvgPx, plus what
 * recovery and diagnosis need: ExecID(17) and OrderID(37) as bytes in place,
 * PossDupFlag(43), PossResend(97), OrdRejReason(103) and Text(58). After a
 * ResendRequest the venue replays fills with PossDupFlag=Y; feed every fill
 * through an {@link ExecIdDeduplicator} so a replay is not applied twice.
 * Prices come back as
 * <b>scaled longs</b> ({@code lastPxMantissa()} × 10^-{@code lastPxDecimals()})
 * — the same representation the encoder takes in, so the round trip never
 * touches a double. The symbol is exposed as bytes-in-place for comparison
 * against a registered table, never as a String.</p>
 *
 * <p>Assumes an already-framed message (the {@link FixDecoder} owns
 * framing and checksum). Single-threaded, one view per session thread —
 * wrap, read, repeat.</p>
 */
public final class FixExecReportView {

    private static final byte SOH = 1;

    private byte[] buf;
    private boolean execReport;
    private long clOrdId;
    private byte execType;
    private byte ordStatus;
    private byte side;
    private long lastQty;
    private long cumQty;
    private long leavesQty;
    private long lastPxMantissa;
    private int lastPxDecimals;
    private int symbolOffset;
    private int symbolLength;
    private int execIdOffset;
    private int execIdLength;
    private int orderIdOffset;
    private int orderIdLength;
    private int textOffset;
    private int textLength;
    private boolean possDup;
    private boolean possResend;
    private int ordRejReason;
    private long avgPxMantissa;
    private int avgPxDecimals;

    /**
     * Parses one framed message in place. Returns {@code true} when it is
     * an ExecutionReport (35=8); other message types return {@code false}
     * and leave the getters undefined. Zero allocation. The buffer is not
     * copied: the caller keeps ownership and must not overwrite those bytes
     * while reading through this view.
     */
    public boolean wrap(byte[] buffer, int offset, int length) {
        this.buf = buffer;
        this.execReport = false;
        this.clOrdId = -1;
        this.execType = 0;
        this.ordStatus = 0;
        this.side = 0;
        this.lastQty = 0;
        this.cumQty = 0;
        this.leavesQty = 0;
        this.lastPxMantissa = 0;
        this.lastPxDecimals = 0;
        this.symbolOffset = -1;
        this.symbolLength = 0;
        this.execIdOffset = -1;
        this.execIdLength = 0;
        this.orderIdOffset = -1;
        this.orderIdLength = 0;
        this.textOffset = -1;
        this.textLength = 0;
        this.possDup = false;
        this.possResend = false;
        this.ordRejReason = -1;
        this.avgPxMantissa = 0;
        this.avgPxDecimals = 0;

        int end = offset + length;
        int p = offset;
        while (p < end) {
            // ---- tag: digits up to '=' ----
            int tag = 0;
            while (p < end && buffer[p] != '=') {
                byte b = buffer[p];
                // A non-digit tag is skipped as a whole (tag -1), never
                // folded into a plausible-looking number.
                tag = tag < 0 || b < '0' || b > '9' || tag > 100_000_000 ? -1 : tag * 10 + (b - '0');
                p++;
            }
            p++; // skip '='
            int valueStart = p;
            while (p < end && buffer[p] != SOH) {
                p++;
            }
            int valueEnd = p;
            p++; // skip SOH

            switch (tag) {
                case FixMessage.MSG_TYPE -> {
                    // 35=8 and nothing else; a one-byte value is required.
                    execReport = valueEnd - valueStart == 1 && buffer[valueStart] == '8';
                    if (!execReport) {
                        return false; // not ours: stop scanning immediately
                    }
                }
                // ClOrdID is free-format FIX: numeric when WE issued it
                // (FixOrderEncoder), counterparty-format on unsolicited
                // reports — those map to the existing -1 "not ours"
                // sentinel instead of throwing mid-wrap.
                case FixMessage.CL_ORD_ID ->
                        clOrdId = FixParse.parseLongOrElse(buf, valueStart, valueEnd, -1);
                case FixMessage.EXEC_TYPE -> execType = buffer[valueStart];
                case FixMessage.ORD_STATUS -> ordStatus = buffer[valueStart];
                case FixMessage.SIDE -> side = buffer[valueStart];
                case FixMessage.LAST_QTY -> lastQty = parseLong(valueStart, valueEnd);
                case FixMessage.CUM_QTY -> cumQty = parseLong(valueStart, valueEnd);
                case FixMessage.LEAVES_QTY -> leavesQty = parseLong(valueStart, valueEnd);
                case FixMessage.LAST_PX -> parsePrice(valueStart, valueEnd);
                case FixMessage.SYMBOL -> {
                    symbolOffset = valueStart;
                    symbolLength = valueEnd - valueStart;
                }
                case FixMessage.EXEC_ID -> {
                    execIdOffset = valueStart;
                    execIdLength = valueEnd - valueStart;
                }
                case FixMessage.ORDER_ID -> {
                    orderIdOffset = valueStart;
                    orderIdLength = valueEnd - valueStart;
                }
                case FixMessage.TEXT -> {
                    textOffset = valueStart;
                    textLength = valueEnd - valueStart;
                }
                case FixMessage.POSS_DUP_FLAG ->
                        possDup = valueEnd - valueStart == 1 && buffer[valueStart] == 'Y';
                case FixMessage.POSS_RESEND ->
                        possResend = valueEnd - valueStart == 1 && buffer[valueStart] == 'Y';
                case FixMessage.ORD_REJ_REASON ->
                        ordRejReason = (int) Math.min(Integer.MAX_VALUE, parseLong(valueStart, valueEnd));
                case FixMessage.AVG_PX -> {
                    avgPxMantissa = FixParse.priceMantissa(buf, valueStart, valueEnd);
                    avgPxDecimals = FixParse.priceDecimals(buf, valueStart, valueEnd);
                }
                default -> {
                    // Every other tag is skipped without materializing it.
                }
            }
        }
        return execReport;
    }

    // ------------------------------------------------------------------
    // Primitive getters (valid until the next wrap)
    // ------------------------------------------------------------------

    /** Numeric ClOrdID as issued by {@code FixOrderEncoder}; −1 when absent. */
    public long clOrdId() {
        return clOrdId;
    }

    /** Tag 150 as its ASCII byte ('0' new, 'F' trade, '4' canceled, ...). */
    public byte execType() {
        return execType;
    }

    /** Tag 39 as its ASCII byte. */
    public byte ordStatus() {
        return ordStatus;
    }

    /** Tag 54: '1' buy, '2' sell. */
    public byte side() {
        return side;
    }

    public long lastQty() {
        return lastQty;
    }

    public long cumQty() {
        return cumQty;
    }

    public long leavesQty() {
        return leavesQty;
    }

    /** LastPx as a scaled long: {@code mantissa × 10^-decimals}. */
    public long lastPxMantissa() {
        return lastPxMantissa;
    }

    public int lastPxDecimals() {
        return lastPxDecimals;
    }

    /** AvgPx(6) as a scaled long: {@code mantissa × 10^-decimals}; 0 when absent. */
    public long avgPxMantissa() {
        return avgPxMantissa;
    }

    public int avgPxDecimals() {
        return avgPxDecimals;
    }

    /** PossDupFlag(43)=Y: a resend replay of a message that may already have been seen. */
    public boolean possDup() {
        return possDup;
    }

    /** PossResend(97)=Y: the same content re-sent under a new sequence number. */
    public boolean possResend() {
        return possResend;
    }

    /** OrdRejReason(103), or -1 when absent. */
    public int ordRejReason() {
        return ordRejReason;
    }

    /** True when ExecID(17) was present. */
    public boolean hasExecId() {
        return execIdOffset >= 0;
    }

    /** Offset of the ExecID(17) bytes in the wrapped buffer (-1 when absent). */
    public int execIdOffset() {
        return execIdOffset;
    }

    public int execIdLength() {
        return execIdLength;
    }

    /** Offset of the OrderID(37) bytes in the wrapped buffer (-1 when absent). */
    public int orderIdOffset() {
        return orderIdOffset;
    }

    public int orderIdLength() {
        return orderIdLength;
    }

    /** Offset of the Text(58) bytes in the wrapped buffer (-1 when absent). */
    public int textOffset() {
        return textOffset;
    }

    public int textLength() {
        return textLength;
    }

    /**
     * Byte {@code i} of the wrapped buffer, for reading in-place byte fields
     * at the offsets reported by this view. The buffer itself is not handed out.
     *
     * @param i absolute index in the wrapped buffer
     * @return the byte
     */
    public byte byteAt(int i) {
        return buf[i];
    }

    /** Text(58) as a String (allocates; for logging rejects, not the fill path). */
    public String text() {
        return textOffset < 0 ? "" : new String(buf, textOffset, textLength,
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** ExecID(17) as a String (allocates; for logging). */
    public String execId() {
        return execIdOffset < 0 ? "" : new String(buf, execIdOffset, execIdLength,
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * Compares the in-place symbol bytes against a registered ASCII symbol
     * (e.g. the same table {@code FixOrderEncoder} holds) — the getter that
     * replaces a String: resolve the dense id by probing your table.
     */
    public boolean symbolEquals(byte[] asciiSymbol) {
        if (symbolOffset < 0 || asciiSymbol.length != symbolLength) {
            return false;
        }
        for (int i = 0; i < symbolLength; i++) {
            if (buf[symbolOffset + i] != asciiSymbol[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Primitive parsers
    // ------------------------------------------------------------------

    private long parseLong(int from, int to) {
        return FixParse.parseLong(buf, from, to);
    }

    /** Decimal → (mantissa, decimals) via the shared {@link FixParse} rules. */
    private void parsePrice(int from, int to) {
        this.lastPxMantissa = FixParse.priceMantissa(buf, from, to);
        this.lastPxDecimals = FixParse.priceDecimals(buf, from, to);
    }

    /**
     * Remembers the ExecIDs of the last {@code capacity} execution reports
     * and answers whether a report is new, so fills replayed after a
     * ResendRequest (PossDupFlag=Y) are not applied twice. ExecID is unique
     * per venue for the trading day (FIX 4.4 Vol 4, ExecID(17)).
     *
     * <p>Each ExecID is kept as a 128-bit fingerprint (two 64-bit FNV-1a
     * hashes with different offset bases), so a false "duplicate" needs a
     * 128-bit collision: probability about n^2 / 2^129 for n remembered ids.
     * The oldest ids are forgotten first. Open addressing with backward-shift
     * deletion; zero allocation after construction; not thread-safe.</p>
     */
    public static final class ExecIdDeduplicator {

        private static final long FNV_PRIME = 0x100000001b3L;
        private static final long BASIS_A = 0xcbf29ce484222325L;
        private static final long BASIS_B = 0x84222325cbf29ce4L;

        private final long[] ringA;
        private final long[] ringB;
        private final int[] table;       // ring index + 1, 0 = empty
        private final int mask;
        private int head;
        private int size;

        public ExecIdDeduplicator(int capacity) {
            if (capacity < 1 || capacity > 1 << 28) {
                throw new IllegalArgumentException("capacity must be in [1, 2^28]");
            }
            ringA = new long[capacity];
            ringB = new long[capacity];
            int slots = Integer.highestOneBit(capacity * 2 - 1) << 1;
            table = new int[Math.max(slots, 2)];
            mask = table.length - 1;
        }

        /**
         * True when the view's ExecID has not been seen (and records it);
         * false for a duplicate. A report without ExecID is always new.
         */
        public boolean firstSeen(FixExecReportView view) {
            if (!view.hasExecId()) {
                return true;
            }
            return firstSeen(view.buf, view.execIdOffset(), view.execIdLength());
        }

        /** As {@link #firstSeen(FixExecReportView)} for raw ExecID bytes. */
        public boolean firstSeen(byte[] id, int offset, int length) {
            long a = BASIS_A;
            long b = BASIS_B;
            for (int i = offset; i < offset + length; i++) {
                a = (a ^ (id[i] & 0xFF)) * FNV_PRIME;
                b = (b ^ (id[i] & 0xFF)) * FNV_PRIME;
            }
            b ^= length;
            int slot = home(a);
            while (table[slot] != 0) {
                int r = table[slot] - 1;
                if (ringA[r] == a && ringB[r] == b) {
                    return false;
                }
                slot = (slot + 1) & mask;
            }
            if (size == ringA.length) {
                evict(head);   // forget the oldest
            } else {
                size++;
            }
            ringA[head] = a;
            ringB[head] = b;
            slot = home(a);    // re-probe: the eviction may have shifted entries
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = head + 1;
            head = head + 1 == ringA.length ? 0 : head + 1;
            return true;
        }

        /** Number of ExecIDs remembered. */
        public int size() {
            return size;
        }

        private int home(long a) {
            return (int) (a ^ (a >>> 32)) & mask;
        }

        private void evict(int ringIndex) {
            int slot = home(ringA[ringIndex]);
            while (table[slot] != ringIndex + 1) {
                slot = (slot + 1) & mask;
            }
            int hole = slot;
            int next = (hole + 1) & mask;
            while (table[next] != 0) {
                int h = home(ringA[table[next] - 1]);
                // move the entry back when its home is not cyclically in (hole, next]
                if (((next - h) & mask) >= ((next - hole) & mask)) {
                    table[hole] = table[next];
                    hole = next;
                }
                next = (next + 1) & mask;
            }
            table[hole] = 0;
        }
    }
}

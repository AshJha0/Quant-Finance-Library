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
 * OrdStatus, Side, LastQty, LastPx, CumQty, LeavesQty. Prices come back as
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

    /**
     * Parses one framed message in place. Returns {@code true} when it is
     * an ExecutionReport (35=8); other message types return {@code false}
     * and leave the getters undefined. Zero allocation.
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

        int end = offset + length;
        int p = offset;
        while (p < end) {
            // ---- tag: digits up to '=' ----
            int tag = 0;
            while (p < end && buffer[p] != '=') {
                tag = tag * 10 + (buffer[p] - '0');
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
}

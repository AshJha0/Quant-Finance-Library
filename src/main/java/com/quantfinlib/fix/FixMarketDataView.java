package com.quantfinlib.fix;

/**
 * Garbage-free FIX 4.4 market-data reader — the feed half of the FIX hot
 * path. FIX is the lingua franca of e-FX liquidity: most bank streams and
 * many ECNs deliver prices as MarketDataSnapshotFullRefresh (35=W) and
 * MarketDataIncrementalRefresh (35=X). Where {@link FixMessage} materializes
 * Strings per tag (right for session plumbing), this is a flyweight in the
 * mold of {@link FixExecReportView}: {@link #wrap} performs one pass over
 * the framed bytes recording primitives into preallocated entry arrays, and
 * every getter is primitive.
 *
 * <p>Subset (documented, honest): single-instrument messages — one Symbol
 * (55) per message, entries carrying MDUpdateAction (279, X only; W entries
 * are implicitly NEW), MDEntryType (269: '0' bid / '1' offer), MDEntryPx
 * (270, scaled long: mantissa × 10^-decimals — the same representation the
 * order encoder takes, so feed-to-order never touches a double) and
 * MDEntrySize (271). Entry order within the message is preserved: for
 * tiered LP streams, position IS the tier. Unknown tags are skipped without
 * materializing anything.</p>
 *
 * <p>Assumes an already-framed message: {@code buffer[offset]} is the first
 * byte of a tag, every field is SOH-terminated (including the last), and
 * checksum validation happened upstream — framing belongs to the transport
 * layer (the session-lane {@code FixDecoder} materializes messages and is
 * not this flyweight's feeder). Single-threaded: one view per feed thread —
 * wrap, read, repeat. Zero allocation after construction.</p>
 */
public final class FixMarketDataView {

    /** MDUpdateAction values (279); W entries report {@link #ACTION_NEW}. */
    public static final byte ACTION_NEW = '0';
    public static final byte ACTION_CHANGE = '1';
    public static final byte ACTION_DELETE = '2';

    /** MDEntryType values (269). */
    public static final byte ENTRY_BID = '0';
    public static final byte ENTRY_OFFER = '1';

    private static final byte SOH = 1;
    private static final int MSG_TYPE = FixMessage.MSG_TYPE;
    private static final int SYMBOL = FixMessage.SYMBOL;
    private static final int NO_MD_ENTRIES = 268;
    private static final int MD_ENTRY_TYPE = 269;
    private static final int MD_ENTRY_PX = 270;
    private static final int MD_ENTRY_SIZE = 271;
    private static final int MD_UPDATE_ACTION = 279;

    private final byte[] entryAction;
    private final byte[] entryType;
    private final long[] entryPxMantissa;
    private final int[] entryPxDecimals;
    private final long[] entrySize;
    private final int maxEntries;

    private byte[] buf;
    private boolean snapshot;
    private int entryCount;
    private int declaredEntries;
    private int symbolOffset;
    private int symbolLength;
    private long droppedEntries;

    /** @param maxEntries entries retained per message (beyond that: counted, dropped) */
    public FixMarketDataView(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.maxEntries = maxEntries;
        this.entryAction = new byte[maxEntries];
        this.entryType = new byte[maxEntries];
        this.entryPxMantissa = new long[maxEntries];
        this.entryPxDecimals = new int[maxEntries];
        this.entrySize = new long[maxEntries];
    }

    /** 32 entries — deeper than any real LP tier stack. */
    public FixMarketDataView() {
        this(32);
    }

    /**
     * Parses one framed message in place. Returns {@code true} for 35=W or
     * 35=X; any other message type returns {@code false} immediately and
     * leaves the getters undefined. Zero allocation.
     */
    public boolean wrap(byte[] buffer, int offset, int length) {
        this.buf = buffer;
        this.snapshot = false;
        this.entryCount = 0;
        this.declaredEntries = 0;
        this.symbolOffset = -1;
        this.symbolLength = 0;
        boolean marketData = false;
        int entry = -1;                    // index of the entry being filled

        int end = offset + length;
        int p = offset;
        while (p < end) {
            int tag = 0;
            while (p < end && buffer[p] != '=') {
                tag = tag * 10 + (buffer[p] - '0');
                p++;
            }
            p++; // '='
            int valueStart = p;
            while (p < end && buffer[p] != SOH) {
                p++;
            }
            int valueEnd = p;
            p++; // SOH

            switch (tag) {
                case MSG_TYPE -> {
                    if (valueEnd - valueStart != 1) {
                        return false;
                    }
                    byte t = buffer[valueStart];
                    if (t == 'W') {
                        marketData = true;
                        snapshot = true;
                    } else if (t == 'X') {
                        marketData = true;
                    } else {
                        return false;      // not market data: stop scanning
                    }
                }
                case SYMBOL -> {
                    symbolOffset = valueStart;
                    symbolLength = valueEnd - valueStart;
                }
                case NO_MD_ENTRIES -> declaredEntries = (int) parseLong(valueStart, valueEnd);
                case MD_UPDATE_ACTION -> {
                    // X: the action opens each repeating-group entry.
                    entry = openEntry();
                    if (entry >= 0) {
                        entryAction[entry] = buffer[valueStart];
                    }
                }
                case MD_ENTRY_TYPE -> {
                    // W: the type opens each entry (implicitly NEW); in X the
                    // entry was already opened by its 279.
                    if (snapshot) {
                        entry = openEntry();
                        if (entry >= 0) {
                            entryAction[entry] = ACTION_NEW;
                        }
                    }
                    if (entry >= 0) {
                        entryType[entry] = buffer[valueStart];
                    }
                }
                case MD_ENTRY_PX -> {
                    if (entry >= 0) {
                        parsePrice(entry, valueStart, valueEnd);
                    }
                }
                case MD_ENTRY_SIZE -> {
                    if (entry >= 0) {
                        entrySize[entry] = parseLong(valueStart, valueEnd);
                    }
                }
                default -> {
                    // skipped without materializing
                }
            }
        }
        return marketData;
    }

    /**
     * Starts a new entry with all fields zeroed; -1 (and a drop count) past
     * {@code maxEntries}. Zeroing matters: 35=X DELETE entries legitimately
     * omit price/size, and a stale value from a previous message's entry at
     * the same index must never masquerade as present — absent fields read
     * as 0.
     */
    private int openEntry() {
        if (entryCount == maxEntries) {
            droppedEntries++;
            return -1;
        }
        int e = entryCount++;
        entryAction[e] = 0;
        entryType[e] = 0;
        entryPxMantissa[e] = 0;
        entryPxDecimals[e] = 0;
        entrySize[e] = 0;
        return e;
    }

    // ------------------------------------------------------------------
    // Getters (valid until the next wrap)
    // ------------------------------------------------------------------

    /** True for 35=W (full refresh), false for 35=X (incremental). */
    public boolean isSnapshot() {
        return snapshot;
    }

    /** Entries actually retained (bounded by the constructor's maxEntries). */
    public int entryCount() {
        return entryCount;
    }

    /** NoMDEntries (268) as declared by the sender. */
    public int declaredEntries() {
        return declaredEntries;
    }

    /** {@link #ACTION_NEW} / {@link #ACTION_CHANGE} / {@link #ACTION_DELETE}. */
    public byte action(int entry) {
        return entryAction[entry];
    }

    /** {@link #ENTRY_BID} or {@link #ENTRY_OFFER}. */
    public byte type(int entry) {
        return entryType[entry];
    }

    /**
     * MDEntryPx as a scaled long: {@code mantissa × 10^-decimals}.
     * <b>Decimals follow the wire formatting and can differ per entry</b>
     * ("1.0850" → (10850, 4), "1.085" → (1085, 3)): never apply one entry's
     * decimals to another's mantissa — compare via {@link #price} or
     * rescale explicitly.
     */
    public long pxMantissa(int entry) {
        return entryPxMantissa[entry];
    }

    public int pxDecimals(int entry) {
        return entryPxDecimals[entry];
    }

    /**
     * MDEntryPx as a double — the safe conversion for double-domain
     * consumers ({@code fx.FxTierBook}, {@code fx.AggregatedBook}). The
     * scaled-long getters remain for pipelines that never touch a double.
     */
    public double price(int entry) {
        // Clamp: a malformed wire value with >18 fractional digits must not
        // turn an accepted message into an index-out-of-bounds at read time.
        int d = Math.min(entryPxDecimals[entry], FixParse.NEG_POW10.length - 1);
        return entryPxMantissa[entry] * FixParse.NEG_POW10[d];
    }

    public long size(int entry) {
        return entrySize[entry];
    }

    /** Entries dropped across the view's lifetime for exceeding maxEntries. */
    public long droppedEntries() {
        return droppedEntries;
    }

    /** In-place symbol comparison — same discipline as {@link FixExecReportView}. */
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
    // Primitive parsers — shared with FixExecReportView via FixParse
    // ------------------------------------------------------------------

    private long parseLong(int from, int to) {
        return FixParse.parseLong(buf, from, to);
    }

    private void parsePrice(int entry, int from, int to) {
        entryPxMantissa[entry] = FixParse.priceMantissa(buf, from, to);
        entryPxDecimals[entry] = FixParse.priceDecimals(buf, from, to);
    }
}

package com.quantfinlib.fix;

/**
 * Garbage-free FIX 4.2 / 4.4 / 5.0 SP2 market-data reader — the feed half of the FIX hot
 * path. FIX is the lingua franca of e-FX liquidity: most bank streams and
 * many ECNs deliver prices as MarketDataSnapshotFullRefresh (35=W) and
 * MarketDataIncrementalRefresh (35=X). Where {@link FixMessage} materializes
 * Strings per tag (right for session plumbing), this is a flyweight in the
 * mold of {@link FixExecReportView}: {@link #wrap} performs one pass over
 * the framed bytes recording primitives into preallocated entry arrays, and
 * every getter is primitive.
 *
 * <p>Supported subset: single-instrument messages, one Symbol
 * (55) per message, entries carrying MDUpdateAction (279, X only; W entries
 * are implicitly NEW), MDEntryType (269: '0' bid / '1' offer), MDEntryPx
 * (270, scaled long: mantissa × 10^-decimals, the same representation the
 * order encoder takes, so feed-to-order never touches a double) and
 * MDEntrySize (271). Entry order within the message is preserved: for
 * tiered LP streams, position is the tier. Unknown tags are skipped
 * without materializing anything.</p>
 *
 * <p>Assumes an already-framed message: {@code buffer[offset]} is the first
 * byte of a tag, every field is SOH-terminated (including the last), and
 * checksum validation happened upstream. Framing belongs to the transport
 * layer (the session-lane {@code FixDecoder} materializes messages and is
 * not this flyweight's feeder). Single-threaded: one view per feed thread;
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
    private final int[] entryLevel;
    /** Index version*2 + (0 W, 1 X): delimiter tag of the entry group. */
    private final int[] delimiter;
    /** Same index: membership table by tag number (delimiter included). */
    private final boolean[][] members;
    private FixVersion version;

    private static final byte[] BEGIN_42 = {'F', 'I', 'X', '.', '4', '.', '2'};
    private static final byte[] BEGIN_T11 = {'F', 'I', 'X', 'T', '.', '1', '.', '1'};
    private static final int MD_PRICE_LEVEL = 1023;

    private void layout(int index, FixGroupDef def) {
        if (def == null) {
            throw new IllegalArgumentException("dictionary lacks NoMDEntries(268)");
        }
        int max = def.delimiterTag();
        for (int t : def.memberTags()) {
            max = Math.max(max, t);
        }
        boolean[] m = new boolean[max + 1];
        m[def.delimiterTag()] = true;
        for (int t : def.memberTags()) {
            m[t] = true;
        }
        delimiter[index] = def.delimiterTag();
        members[index] = m;
    }

    private byte[] buf;
    private boolean snapshot;
    private int entryCount;
    private int declaredEntries;
    private int symbolOffset;
    private int symbolLength;
    private long droppedEntries;

    /** @param maxEntries entries retained per message (beyond that: counted, dropped) */
    public FixMarketDataView(int maxEntries) {
        this(maxEntries, null);
    }

    /**
     * A view whose NoMDEntries(268) layout comes from a dictionary.
     *
     * <p>Plain idea: which fields belong to an entry, and which field starts
     * one, differ by message type and version (35=W entries start with
     * MDEntryType(269), 35=X entries with MDUpdateAction(279); FIX 5.0 SP2 adds
     * MDPriceLevel(1023)). The view reads that from {@link FixDictionary} once,
     * here, into flat tables, and picks the table from BeginString(8) on each
     * wrap, so the wrap path still allocates nothing. A field that is not a
     * member of the group ends it, as on a counterparty engine with the same
     * dictionary.
     *
     * @param maxEntries entries retained per message
     * @param dictionary venue dictionary for its own version, or null for the
     *                   standard ones ({@link FixDictionary#forVersion})
     */
    public FixMarketDataView(int maxEntries, FixDictionary dictionary) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        int n = FixVersion.values().length;
        this.delimiter = new int[n * 2];
        this.members = new boolean[n * 2][];
        for (FixVersion v : FixVersion.values()) {
            FixDictionary d = dictionary != null && dictionary.version() == v
                    ? dictionary : FixDictionary.forVersion(v);
            layout(v.ordinal() * 2, d.group("W", NO_MD_ENTRIES));
            layout(v.ordinal() * 2 + 1, d.group("X", NO_MD_ENTRIES));
        }
        this.entryLevel = new int[maxEntries];
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
     * leaves the getters undefined. Zero allocation. The buffer is not
     * copied: the caller keeps ownership and must not overwrite those bytes
     * while reading through this view.
     */
    public boolean wrap(byte[] buffer, int offset, int length) {
        this.buf = buffer;
        this.snapshot = false;
        this.entryCount = 0;
        this.declaredEntries = 0;
        this.symbolOffset = -1;
        this.symbolLength = 0;
        this.version = FixVersion.FIX_4_4;
        boolean marketData = false;
        int entry = -1;                    // index of the entry being filled
        int layout = -1;                   // index into delimiter/members, once 35 is known
        boolean inGroup = false;

        int end = offset + length;
        int p = offset;
        while (p < end) {
            int tag = 0;
            while (p < end && buffer[p] != '=') {
                byte c = buffer[p];
                tag = tag < 0 || c < '0' || c > '9' || tag > 100_000_000 ? -1 : tag * 10 + (c - '0');
                p++;
            }
            p++; // '='
            int valueStart = p;
            while (p < end && buffer[p] != SOH) {
                p++;
            }
            int valueEnd = p;
            p++; // SOH

            if (tag == 8) {
                version = FixExecReportView.same(buffer, valueStart, valueEnd, BEGIN_42)
                        ? FixVersion.FIX_4_2
                        : FixExecReportView.same(buffer, valueStart, valueEnd, BEGIN_T11)
                        ? FixVersion.FIX_5_0_SP2 : FixVersion.FIX_4_4;
                continue;
            }
            if (tag == MSG_TYPE) {
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
                layout = version.ordinal() * 2 + (snapshot ? 0 : 1);
                continue;
            }
            if (tag == NO_MD_ENTRIES) {
                declaredEntries = (int) parseLong(valueStart, valueEnd);
                inGroup = layout >= 0;
                entry = -1;
                continue;
            }
            if (layout >= 0 && tag == delimiter[layout]) {
                // The dictionary's delimiter opens each entry; lenient when a
                // sender omitted 268 (the entry still opens the group).
                inGroup = true;
                entry = openEntry();
                if (entry >= 0) {
                    if (snapshot) {
                        entryAction[entry] = ACTION_NEW;   // W entries are implicitly NEW
                    }
                    member(entry, tag, valueStart, valueEnd);
                }
                continue;
            }
            if (inGroup) {
                boolean[] m = members[layout];
                if (tag >= 0 && tag < m.length && m[tag]) {
                    if (entry >= 0) {
                        member(entry, tag, valueStart, valueEnd);
                    }
                    if (tag == SYMBOL) {
                        symbolOffset = valueStart;
                        symbolLength = valueEnd - valueStart;
                    }
                    continue;
                }
                inGroup = false;           // a non-member field ends the group
                entry = -1;
            }
            if (tag == SYMBOL) {
                symbolOffset = valueStart;
                symbolLength = valueEnd - valueStart;
            }
            // every other top-level tag is skipped without materializing
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
        entryLevel[e] = 0;
        return e;
    }

    /** Stores one member field of an entry; members the view does not expose are ignored. */
    private void member(int entry, int tag, int valueStart, int valueEnd) {
        switch (tag) {
            case MD_UPDATE_ACTION -> entryAction[entry] = buf[valueStart];
            case MD_ENTRY_TYPE -> entryType[entry] = buf[valueStart];
            case MD_ENTRY_PX -> parsePrice(entry, valueStart, valueEnd);
            case MD_ENTRY_SIZE -> entrySize[entry] = parseLong(valueStart, valueEnd);
            case MD_PRICE_LEVEL -> entryLevel[entry] =
                    (int) Math.min(Integer.MAX_VALUE, parseLong(valueStart, valueEnd));
            default -> {
                // member of the group, not exposed
            }
        }
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
     * consumers ({@code fx.FxTierBook}, {@code marketdata.AggregatedBook}). The
     * scaled-long getters remain for pipelines that never touch a double.
     */
    public double price(int entry) {
        // Clamp: a malformed wire value with >18 fractional digits must not
        // turn an accepted message into an index-out-of-bounds at read time.
        int d = Math.min(entryPxDecimals[entry], FixParse.NEG_POW10.length - 1);
        return entryPxMantissa[entry] * FixParse.NEG_POW10[d];
    }

    /**
     * MDPriceLevel(1023) of an entry, 0 when absent. Only FIX 5.0 SP2
     * incremental entries define it in the standard dictionary.
     */
    public int priceLevel(int entry) {
        return entryLevel[entry];
    }

    /** Version named by BeginString(8) of the last wrapped message (4.4 when absent). */
    public FixVersion version() {
        return version;
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

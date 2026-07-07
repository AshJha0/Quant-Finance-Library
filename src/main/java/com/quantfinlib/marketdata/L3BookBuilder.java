package com.quantfinlib.marketdata;

import com.quantfinlib.orderbook.Side;

/**
 * Participant-side full-depth (L3) book builder: reconstructs a venue's book
 * from an ITCH-style event stream (add / execute / cancel / delete / replace)
 * and answers the questions an execution engine actually asks — best
 * bid/ask, depth, and <b>exactly how many shares are queued ahead of my
 * order</b> — with zero allocation on every event.
 *
 * <p>Same engineering as {@code orderbook.HftOrderBook} (dense tick ladder,
 * occupancy bitmaps, pooled intrusive nodes, open-addressing ref map with
 * backward-shift deletion), but driven by the feed instead of by matching:
 * this is the consumer of a venue's L3 feed, not the venue.</p>
 *
 * <h2>Queue position</h2>
 * Call {@link #track} with your own order's reference (learned from the
 * order-entry gateway's ack) once its add has appeared on the feed. The
 * initial shares-ahead is computed by one walk of the level's FIFO; from
 * then on it is maintained in O(1) per event using two facts of price-time
 * priority: executions always consume the queue head (so any execution at
 * your level that isn't you happened ahead of you), and a cancel is ahead of
 * you iff it entered the queue before you (insertion sequence numbers).
 * When your order fills, is deleted, or is replaced, tracking ends
 * automatically.
 *
 * <p><b>Threading</b>: single-writer — one feed-handler thread owns the
 * book. Order references must be positive (0 is the map's empty sentinel),
 * which matches real ITCH feeds.</p>
 */
public final class L3BookBuilder {

    private static final byte BUY = 0;
    private static final byte SELL = 1;
    private static final int NONE = -1;
    private static final int MAX_TRACKED = 64;

    private final int stockLocate;
    private final int minTick;
    private final int ladder;

    private final int[] bidHead;
    private final int[] bidTail;
    private final long[] bidQty;
    private final int[] askHead;
    private final int[] askTail;
    private final long[] askQty;
    private final long[] bidBits;
    private final long[] askBits;
    private int bestBidIdx = NONE;
    private int bestAskIdx = NONE;

    // Pooled intrusive nodes (index = handle).
    private final long[] nodeRef;
    private final long[] nodeQty;
    private final long[] nodeSeq;            // insertion order (priority within level)
    private final int[] nodeTick;            // ladder slot
    private final int[] nodePrev;
    private final int[] nodeNext;
    private final byte[] nodeSide;
    private int freeHead;

    // Open-addressing ref → node map.
    private final long[] mapKeys;
    private final int[] mapVals;
    private final int mapMask;

    // Own-order tracking (parallel arrays, linear scan — MAX_TRACKED is small).
    private final long[] trackedRef = new long[MAX_TRACKED];
    private final long[] trackedSeq = new long[MAX_TRACKED];
    private final long[] trackedAhead = new long[MAX_TRACKED];
    private final int[] trackedTick = new int[MAX_TRACKED];
    private final byte[] trackedSide = new byte[MAX_TRACKED];
    private int trackedCount;

    private final ItchCodec.View view = new ItchCodec.View();

    private long nextSeq = 1;
    private long addCount;
    private long executeCount;
    private long cancelCount;
    private long deleteCount;
    private long replaceCount;
    private long tradeCount;
    private long unknownRefCount;
    private long outOfBandCount;
    private long duplicateRefCount;
    private int restingOrders;
    private int lastTradeTick = Integer.MIN_VALUE;

    /**
     * @param stockLocate  the feed's locate code for the symbol this book tracks;
     *                     messages for other locates are ignored by {@link #onMessage}
     * @param minPriceTick lowest representable price in 0.0001 ticks (inclusive)
     * @param maxPriceTick highest representable price in 0.0001 ticks (inclusive)
     * @param maxOrders    resting-order capacity (pool size, fixed forever)
     */
    public L3BookBuilder(int stockLocate, int minPriceTick, int maxPriceTick, int maxOrders) {
        if (maxPriceTick < minPriceTick || maxOrders <= 0) {
            throw new IllegalArgumentException("need maxPriceTick >= minPriceTick, maxOrders > 0");
        }
        this.stockLocate = stockLocate;
        this.minTick = minPriceTick;
        this.ladder = maxPriceTick - minPriceTick + 1;
        this.bidHead = filled(ladder);
        this.bidTail = filled(ladder);
        this.askHead = filled(ladder);
        this.askTail = filled(ladder);
        this.bidQty = new long[ladder];
        this.askQty = new long[ladder];
        this.bidBits = new long[(ladder + 63) >>> 6];
        this.askBits = new long[(ladder + 63) >>> 6];

        this.nodeRef = new long[maxOrders];
        this.nodeQty = new long[maxOrders];
        this.nodeSeq = new long[maxOrders];
        this.nodeTick = new int[maxOrders];
        this.nodePrev = new int[maxOrders];
        this.nodeNext = new int[maxOrders];
        this.nodeSide = new byte[maxOrders];
        for (int i = 0; i < maxOrders - 1; i++) {
            nodeNext[i] = i + 1;
        }
        nodeNext[maxOrders - 1] = NONE;
        this.freeHead = 0;

        int cap = Integer.highestOneBit(Math.max(16, maxOrders * 2 - 1)) << 1;
        this.mapKeys = new long[cap];
        this.mapVals = new int[cap];
        this.mapMask = cap - 1;
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, NONE);
        return a;
    }

    // ------------------------------------------------------------------
    // Feed entry points (the hot path)
    // ------------------------------------------------------------------

    /**
     * Applies one wire message starting at {@code offset}. Returns the wire
     * length consumed, or 0 when the message is for another stock locate or
     * outside the supported subset (callers skip it by its own length).
     */
    public int onMessage(byte[] buf, int offset) {
        ItchCodec.View v = view.wrap(buf, offset);
        int len = ItchCodec.length(v.type());
        if (len < 0 || v.stockLocate() != stockLocate) {
            return 0;
        }
        switch (v.type()) {
            case ItchCodec.ADD, ItchCodec.ADD_MPID ->
                    onAdd(v.orderRef(), v.side() == ItchCodec.BUY ? Side.BUY : Side.SELL,
                            v.shares(), v.priceTick());
            case ItchCodec.EXECUTED -> onExecute(v.orderRef(), v.deltaShares());
            case ItchCodec.CANCEL -> onCancel(v.orderRef(), v.deltaShares());
            case ItchCodec.DELETE -> onDelete(v.orderRef());
            case ItchCodec.REPLACE -> onReplace(v.origRef(), v.newRef(), v.shares(), v.priceTick());
            case ItchCodec.TRADE -> onTrade(v.priceTick());
            default -> {
                return 0;
            }
        }
        return len;
    }

    /**
     * Add order: appends to its level's FIFO. False when the order was
     * dropped (off-band price, exhausted pool, or a duplicate ref — a feed
     * anomaly that would otherwise corrupt the ref map).
     */
    public boolean onAdd(long ref, Side side, long shares, int priceTick) {
        if (!insert(ref, side, shares, priceTick)) {
            return false;
        }
        addCount++;
        return true;
    }

    /**
     * Places an order without event counting — shared by add and replace.
     * Failures are counted here ({@code outOfBandCount} / {@code
     * duplicateRefCount}) because they mean the same thing on both paths.
     */
    private boolean insert(long ref, Side side, long shares, int priceTick) {
        int idx = priceTick - minTick;
        if (idx < 0 || idx >= ladder || shares <= 0 || ref <= 0 || freeHead == NONE) {
            outOfBandCount++;
            return false;
        }
        if (mapFind(ref) != NONE) {
            // Re-delivered add (gap-recovery replay / simulator bug): a blind
            // insert would leave a phantom second node the venue's future
            // delete can never remove.
            duplicateRefCount++;
            return false;
        }
        byte s = side == Side.BUY ? BUY : SELL;
        int node = freeHead;
        freeHead = nodeNext[node];
        nodeRef[node] = ref;
        nodeQty[node] = shares;
        nodeSeq[node] = nextSeq++;
        nodeTick[node] = idx;
        nodeSide[node] = s;
        nodeNext[node] = NONE;

        int[] head = s == BUY ? bidHead : askHead;
        int[] tail = s == BUY ? bidTail : askTail;
        long[] qty = s == BUY ? bidQty : askQty;
        long[] bits = s == BUY ? bidBits : askBits;

        int t = tail[idx];
        nodePrev[node] = t;
        if (t == NONE) {
            head[idx] = node;
        } else {
            nodeNext[t] = node;
        }
        tail[idx] = node;
        qty[idx] += shares;
        bits[idx >>> 6] |= 1L << (idx & 63);
        if (s == BUY) {
            if (bestBidIdx == NONE || idx > bestBidIdx) {
                bestBidIdx = idx;
            }
        } else {
            if (bestAskIdx == NONE || idx < bestAskIdx) {
                bestAskIdx = idx;
            }
        }
        mapPut(ref, node);
        restingOrders++;
        return true;
    }

    /**
     * Execution against a resting order (always the queue head under
     * price-time priority — which is what makes O(1) queue tracking sound).
     */
    public void onExecute(long ref, long shares) {
        if (reduce(ref, shares, false)) {
            executeCount++;
        } else {
            unknownRefCount++;
        }
    }

    /** Partial cancel: reduces a resting order in place (keeps its priority). */
    public void onCancel(long ref, long shares) {
        if (reduce(ref, shares, true)) {
            cancelCount++;
        } else {
            unknownRefCount++;
        }
    }

    /** Full removal of a resting order. */
    public void onDelete(long ref) {
        int slot = mapFind(ref);
        if (slot == NONE) {
            unknownRefCount++;
            return;
        }
        deleteCount++;
        removeWhole(mapVals[slot], slot);
    }

    /**
     * Cancel/replace: the original order is removed and the new reference
     * joins the back of the (possibly different) level's queue — priority is
     * lost, exactly as on a real venue. A replace re-pricing to an off-band
     * level drops the order entirely, consistent with off-band adds: prices
     * outside the configured band are invisible to this book by design.
     */
    public void onReplace(long origRef, long newRef, long shares, int priceTick) {
        int slot = mapFind(origRef);
        if (slot == NONE) {
            unknownRefCount++;
            return;
        }
        replaceCount++;
        int node = mapVals[slot];
        Side side = nodeSide[node] == BUY ? Side.BUY : Side.SELL;
        removeWhole(node, slot);
        insert(newRef, side, shares, priceTick);
    }

    /**
     * Shared reduction for executions and cancels: clamps to the resting
     * quantity, credits tracked orders (executions consume the head so they
     * are ahead of everyone else at the level; cancels only of orders they
     * precede — {@code bySeq}), and removes the node when it empties. The
     * level total can only reach zero when the node's own quantity did, so
     * {@code removeNode} covers all bitmap maintenance. False = unknown ref.
     */
    private boolean reduce(long ref, long shares, boolean bySeq) {
        int slot = mapFind(ref);
        if (slot == NONE) {
            return false;
        }
        int node = mapVals[slot];
        long cut = Math.min(shares, nodeQty[node]);
        creditAhead(node, cut, bySeq);
        nodeQty[node] -= cut;
        levelQtyOf(node)[nodeTick[node]] -= cut;
        if (nodeQty[node] == 0) {
            removeNode(node, slot);
        }
        return true;
    }

    /** Full removal shared by delete and replace: credit, level total, unlink. */
    private void removeWhole(int node, int slot) {
        creditAhead(node, nodeQty[node], true);
        levelQtyOf(node)[nodeTick[node]] -= nodeQty[node];
        removeNode(node, slot);
    }

    /** Off-book/non-displayed trade print: records it, book unchanged. */
    public void onTrade(int priceTick) {
        tradeCount++;
        lastTradeTick = priceTick;
    }

    // ------------------------------------------------------------------
    // Own-order queue tracking
    // ------------------------------------------------------------------

    /**
     * Starts queue tracking for a resting order (yours, learned from your
     * gateway ack). The initial shares-ahead is one FIFO walk; maintenance
     * is O(1) per event afterwards. Returns false when the ref is unknown
     * or the tracking table (64 orders) is full.
     */
    public boolean track(long ref) {
        for (int i = 0; i < trackedCount; i++) {
            if (trackedRef[i] == ref) {
                return true;                 // idempotent: a retried ack must
            }                                // not create a leaking duplicate row
        }
        int slot = mapFind(ref);
        if (slot == NONE || trackedCount == MAX_TRACKED) {
            return false;
        }
        int node = mapVals[slot];
        long ahead = 0;
        int idx = nodeTick[node];
        int cur = (nodeSide[node] == BUY ? bidHead : askHead)[idx];
        while (cur != node && cur != NONE) {
            ahead += nodeQty[cur];
            cur = nodeNext[cur];
        }
        trackedRef[trackedCount] = ref;
        trackedSeq[trackedCount] = nodeSeq[node];
        trackedAhead[trackedCount] = ahead;
        trackedTick[trackedCount] = idx;
        trackedSide[trackedCount] = nodeSide[node];
        trackedCount++;
        return true;
    }

    /** Stops tracking a ref (no-op when not tracked). */
    public void untrack(long ref) {
        for (int i = 0; i < trackedCount; i++) {
            if (trackedRef[i] == ref) {
                trackedCount--;
                trackedRef[i] = trackedRef[trackedCount];
                trackedSeq[i] = trackedSeq[trackedCount];
                trackedAhead[i] = trackedAhead[trackedCount];
                trackedTick[i] = trackedTick[trackedCount];
                trackedSide[i] = trackedSide[trackedCount];
                return;
            }
        }
    }

    /**
     * Shares queued ahead of a tracked order right now; -1 when the ref is
     * not tracked (never was, or it filled / was deleted / was replaced).
     * This is the <em>exact</em> position from the L3 feed; when only L2
     * (aggregated level sizes) is available, use the probabilistic
     * {@code microstructure.QueuePositionEstimator} instead.
     */
    public long sharesAhead(long ref) {
        for (int i = 0; i < trackedCount; i++) {
            if (trackedRef[i] == ref) {
                return trackedAhead[i];
            }
        }
        return -1;
    }

    /**
     * Reduces shares-ahead for every tracked order that the removed/reduced
     * quantity was actually ahead of. Executions consume the head, so they
     * are ahead of every other order at the level; cancels are ahead only
     * of orders they precede in insertion order ({@code bySeq}).
     */
    private void creditAhead(int node, long qty, boolean bySeq) {
        int idx = nodeTick[node];
        byte side = nodeSide[node];
        long seq = nodeSeq[node];
        long ref = nodeRef[node];
        for (int i = 0; i < trackedCount; i++) {
            if (trackedTick[i] == idx && trackedSide[i] == side && trackedRef[i] != ref
                    && (!bySeq || seq < trackedSeq[i])) {
                trackedAhead[i] = Math.max(0, trackedAhead[i] - qty);
            }
        }
    }

    // ------------------------------------------------------------------
    // Node/level plumbing
    // ------------------------------------------------------------------

    private long[] levelQtyOf(int node) {
        return nodeSide[node] == BUY ? bidQty : askQty;
    }

    /** Unlinks a zero/removed node, clears bitmap, recycles pool + map slots. */
    private void removeNode(int node, int mapSlot) {
        int idx = nodeTick[node];
        byte side = nodeSide[node];
        int[] head = side == BUY ? bidHead : askHead;
        int[] tail = side == BUY ? bidTail : askTail;

        int prev = nodePrev[node];
        int next = nodeNext[node];
        if (prev == NONE) {
            head[idx] = next;
        } else {
            nodeNext[prev] = next;
        }
        if (next == NONE) {
            tail[idx] = prev;
        } else {
            nodePrev[next] = prev;
        }
        untrack(nodeRef[node]);              // ends tracking if this was ours
        mapRemoveAt(mapSlot);
        nodeNext[node] = freeHead;
        freeHead = node;
        restingOrders--;

        long[] qty = side == BUY ? bidQty : askQty;
        if (qty[idx] == 0) {
            long[] bits = side == BUY ? bidBits : askBits;
            bits[idx >>> 6] &= ~(1L << (idx & 63));
            if (side == BUY && idx == bestBidIdx) {
                bestBidIdx = nextSetAtOrBelow(bidBits, idx - 1);
            } else if (side == SELL && idx == bestAskIdx) {
                bestAskIdx = nextSetAtOrAbove(askBits, idx + 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Best bid in absolute 0.0001 ticks; {@code Integer.MIN_VALUE} when none. */
    public int bestBidTick() {
        return bestBidIdx == NONE ? Integer.MIN_VALUE : bestBidIdx + minTick;
    }

    /** Best ask in absolute 0.0001 ticks; {@code Integer.MAX_VALUE} when none. */
    public int bestAskTick() {
        return bestAskIdx == NONE ? Integer.MAX_VALUE : bestAskIdx + minTick;
    }

    public long bestBidSize() {
        return bestBidIdx == NONE ? 0 : bidQty[bestBidIdx];
    }

    public long bestAskSize() {
        return bestAskIdx == NONE ? 0 : askQty[bestAskIdx];
    }

    /** Resting quantity at an absolute tick (0 when off-band or empty). */
    public long qtyAtTick(Side side, int priceTick) {
        int idx = priceTick - minTick;
        if (idx < 0 || idx >= ladder) {
            return 0;
        }
        return side == Side.BUY ? bidQty[idx] : askQty[idx];
    }

    /** Open shares of any resting order by ref; 0 when gone/unknown. */
    public long openQuantity(long ref) {
        int slot = mapFind(ref);
        return slot == NONE ? 0 : nodeQty[mapVals[slot]];
    }

    /** Depth snapshot into caller arrays, best-first; returns levels written. */
    public int snapshot(Side side, int[] outTicks, long[] outQtys) {
        int max = Math.min(outTicks.length, outQtys.length);
        int n = 0;
        if (side == Side.BUY) {
            for (int idx = bestBidIdx; idx != NONE && n < max;
                 idx = nextSetAtOrBelow(bidBits, idx - 1)) {
                outTicks[n] = idx + minTick;
                outQtys[n] = bidQty[idx];
                n++;
            }
        } else {
            for (int idx = bestAskIdx; idx != NONE && n < max;
                 idx = nextSetAtOrAbove(askBits, idx + 1)) {
                outTicks[n] = idx + minTick;
                outQtys[n] = askQty[idx];
                n++;
            }
        }
        return n;
    }

    public int lastTradeTick() {
        return lastTradeTick;
    }

    public int restingOrders() {
        return restingOrders;
    }

    public long addCount() {
        return addCount;
    }

    public long executeCount() {
        return executeCount;
    }

    public long cancelCount() {
        return cancelCount;
    }

    public long deleteCount() {
        return deleteCount;
    }

    public long replaceCount() {
        return replaceCount;
    }

    public long tradeCount() {
        return tradeCount;
    }

    /** Events referencing unknown orders (feed gap symptom — resubscribe/snapshot). */
    public long unknownRefCount() {
        return unknownRefCount;
    }

    /**
     * Orders dropped for off-band prices or an exhausted pool — adds AND
     * replace re-adds (widen the band/pool; off-band liquidity is invisible
     * to this book by design, and its later events count as unknown refs).
     */
    public long outOfBandCount() {
        return outOfBandCount;
    }

    /** Adds re-delivering a live ref, rejected to protect the book (replay symptom). */
    public long duplicateRefCount() {
        return duplicateRefCount;
    }

    // ------------------------------------------------------------------
    // Bitmap scans + ref map: shared with HftOrderBook via BookPrimitives
    // (the backward-shift deletion rule exists exactly once in the library)
    // ------------------------------------------------------------------

    private static int nextSetAtOrAbove(long[] bits, int from) {
        return com.quantfinlib.orderbook.BookPrimitives.nextSetAtOrAbove(bits, from);
    }

    private static int nextSetAtOrBelow(long[] bits, int from) {
        return com.quantfinlib.orderbook.BookPrimitives.nextSetAtOrBelow(bits, from);
    }

    private void mapPut(long key, int value) {
        com.quantfinlib.orderbook.BookPrimitives.mapPut(mapKeys, mapVals, mapMask, key, value);
    }

    private int mapFind(long key) {
        return com.quantfinlib.orderbook.BookPrimitives.mapFind(mapKeys, mapMask, key);
    }

    private void mapRemoveAt(int slot) {
        com.quantfinlib.orderbook.BookPrimitives.mapRemoveAt(mapKeys, mapVals, mapMask, slot);
    }
}

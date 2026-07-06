package com.quantfinlib.orderbook;

/**
 * Venue-grade price-time-priority matching engine: the zero-allocation
 * sibling of {@link OrderBook}, built the way exchange cores are actually
 * built. Where {@code OrderBook} favors clarity (TreeMap, per-order objects)
 * for research, this class favors the disciplines of a matching venue:
 *
 * <ul>
 *   <li><b>Dense integer-tick price ladder</b> — one array slot per tick per
 *       side over a configured band; no tree, no comparator, no boxing.
 *       Prices are {@code int} ticks (convert once at the edge, e.g. via
 *       {@code microstructure.TickSizeSchedule});</li>
 *   <li><b>Occupancy bitmaps</b> — one bit per price level per side, so
 *       advancing the best price over emptied levels is a word scan
 *       ({@code Long.numberOfTrailingZeros}), not a tree walk;</li>
 *   <li><b>Pooled intrusive order nodes</b> — every order lives in
 *       preallocated parallel primitive arrays, linked into its level's FIFO
 *       queue by index; placing, filling and cancelling recycle pool slots
 *       and allocate <em>nothing</em> (proven by the allocation-counter
 *       test, like every hot-path claim in this library);</li>
 *   <li><b>Primitive open-addressing id map</b> — order-id → node lookup by
 *       linear probing over {@code long[]}/{@code int[]}, with backward-shift
 *       deletion so cancel churn never degrades into tombstone soup;</li>
 *   <li><b>No iterators, no listeners list</b> — a single primitive
 *       {@link TradeSink} callback.</li>
 * </ul>
 *
 * <p>Correctness is enforced two ways: the price-time semantics are asserted
 * directly, and a model-based equivalence test drives this book and the
 * reference {@link OrderBook} with identical randomized operation streams
 * and demands identical books — the readable implementation is the
 * executable specification of this one.</p>
 *
 * <p><b>Threading</b>: single-threaded by design, like real matching cores —
 * one engine thread owns the book; fan orders in via
 * {@code trading.OrderRingBuffer} and trades out via the sink. Rejections
 * are return codes, never exceptions, because a venue must not unwind its
 * matching loop over a bad order.</p>
 */
public final class HftOrderBook {

    /** Accepted-order ids are positive; these are the rejection codes. */
    public static final long REJECT_POOL_FULL = -1;
    public static final long REJECT_OUT_OF_BAND = -2;
    public static final long REJECT_INVALID = -3;

    /** Primitive fill callback: maker is the resting order, taker the incoming one. */
    @FunctionalInterface
    public interface TradeSink {
        void onTrade(long makerOrderId, long takerOrderId, int priceTick, long quantity,
                     long timestampNanos);
    }

    private static final byte BUY = 0;
    private static final byte SELL = 1;
    private static final int NONE = -1;

    private final int minTick;
    private final int ladder;                // number of price levels per side

    // Per-side level queues: head/tail node index per tick slot, total resting qty.
    private final int[] bidHead;
    private final int[] bidTail;
    private final long[] bidQty;
    private final int[] askHead;
    private final int[] askTail;
    private final long[] askQty;
    // Occupancy bitmaps: bit i set = level i has resting orders on that side.
    private final long[] bidBits;
    private final long[] askBits;
    private int bestBidIdx = NONE;           // highest occupied bid slot
    private int bestAskIdx = NONE;           // lowest occupied ask slot

    // Intrusive node pool (parallel primitive arrays; index = node handle).
    private final long[] nodeId;
    private final long[] nodeQty;
    private final int[] nodeTick;            // ladder slot, not absolute tick
    private final int[] nodePrev;
    private final int[] nodeNext;
    private final byte[] nodeSide;
    private int freeHead;                    // free-list threaded through nodeNext

    // Open-addressing id → node map (linear probing, backward-shift delete).
    // Key 0 = empty slot, which is safe because ids start at 1.
    private final long[] mapKeys;
    private final int[] mapVals;
    private final int mapMask;

    private TradeSink sink;
    private long nextId = 1;
    private long orderCount;
    private long cancelCount;
    private long tradeCount;
    private int restingOrders;

    /**
     * @param minPriceTick lowest representable price, in ticks (inclusive)
     * @param maxPriceTick highest representable price, in ticks (inclusive)
     * @param maxOrders    resting-order capacity (pool size, fixed forever)
     */
    public HftOrderBook(int minPriceTick, int maxPriceTick, int maxOrders) {
        if (maxPriceTick < minPriceTick || maxOrders <= 0) {
            throw new IllegalArgumentException("need maxPriceTick >= minPriceTick, maxOrders > 0");
        }
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

        this.nodeId = new long[maxOrders];
        this.nodeQty = new long[maxOrders];
        this.nodeTick = new int[maxOrders];
        this.nodePrev = new int[maxOrders];
        this.nodeNext = new int[maxOrders];
        this.nodeSide = new byte[maxOrders];
        // Thread the free list through nodeNext.
        for (int i = 0; i < maxOrders - 1; i++) {
            nodeNext[i] = i + 1;
        }
        nodeNext[maxOrders - 1] = NONE;
        this.freeHead = 0;

        // 2× capacity, power of two: max load factor 0.5 keeps probes short.
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

    /** Installs the (single) trade callback; call before trading. */
    public void tradeSink(TradeSink sink) {
        this.sink = sink;
    }

    // ------------------------------------------------------------------
    // Order entry (the hot path)
    // ------------------------------------------------------------------

    /**
     * Limit order: matches against the opposite side while it crosses, rests
     * any remainder at {@code priceTick}. Returns the positive order id on
     * acceptance (also the taker id in emitted trades — even a fully filled
     * order was accepted), or a negative rejection code. Zero allocation.
     */
    public long submitLimit(Side side, int priceTick, long quantity, long timestampNanos) {
        if (quantity <= 0) {
            return REJECT_INVALID;
        }
        int idx = priceTick - minTick;
        if (idx < 0 || idx >= ladder) {
            return REJECT_OUT_OF_BAND;
        }
        long id = nextId++;
        orderCount++;
        long remaining = side == Side.BUY
                ? matchBuy(id, idx, quantity, timestampNanos)
                : matchSell(id, idx, quantity, timestampNanos);
        if (remaining > 0) {
            if (freeHead == NONE) {
                // Pool exhausted: the matched portion stands (trades were
                // real); only the resting remainder is rejected — exactly
                // how a venue sheds load without unwinding executions.
                return REJECT_POOL_FULL;
            }
            rest(id, side == Side.BUY ? BUY : SELL, idx, remaining);
        }
        return id;
    }

    /**
     * Market order: matches against the whole opposite book, never rests.
     * Returns the filled quantity (0 when the opposite side is empty).
     */
    public long submitMarket(Side side, long quantity, long timestampNanos) {
        if (quantity <= 0) {
            return 0;
        }
        long id = nextId++;
        orderCount++;
        long remaining = side == Side.BUY
                ? matchBuy(id, ladder - 1, quantity, timestampNanos)
                : matchSell(id, 0, quantity, timestampNanos);
        return quantity - remaining;
    }

    /** Cancels a resting order. False when unknown/already gone — never throws. */
    public boolean cancel(long orderId) {
        int slot = mapFind(orderId);
        if (slot == NONE) {
            return false;
        }
        int node = mapVals[slot];
        unlink(node);
        mapRemoveAt(slot);
        freeNode(node);
        cancelCount++;
        restingOrders--;
        return true;
    }

    // ------------------------------------------------------------------
    // Matching (private hot path)
    // ------------------------------------------------------------------

    /** Buy taker: lift asks from the lowest occupied level up to limitIdx. */
    private long matchBuy(long takerId, int limitIdx, long quantity, long ts) {
        long remaining = quantity;
        while (remaining > 0 && bestAskIdx != NONE && bestAskIdx <= limitIdx) {
            remaining = fillLevel(askHead, askTail, askQty, askBits, bestAskIdx,
                    takerId, remaining, ts);
            if (askQty[bestAskIdx] == 0) {
                bestAskIdx = nextSetAtOrAbove(askBits, bestAskIdx + 1);
            }
        }
        return remaining;
    }

    /** Sell taker: hit bids from the highest occupied level down to limitIdx. */
    private long matchSell(long takerId, int limitIdx, long quantity, long ts) {
        long remaining = quantity;
        while (remaining > 0 && bestBidIdx != NONE && bestBidIdx >= limitIdx) {
            remaining = fillLevel(bidHead, bidTail, bidQty, bidBits, bestBidIdx,
                    takerId, remaining, ts);
            if (bidQty[bestBidIdx] == 0) {
                bestBidIdx = nextSetAtOrBelow(bidBits, bestBidIdx - 1);
            }
        }
        return remaining;
    }

    /** FIFO-fills one level; returns the taker's remaining quantity. */
    private long fillLevel(int[] head, int[] tail, long[] levelQty, long[] bits, int idx,
                           long takerId, long remaining, long ts) {
        int node = head[idx];
        while (remaining > 0 && node != NONE) {
            long fill = Math.min(remaining, nodeQty[node]);
            remaining -= fill;
            nodeQty[node] -= fill;
            levelQty[idx] -= fill;
            tradeCount++;
            if (sink != null) {
                sink.onTrade(nodeId[node], takerId, idx + minTick, fill, ts);
            }
            if (nodeQty[node] == 0) {
                // Maker fully filled: pop the head, recycle everything.
                int next = nodeNext[node];
                head[idx] = next;
                if (next == NONE) {
                    tail[idx] = NONE;
                } else {
                    nodePrev[next] = NONE;
                }
                mapRemoveAt(mapFind(nodeId[node]));
                freeNode(node);
                restingOrders--;
                node = next;
            }
        }
        if (levelQty[idx] == 0) {
            bits[idx >>> 6] &= ~(1L << (idx & 63));
        }
        return remaining;
    }

    /** Appends a remainder to its level's FIFO tail and indexes it. */
    private void rest(long id, byte side, int idx, long quantity) {
        int node = freeHead;
        freeHead = nodeNext[node];
        nodeId[node] = id;
        nodeQty[node] = quantity;
        nodeTick[node] = idx;
        nodeSide[node] = side;
        nodeNext[node] = NONE;

        int[] head = side == BUY ? bidHead : askHead;
        int[] tail = side == BUY ? bidTail : askTail;
        long[] qty = side == BUY ? bidQty : askQty;
        long[] bits = side == BUY ? bidBits : askBits;

        int t = tail[idx];
        nodePrev[node] = t;
        if (t == NONE) {
            head[idx] = node;
        } else {
            nodeNext[t] = node;
        }
        tail[idx] = node;
        qty[idx] += quantity;
        bits[idx >>> 6] |= 1L << (idx & 63);

        if (side == BUY) {
            if (bestBidIdx == NONE || idx > bestBidIdx) {
                bestBidIdx = idx;
            }
        } else {
            if (bestAskIdx == NONE || idx < bestAskIdx) {
                bestAskIdx = idx;
            }
        }
        mapPut(id, node);
        restingOrders++;
    }

    /** Removes a (known-resting) node from its level, maintaining best/bitmap. */
    private void unlink(int node) {
        int idx = nodeTick[node];
        byte side = nodeSide[node];
        int[] head = side == BUY ? bidHead : askHead;
        int[] tail = side == BUY ? bidTail : askTail;
        long[] qty = side == BUY ? bidQty : askQty;
        long[] bits = side == BUY ? bidBits : askBits;

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
        qty[idx] -= nodeQty[node];
        if (qty[idx] == 0) {
            bits[idx >>> 6] &= ~(1L << (idx & 63));
            if (side == BUY && idx == bestBidIdx) {
                bestBidIdx = nextSetAtOrBelow(bidBits, idx - 1);
            } else if (side == SELL && idx == bestAskIdx) {
                bestAskIdx = nextSetAtOrAbove(askBits, idx + 1);
            }
        }
    }

    private void freeNode(int node) {
        nodeNext[node] = freeHead;
        freeHead = node;
    }

    // ------------------------------------------------------------------
    // Bitmap scans (best-price advancement)
    // ------------------------------------------------------------------

    /** Lowest set bit at or above {@code from}, or NONE. */
    private int nextSetAtOrAbove(long[] bits, int from) {
        if (from >= ladder) {
            return NONE;
        }
        int word = from >>> 6;
        long w = bits[word] & (-1L << (from & 63)); // mask below `from`
        while (true) {
            if (w != 0) {
                return (word << 6) + Long.numberOfTrailingZeros(w);
            }
            if (++word >= bits.length) {
                return NONE;
            }
            w = bits[word];
        }
    }

    /** Highest set bit at or below {@code from}, or NONE. */
    private int nextSetAtOrBelow(long[] bits, int from) {
        if (from < 0) {
            return NONE;
        }
        int word = from >>> 6;
        long w = bits[word] & (-1L >>> (63 - (from & 63))); // mask above `from`
        while (true) {
            if (w != 0) {
                return (word << 6) + 63 - Long.numberOfLeadingZeros(w);
            }
            if (--word < 0) {
                return NONE;
            }
            w = bits[word];
        }
    }

    // ------------------------------------------------------------------
    // Primitive open-addressing id map (linear probe, backward-shift delete)
    // ------------------------------------------------------------------

    private void mapPut(long key, int value) {
        int slot = (int) mix(key) & mapMask;
        while (mapKeys[slot] != 0) {
            slot = (slot + 1) & mapMask;
        }
        mapKeys[slot] = key;
        mapVals[slot] = value;
    }

    /** Slot of {@code key}, or NONE. */
    private int mapFind(long key) {
        int slot = (int) mix(key) & mapMask;
        while (mapKeys[slot] != 0) {
            if (mapKeys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mapMask;
        }
        return NONE;
    }

    /**
     * Backward-shift deletion: re-place every entry of the probe run that
     * follows the hole, so lookups never need tombstones and cancel churn
     * cannot degrade probe lengths over a long session.
     */
    private void mapRemoveAt(int slot) {
        int hole = slot;
        int probe = (hole + 1) & mapMask;
        while (mapKeys[probe] != 0) {
            int home = (int) mix(mapKeys[probe]) & mapMask;
            // Move back iff the entry's home position is "at or before" the
            // hole in circular probe order (standard backward-shift rule).
            boolean move = hole <= probe
                    ? (home <= hole || home > probe)
                    : (home <= hole && home > probe);
            if (move) {
                mapKeys[hole] = mapKeys[probe];
                mapVals[hole] = mapVals[probe];
                hole = probe;
            }
            probe = (probe + 1) & mapMask;
        }
        mapKeys[hole] = 0;
    }

    /** Stafford variant 13 finalizer: cheap, well-mixed long hash. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------
    // Queries (all primitive, zero allocation)
    // ------------------------------------------------------------------

    /** Best bid in absolute ticks; {@code Integer.MIN_VALUE} when no bids. */
    public int bestBidTick() {
        return bestBidIdx == NONE ? Integer.MIN_VALUE : bestBidIdx + minTick;
    }

    /** Best ask in absolute ticks; {@code Integer.MAX_VALUE} when no asks. */
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

    /** Open (unfilled, uncancelled) quantity of an order; 0 when gone. */
    public long openQuantity(long orderId) {
        int slot = mapFind(orderId);
        return slot == NONE ? 0 : nodeQty[mapVals[slot]];
    }

    /**
     * Depth snapshot into caller-provided arrays (absolute ticks + level
     * quantities, best-first): zero allocation. Returns levels written
     * (bounded by the shorter array).
     */
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

    public long orderCount() {
        return orderCount;
    }

    public long cancelCount() {
        return cancelCount;
    }

    public long tradeCount() {
        return tradeCount;
    }

    /** Orders currently resting in the book (pool slots in use). */
    public int restingOrders() {
        return restingOrders;
    }
}

package com.quantfinlib.orderbook;

import com.quantfinlib.core.Side;
/**
 * Call auction: the opening or closing auction of an exchange. During the
 * call phase orders accumulate without trading, the book may cross, and the
 * venue publishes an indicative price, volume and imbalance; at the end of
 * the call the book uncrosses at one price and the remaining orders can be
 * moved into a continuous {@link HftOrderBook}.
 *
 * <h2>Uncrossing price</h2>
 * <p>The plain idea: pick the single price at which the most shares change
 * hands. For a candidate price {@code p} (in ticks), demand
 * {@code D(p)} is the quantity of market buys plus limit buys priced at or
 * above {@code p}, supply {@code S(p)} the market sells plus limit sells at
 * or below {@code p}. The executable volume is {@code V(p) = min(D(p), S(p))}
 * and the imbalance {@code I(p) = D(p) - S(p)} (positive: buy surplus). The
 * uncrossing price is chosen, among every tick between the lowest and the
 * highest limit price in the book (and the reference price):</p>
 * <ol>
 *   <li>maximum executable volume {@code V(p)};</li>
 *   <li>then minimum absolute imbalance {@code |I(p)|};</li>
 *   <li>then the price closest to the reference price (typically the
 *       previous close, or the last trade for a closing auction).</li>
 * </ol>
 * <p>These are the first, second and last steps of the auction price
 * determination in the Deutsche Boerse Xetra market model and the Euronext
 * uncrossing rules; both insert a market-pressure step (the side of the
 * surplus) before the reference price, which this book leaves out. Since {@code D} is
 * non-increasing and {@code S} non-decreasing, {@code V} is unimodal and
 * {@code I} monotone, so the prices tied after steps 1 and 2 form one
 * contiguous range of ticks and step 3 always yields a single price: the
 * reference itself when it lies inside the range, otherwise the nearer end.
 * Without a reference price ({@link #NO_PRICE}) the lowest tied price is
 * taken.</p>
 *
 * <p>Worked example: buys 100 at 10 and 200 at 9, sells 150 at 8 and 100 at
 * 9. At 8: {@code D = 300, S = 150, V = 150}; at 9: {@code D = 300, S = 250,
 * V = 250}; at 10: {@code D = 100, S = 250, V = 100}. The price is 9, 250
 * trade and 50 remain bid at 9 (imbalance +50).</p>
 *
 * <h2>Allocation</h2>
 * <p>At the uncrossing price, executable orders fill in price-time priority:
 * market orders first, then limit orders from the most aggressive price,
 * oldest first within a price. Buys and sells are paired in that order, so a
 * trade report names one buy and one sell order. Every buy priced above and
 * every sell priced below the uncrossing price fills completely, so the
 * remaining book does not cross. Market orders that do not execute are
 * cancelled.</p>
 *
 * <h2>Allocation behaviour</h2>
 * <p>Order entry, cancel and indicative-price computation during the call
 * allocate nothing (pooled nodes, primitive id map, preallocated scratch);
 * the allocation test pins this. Single-threaded, like {@link HftOrderBook}.</p>
 */
public final class AuctionBook {

    /** Price returned when the book does not cross. */
    public static final int NO_PRICE = Integer.MIN_VALUE;
    public static final long REJECT_POOL_FULL = -1;
    public static final long REJECT_OUT_OF_BAND = -2;
    public static final long REJECT_INVALID = -3;
    /** Order entry outside the call phase. */
    public static final long REJECT_NOT_IN_CALL = -5;

    /** Auction trade: one buy order, one sell order, at the uncrossing price. */
    @FunctionalInterface
    public interface AuctionTradeSink {
        void onTrade(long tradeSequence, long buyOrderId, long sellOrderId, int priceTick,
                     long quantity, long timestampNanos);
    }

    /** Indicative uncrossing price, volume and imbalance, published during the call. */
    @FunctionalInterface
    public interface IndicativeListener {
        void onIndicative(int priceTick, long volume, long imbalance, long timestampNanos);
    }

    /** Old auction order id and the id it received in the continuous book (negative: rejected). */
    @FunctionalInterface
    public interface TransferSink {
        void onTransferred(long auctionOrderId, long continuousOrderId);
    }

    private static final byte BUY = 0;
    private static final byte SELL = 1;
    private static final int NONE = -1;

    private final int minTick;
    private final int ladder;
    private final int market;            // slot index of the market-order queue
    private final int[] buyHead;
    private final int[] buyTail;
    private final long[] buyQty;
    private final int[] sellHead;
    private final int[] sellTail;
    private final long[] sellQty;
    private final long[] demand;         // scratch: D at each slot

    private final long[] nodeId;
    private final long[] nodeQty;
    private final int[] nodeSlot;
    private final byte[] nodeSide;
    private final int[] nodePrev;
    private final int[] nodeNext;
    private int freeHead;

    private final long[] mapKeys;
    private final int[] mapVals;
    private final int mapMask;

    private boolean inCall;
    private long nextId = 1;
    private long tradeSequence;
    private int referenceTick = NO_PRICE;
    private int resting;
    private AuctionTradeSink sink;
    private IndicativeListener indicativeListener;

    // Last equilibrium computed.
    private int eqTick = NO_PRICE;
    private long eqVolume;
    private long eqImbalance;

    /**
     * @param minPriceTick lowest limit price accepted, in ticks
     * @param maxPriceTick highest limit price accepted, in ticks
     * @param maxOrders    order pool size
     */
    public AuctionBook(int minPriceTick, int maxPriceTick, int maxOrders) {
        if (maxPriceTick < minPriceTick || maxOrders <= 0) {
            throw new IllegalArgumentException("need maxPriceTick >= minPriceTick, maxOrders > 0");
        }
        if ((long) maxPriceTick - minPriceTick + 2 > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("price band too wide");
        }
        this.minTick = minPriceTick;
        this.ladder = maxPriceTick - minPriceTick + 1;
        this.market = ladder;
        this.buyHead = filled(ladder + 1);
        this.buyTail = filled(ladder + 1);
        this.buyQty = new long[ladder + 1];
        this.sellHead = filled(ladder + 1);
        this.sellTail = filled(ladder + 1);
        this.sellQty = new long[ladder + 1];
        this.demand = new long[ladder];
        this.nodeId = new long[maxOrders];
        this.nodeQty = new long[maxOrders];
        this.nodeSlot = new int[maxOrders];
        this.nodeSide = new byte[maxOrders];
        this.nodePrev = new int[maxOrders];
        this.nodeNext = new int[maxOrders];
        for (int i = 0; i < maxOrders - 1; i++) {
            nodeNext[i] = i + 1;
        }
        nodeNext[maxOrders - 1] = NONE;
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

    public void tradeSink(AuctionTradeSink s) {
        this.sink = s;
    }

    public void indicativeListener(IndicativeListener l) {
        this.indicativeListener = l;
    }

    /**
     * Reference price for the third tie-break, in ticks; {@link #NO_PRICE}
     * clears it. Must lie in the book's band.
     */
    public void referencePrice(int priceTick) {
        if (priceTick != NO_PRICE && (priceTick < minTick || priceTick - minTick >= ladder)) {
            throw new IllegalArgumentException("reference price outside the band: " + priceTick);
        }
        this.referenceTick = priceTick;
    }

    public int referencePrice() {
        return referenceTick;
    }

    /** Opens the call phase: orders are accepted and do not trade. */
    public void openCall() {
        inCall = true;
    }

    public boolean isInCall() {
        return inCall;
    }

    // ------------------------------------------------------------------
    // Call phase (allocation-free)
    // ------------------------------------------------------------------

    /** Limit order into the call. Returns the positive order id or a negative rejection code. */
    public long submitLimit(Side side, int priceTick, long quantity) {
        if (!inCall) {
            return REJECT_NOT_IN_CALL;
        }
        if (side == null || quantity <= 0) {
            return REJECT_INVALID;
        }
        int slot = priceTick - minTick;
        if (priceTick < minTick || slot >= ladder) {
            return REJECT_OUT_OF_BAND;
        }
        return add(side, slot, quantity);
    }

    /** Market order into the call: executes at the uncrossing price ahead of limit orders. */
    public long submitMarket(Side side, long quantity) {
        if (!inCall) {
            return REJECT_NOT_IN_CALL;
        }
        if (side == null || quantity <= 0) {
            return REJECT_INVALID;
        }
        return add(side, market, quantity);
    }

    private long add(Side side, int slot, long quantity) {
        if (freeHead == NONE) {
            return REJECT_POOL_FULL;
        }
        int node = freeHead;
        freeHead = nodeNext[node];
        long id = nextId++;
        byte s = side == Side.BUY ? BUY : SELL;
        nodeId[node] = id;
        nodeQty[node] = quantity;
        nodeSlot[node] = slot;
        nodeSide[node] = s;
        int[] head = s == BUY ? buyHead : sellHead;
        int[] tail = s == BUY ? buyTail : sellTail;
        nodeNext[node] = NONE;
        nodePrev[node] = tail[slot];
        if (tail[slot] == NONE) {
            head[slot] = node;
        } else {
            nodeNext[tail[slot]] = node;
        }
        tail[slot] = node;
        (s == BUY ? buyQty : sellQty)[slot] += quantity;
        BookPrimitives.mapPut(mapKeys, mapVals, mapMask, id, node);
        resting++;
        return id;
    }

    /** Cancels a resting order (in the call or in the residual book). */
    public boolean cancel(long orderId) {
        int mslot = BookPrimitives.mapFind(mapKeys, mapMask, orderId);
        if (mslot == NONE) {
            return false;
        }
        remove(mapVals[mslot]);
        return true;
    }

    private void remove(int node) {
        byte s = nodeSide[node];
        int slot = nodeSlot[node];
        int[] head = s == BUY ? buyHead : sellHead;
        int[] tail = s == BUY ? buyTail : sellTail;
        (s == BUY ? buyQty : sellQty)[slot] -= nodeQty[node];
        int prev = nodePrev[node];
        int next = nodeNext[node];
        if (prev == NONE) {
            head[slot] = next;
        } else {
            nodeNext[prev] = next;
        }
        if (next == NONE) {
            tail[slot] = prev;
        } else {
            nodePrev[next] = prev;
        }
        BookPrimitives.mapRemoveAt(mapKeys, mapVals, mapMask,
                BookPrimitives.mapFind(mapKeys, mapMask, nodeId[node]));
        nodeNext[node] = freeHead;
        freeHead = node;
        resting--;
    }

    /**
     * Computes the equilibrium of the current book (see the class
     * description) and returns its price, or {@link #NO_PRICE} when nothing
     * would execute. Volume and imbalance are then read from
     * {@link #indicativeVolume()} and {@link #indicativeImbalance()}.
     */
    public int computeIndicative() {
        long marketBuy = buyQty[market];
        long marketSell = sellQty[market];
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        long run = marketBuy;
        long totalSell = marketSell;
        for (int i = ladder - 1; i >= 0; i--) {
            run += buyQty[i];
            demand[i] = run;
            if (buyQty[i] != 0 || sellQty[i] != 0) {
                lo = Math.min(lo, i);
                hi = Math.max(hi, i);
                totalSell += sellQty[i];
            }
        }
        long totalBuy = run;
        int ref = referenceTick == NO_PRICE ? NONE : referenceTick - minTick;
        if (ref != NONE) {
            lo = Math.min(lo, ref);
            hi = Math.max(hi, ref);
        }
        eqTick = NO_PRICE;
        eqVolume = 0;
        eqImbalance = totalBuy - totalSell;
        if (lo > hi) {
            return NO_PRICE;
        }
        long supply = marketSell;
        int best = NONE;
        long bestV = 0;
        long bestAbsImb = Long.MAX_VALUE;
        long bestImb = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = lo; i <= hi; i++) {
            supply += sellQty[i];
            long d = demand[i];
            long v = Math.min(d, supply);
            if (v == 0) {
                continue;
            }
            long imb = d - supply;
            long absImb = Math.abs(imb);
            long dist = ref == NONE ? 0 : Math.abs((long) i - ref);
            if (v > bestV || (v == bestV && (absImb < bestAbsImb
                    || (absImb == bestAbsImb && dist < bestDist)))) {
                best = i;
                bestV = v;
                bestAbsImb = absImb;
                bestImb = imb;
                bestDist = dist;
            }
        }
        if (best == NONE) {
            return NO_PRICE;
        }
        eqTick = best + minTick;
        eqVolume = bestV;
        eqImbalance = bestImb;
        return eqTick;
    }

    /** Computes the equilibrium and sends it to the {@link IndicativeListener}. */
    public int publishIndicative(long timestampNanos) {
        int p = computeIndicative();
        if (indicativeListener != null) {
            indicativeListener.onIndicative(p, eqVolume, eqImbalance, timestampNanos);
        }
        return p;
    }

    /** Price of the last computed equilibrium, or {@link #NO_PRICE}. */
    public int indicativePrice() {
        return eqTick;
    }

    /** Executable volume of the last computed equilibrium; 0 without a price. */
    public long indicativeVolume() {
        return eqVolume;
    }

    /**
     * Imbalance {@code D - S} at the last computed price; without a price, the
     * total buy quantity minus the total sell quantity.
     */
    public long indicativeImbalance() {
        return eqImbalance;
    }

    // ------------------------------------------------------------------
    // Uncross
    // ------------------------------------------------------------------

    /**
     * Ends the call: executes at the uncrossing price in price-time priority,
     * cancels unexecuted market orders, and leaves the remaining limit orders
     * resting (uncrossed). Returns the executed volume (0 when the book did
     * not cross).
     */
    public long uncross(long timestampNanos) {
        inCall = false;
        int price = computeIndicative();
        long left = price == NO_PRICE ? 0 : eqVolume;
        long executed = left;
        if (left > 0) {
            int bSlot = market;
            int sSlot = market;
            int b = buyHead[bSlot];
            int s = sellHead[sSlot];
            while (left > 0) {
                while (b == NONE) {
                    bSlot = bSlot == market ? ladder - 1 : bSlot - 1;
                    b = buyHead[bSlot];
                }
                while (s == NONE) {
                    sSlot = sSlot == market ? 0 : sSlot + 1;
                    s = sellHead[sSlot];
                }
                long q = Math.min(left, Math.min(nodeQty[b], nodeQty[s]));
                if (sink != null) {
                    sink.onTrade(++tradeSequence, nodeId[b], nodeId[s], price, q, timestampNanos);
                } else {
                    ++tradeSequence;
                }
                left -= q;
                b = take(b, q);
                s = take(s, q);
            }
        }
        while (buyHead[market] != NONE) {
            remove(buyHead[market]);
        }
        while (sellHead[market] != NONE) {
            remove(sellHead[market]);
        }
        computeIndicative();
        return executed;
    }

    /** Reduces a node by {@code q}; returns the node to continue with (its successor once empty). */
    private int take(int node, long q) {
        nodeQty[node] -= q;
        (nodeSide[node] == BUY ? buyQty : sellQty)[nodeSlot[node]] -= q;
        if (nodeQty[node] > 0) {
            return node;
        }
        int next = nodeNext[node];
        nodeQty[node] = 0;
        remove(node);
        return next;
    }

    /**
     * Moves every resting limit order into {@code target} (a continuous book),
     * level by level in time order, and empties this book. Only allowed
     * outside the call. Each move is reported to {@code sink}; a negative new
     * id is the target's rejection code.
     */
    public void transferResidual(HftOrderBook target, TransferSink sink, long timestampNanos) {
        if (inCall) {
            throw new IllegalStateException("transfer only after the uncross");
        }
        for (int i = 0; i < ladder; i++) {
            while (buyHead[i] != NONE) {
                transferOne(buyHead[i], Side.BUY, i, target, sink, timestampNanos);
            }
            while (sellHead[i] != NONE) {
                transferOne(sellHead[i], Side.SELL, i, target, sink, timestampNanos);
            }
        }
    }

    private void transferOne(int node, Side side, int slot, HftOrderBook target, TransferSink sink,
                             long ts) {
        long oldId = nodeId[node];
        long qty = nodeQty[node];
        remove(node);
        long newId = target.submitLimit(side, slot + minTick, qty, ts);
        if (sink != null) {
            sink.onTransferred(oldId, newId);
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Total quantity resting at a limit price. */
    public long qtyAtTick(Side side, int priceTick) {
        int slot = priceTick - minTick;
        if (priceTick < minTick || slot >= ladder) {
            return 0;
        }
        return (side == Side.BUY ? buyQty : sellQty)[slot];
    }

    /** Total market-order quantity waiting on a side. */
    public long marketQty(Side side) {
        return (side == Side.BUY ? buyQty : sellQty)[market];
    }

    /** Open quantity of an order, 0 when unknown or done. */
    public long openQuantity(long orderId) {
        int mslot = BookPrimitives.mapFind(mapKeys, mapMask, orderId);
        return mslot == NONE ? 0 : nodeQty[mapVals[mslot]];
    }

    public int restingOrders() {
        return resting;
    }

    /** Trades emitted so far. */
    public long tradeCount() {
        return tradeSequence;
    }
}

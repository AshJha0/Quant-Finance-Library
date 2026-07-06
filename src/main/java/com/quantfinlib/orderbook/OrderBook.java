package com.quantfinlib.orderbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.ArrayDeque;

/**
 * Price-time-priority limit order book with continuous matching. Models order
 * placement, cancellation and execution so microstructure effects — spread
 * dynamics, queue priority, book sweeps — can be studied directly.
 *
 * <p><b>Which lane this is in</b>: this is the <em>research-grade venue
 * model</em> — used by the microstructure fuzz tests, queue analytics and
 * simulations — NOT part of the measured ultra-low-latency path. The
 * library's sub-microsecond numbers are <em>participant-side</em>
 * (tick → strategy/quoter → risk gate → order ring → venue adapter); in that
 * architecture matching happens at the exchange, not in this process, so no
 * order on the hot path ever touches this class. Internally it deliberately
 * favors clarity over allocation discipline ({@code TreeMap<Double,…>}
 * boxing, per-order objects, iterators) — adequate for simulation, and
 * exactly what a venue-grade core must NOT do. A venue-grade book (dense
 * integer-tick price ladder, pooled intrusive order nodes, primitive
 * open-addressing id map, zero iterators) is a documented non-goal today —
 * see {@code docs/ULTRA_LOW_LATENCY.md}, "where this repository deliberately
 * stops".</p>
 *
 * <p>Keeps message counters (orders, cancels, trades) for order-to-trade
 * ratio and surveillance analytics. Single-threaded by design: drive it from
 * one strategy/simulation thread (or the HFT bus consumer thread).</p>
 */
public final class OrderBook {

    /** Fill callback: maker is the resting order, taker the incoming one. */
    public interface TradeListener {
        void onTrade(long makerOrderId, long takerOrderId, double price, long quantity, long timestampNanos);
    }

    private static final class PriceLevel {
        final double price;
        final ArrayDeque<LimitOrder> queue = new ArrayDeque<>();
        long totalQty;

        PriceLevel(double price) {
            this.price = price;
        }
    }

    private final String symbol;
    private final NavigableMap<Double, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Double, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, LimitOrder> orders = new HashMap<>();
    private final List<TradeListener> listeners = new ArrayList<>();
    private long nextId = 1;
    private long orderCount;
    private long cancelCount;
    private long tradeCount;

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public void addTradeListener(TradeListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------
    // Order entry
    // ------------------------------------------------------------------

    /**
     * Submits a limit order: matches any crossing liquidity, then rests the
     * remainder. Returns the order id (usable for {@link #cancel} and
     * {@link #qtyAhead} while any quantity rests).
     */
    public long submitLimit(Side side, double price, long quantity, long timestampNanos) {
        orderCount++;
        long id = nextId++;
        long remaining = match(side, quantity, price, false, id, timestampNanos);
        if (remaining > 0) {
            LimitOrder order = new LimitOrder(id, side, price, remaining, timestampNanos);
            PriceLevel level = book(side).computeIfAbsent(price, PriceLevel::new);
            level.queue.addLast(order);
            level.totalQty += remaining;
            orders.put(id, order);
        }
        return id;
    }

    /** Submits a market order; any unfilled remainder is discarded. Returns filled quantity. */
    public long submitMarket(Side side, long quantity, long timestampNanos) {
        orderCount++;
        long id = nextId++;
        long remaining = match(side, quantity, 0, true, id, timestampNanos);
        return quantity - remaining;
    }

    /** Cancels a resting order. Returns false if unknown or fully filled. */
    public boolean cancel(long orderId) {
        LimitOrder order = orders.remove(orderId);
        if (order == null) {
            return false;
        }
        cancelCount++;
        PriceLevel level = book(order.side()).get(order.price());
        if (level != null) {
            level.queue.remove(order);
            level.totalQty -= order.quantity();
            if (level.queue.isEmpty()) {
                book(order.side()).remove(order.price());
            }
        }
        return true;
    }

    private long match(Side takerSide, long qty, double limitPrice, boolean market,
                       long takerId, long timestampNanos) {
        NavigableMap<Double, PriceLevel> opposite = takerSide == Side.BUY ? asks : bids;
        while (qty > 0 && !opposite.isEmpty()) {
            PriceLevel best = opposite.firstEntry().getValue();
            if (!market) {
                if (takerSide == Side.BUY && best.price > limitPrice) {
                    break;
                }
                if (takerSide == Side.SELL && best.price < limitPrice) {
                    break;
                }
            }
            LimitOrder maker = best.queue.peekFirst();
            long fill = Math.min(qty, maker.quantity);
            maker.quantity -= fill;
            best.totalQty -= fill;
            qty -= fill;
            tradeCount++;
            for (TradeListener l : listeners) {
                l.onTrade(maker.id(), takerId, best.price, fill, timestampNanos);
            }
            if (maker.quantity == 0) {
                best.queue.pollFirst();
                orders.remove(maker.id());
            }
            if (best.queue.isEmpty()) {
                opposite.remove(best.price);
            }
        }
        return qty;
    }

    // ------------------------------------------------------------------
    // Top of book / depth
    // ------------------------------------------------------------------

    public double bestBid()      { return bids.isEmpty() ? Double.NaN : bids.firstKey(); }
    public double bestAsk()      { return asks.isEmpty() ? Double.NaN : asks.firstKey(); }
    public long bestBidSize()    { return bids.isEmpty() ? 0 : bids.firstEntry().getValue().totalQty; }
    public long bestAskSize()    { return asks.isEmpty() ? 0 : asks.firstEntry().getValue().totalQty; }

    public double mid() {
        double b = bestBid(), a = bestAsk();
        return Double.isNaN(b) || Double.isNaN(a) ? Double.NaN : (b + a) / 2;
    }

    public double spread() {
        return bestAsk() - bestBid();
    }

    /**
     * Price/quantity pairs for the given side's best {@code maxLevels}
     * (side = the resting liquidity side: BUY returns bids).
     */
    public List<double[]> levels(Side side, int maxLevels) {
        List<double[]> out = new ArrayList<>(Math.min(maxLevels, 16));
        for (PriceLevel level : book(side).values()) {
            if (out.size() >= maxLevels) {
                break;
            }
            out.add(new double[]{level.price, level.totalQty});
        }
        return out;
    }

    /** Total resting quantity on a side across its best {@code maxLevels}. */
    public long depth(Side side, int maxLevels) {
        long total = 0;
        int n = 0;
        for (PriceLevel level : book(side).values()) {
            if (n++ >= maxLevels) {
                break;
            }
            total += level.totalQty;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Queue position & surveillance counters
    // ------------------------------------------------------------------

    /** Quantity queued ahead of the given resting order at its price level. */
    public long qtyAhead(long orderId) {
        LimitOrder order = orders.get(orderId);
        if (order == null) {
            return -1;
        }
        PriceLevel level = book(order.side()).get(order.price());
        long ahead = 0;
        for (LimitOrder o : level.queue) {
            if (o.id() == orderId) {
                return ahead;
            }
            ahead += o.quantity();
        }
        return ahead;
    }

    public LimitOrder order(long orderId) {
        return orders.get(orderId);
    }

    public long orderCount()  { return orderCount; }
    public long cancelCount() { return cancelCount; }
    public long tradeCount()  { return tradeCount; }

    /** Order-to-trade ratio: messages (orders + cancels) per trade. */
    public double orderToTradeRatio() {
        return tradeCount == 0 ? Double.POSITIVE_INFINITY : (double) (orderCount + cancelCount) / tradeCount;
    }

    private NavigableMap<Double, PriceLevel> book(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}

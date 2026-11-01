package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.risk.PreTradeLimitChecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Quote-driven paper trading venue: runs real strategy and risk-gate code
 * against simulated fills.
 *
 * <h2>Fill model</h2>
 * <ul>
 *   <li>Top of book arrives through {@link #onQuote(String, double, double, double, double)}
 *       with displayed sizes. Liquidity is finite: each order fills at most
 *       the displayed size at the touch, and that size is consumed, so two
 *       orders cannot both take the same displayed quantity. The two-argument
 *       {@link #onQuote(String, double, double)} means unlimited size.</li>
 *   <li>Market orders fill at the touch up to the displayed size; any
 *       remainder is cancelled (immediate-or-cancel), leaving the order
 *       CANCELED with a partial fill. With no quote they are rejected.</li>
 *   <li>Limit orders fill immediately when marketable (at the touch, up to
 *       displayed size) and rest otherwise; a resting order fills when a
 *       later quote crosses its limit, again limited by displayed size, at
 *       the better of its limit and the touch.</li>
 *   <li>Fills are instantaneous: there is no latency or queue-position
 *       model, so passive fills are optimistic.</li>
 * </ul>
 *
 * <p>Order states follow {@link OrderStateMachine}: NEW on acceptance,
 * PARTIALLY_FILLED, FILLED, CANCELED or REJECTED. Every order passes the
 * optional {@link PreTradeLimitChecker} first.</p>
 *
 * <p>Accounting: signed positions with average cost, cash, realised and
 * unrealised P&amp;L, commission, and mark-to-market equity at the mid.</p>
 *
 * <p>Thread-safe: state changes synchronize on the gateway and
 * {@link #snapshot()} is internally consistent. Execution listeners are
 * called after the lock is released, on the calling thread, so a listener
 * may call back into the gateway or take its own locks without deadlock;
 * fills from one call are delivered in order, but listeners on different
 * threads can see interleaved deliveries.</p>
 */
public final class PaperTradingGateway implements OrderGateway {

    private static final class WorkingOrder {
        final long id;
        final String symbol;
        final Side side;
        final double limitPrice;   // NaN = market
        final long quantity;
        long filled;
        int state;

        WorkingOrder(long id, String symbol, Side side, long quantity, double limitPrice) {
            this.id = id;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.limitPrice = limitPrice;
            this.state = OrderStateMachine.PENDING_NEW;
        }

        long leaves() {
            return quantity - filled;
        }
    }

    private static final class Position {
        double quantity;
        double avgCost;
    }

    private static final class Quote {
        final double bid;
        final double ask;
        double bidSize;
        double askSize;

        Quote(double bid, double bidSize, double ask, double askSize) {
            this.bid = bid;
            this.ask = ask;
            this.bidSize = bidSize;
            this.askSize = askSize;
        }

        double mid() {
            return (bid + ask) / 2;
        }
    }

    private record Fill(long id, String symbol, Side side, double price, long qty, long ts) {
    }

    private final PreTradeLimitChecker limitChecker;   // null = no gate
    private final double commissionRate;
    private final Map<String, Quote> quotes = new HashMap<>();
    private final Map<Long, WorkingOrder> orders = new HashMap<>();
    private final List<WorkingOrder> resting = new ArrayList<>();
    private final Map<String, Position> positions = new HashMap<>();
    private final List<ExecutionListener> listeners = new CopyOnWriteArrayList<>();
    // Capped: a soak with a misconfigured limit checker rejects every order,
    // and an unbounded String log would grow the heap for the whole run.
    private static final int REJECTION_LOG_CAP = 1_000;
    private final List<String> rejectionLog = new ArrayList<>();
    private int rejectionCount;
    private double cash;
    private double realizedPnl;
    private long nextId = 1;

    public PaperTradingGateway(double initialCash) {
        this(initialCash, 0, null);
    }

    public PaperTradingGateway(double initialCash, double commissionRate,
                               PreTradeLimitChecker limitChecker) {
        this.cash = initialCash;
        this.commissionRate = commissionRate;
        this.limitChecker = limitChecker;
    }

    // ------------------------------------------------------------------
    // Market data in
    // ------------------------------------------------------------------

    /** Top of book with unlimited displayed size. */
    public void onQuote(String symbol, double bid, double ask) {
        onQuote(symbol, bid, Double.POSITIVE_INFINITY, ask, Double.POSITIVE_INFINITY);
    }

    /**
     * Updates the top of book and fills resting limit orders that now cross,
     * oldest first, up to the displayed size on the crossing side.
     */
    public void onQuote(String symbol, double bid, double bidSize, double ask, double askSize) {
        List<Fill> out = new ArrayList<>();
        synchronized (this) {
            Quote q = new Quote(bid, bidSize, ask, askSize);
            quotes.put(symbol, q);
            resting.removeIf(order -> {
                if (!order.symbol.equals(symbol)) {
                    return false;
                }
                if (order.side == Side.BUY && ask <= order.limitPrice) {
                    takeLiquidity(order, q, Math.min(order.limitPrice, ask), out);
                } else if (order.side == Side.SELL && bid >= order.limitPrice) {
                    takeLiquidity(order, q, Math.max(order.limitPrice, bid), out);
                }
                return OrderStateMachine.isTerminal(order.state);
            });
        }
        dispatch(out);
    }

    // ------------------------------------------------------------------
    // OrderGateway
    // ------------------------------------------------------------------

    @Override
    public long submitLimit(String symbol, Side side, long quantity, double price) {
        List<Fill> out = new ArrayList<>();
        long id;
        synchronized (this) {
            WorkingOrder order = new WorkingOrder(nextId++, symbol, side, quantity, price);
            orders.put(order.id, order);
            id = order.id;
            if (!(price > 0) || price == Double.POSITIVE_INFINITY || quantity <= 0) {
                reject(order, "INVALID_ORDER");
            } else if (passesRiskGate(order, price)) {
                order.state = OrderStateMachine.next(order.state, OrderStateMachine.EVENT_ACK);
                Quote q = quotes.get(symbol);
                if (q != null && (side == Side.BUY ? q.ask <= price : q.bid >= price)) {
                    takeLiquidity(order, q, side == Side.BUY ? q.ask : q.bid, out);
                }
                if (OrderStateMachine.isLive(order.state)) {
                    resting.add(order);
                }
            }
        }
        dispatch(out);
        return id;
    }

    @Override
    public long submitMarket(String symbol, Side side, long quantity) {
        List<Fill> out = new ArrayList<>();
        long id;
        synchronized (this) {
            WorkingOrder order = new WorkingOrder(nextId++, symbol, side, quantity, Double.NaN);
            orders.put(order.id, order);
            id = order.id;
            Quote q = quotes.get(symbol);
            if (quantity <= 0) {
                reject(order, "INVALID_ORDER");
            } else if (q == null) {
                reject(order, "NO_QUOTE for " + symbol);
            } else {
                double touch = side == Side.BUY ? q.ask : q.bid;
                if (passesRiskGate(order, touch)) {
                    order.state = OrderStateMachine.next(order.state,
                            OrderStateMachine.EVENT_ACK);
                    takeLiquidity(order, q, touch, out);
                    if (OrderStateMachine.isLive(order.state)) {
                        // Immediate-or-cancel remainder.
                        order.state = OrderStateMachine.next(order.state,
                                OrderStateMachine.EVENT_CANCELED);
                    }
                }
            }
        }
        dispatch(out);
        return id;
    }

    @Override
    public synchronized boolean cancel(long orderId) {
        WorkingOrder order = orders.get(orderId);
        if (order == null) {
            return false;
        }
        int next = OrderStateMachine.next(order.state, OrderStateMachine.EVENT_CANCELED);
        if (next == OrderStateMachine.ILLEGAL) {
            return false;
        }
        order.state = next;
        resting.remove(order);
        return true;
    }

    @Override
    public synchronized OrderStatus status(long orderId) {
        WorkingOrder order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("unknown order " + orderId);
        }
        return OrderStateMachine.toStatus(order.state);
    }

    /** Quantity filled so far on an order. */
    public synchronized long filledQuantity(long orderId) {
        WorkingOrder order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("unknown order " + orderId);
        }
        return order.filled;
    }

    @Override
    public void addExecutionListener(ExecutionListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    public synchronized double position(String symbol) {
        Position p = positions.get(symbol);
        return p == null ? 0 : p.quantity;
    }

    public synchronized double cash() {
        return cash;
    }

    public synchronized double realizedPnl() {
        return realizedPnl;
    }

    /** Mark-to-market equity at current mids. */
    public synchronized double equity() {
        double value = cash;
        for (Map.Entry<String, Position> e : positions.entrySet()) {
            Quote q = quotes.get(e.getKey());
            if (q != null) {
                value += e.getValue().quantity * q.mid();
            }
        }
        return value;
    }

    /** The first {@value #REJECTION_LOG_CAP} rejection messages; the snapshot's
     *  {@code rejectionCount} keeps counting past the cap. */
    public synchronized List<String> rejectionLog() {
        return List.copyOf(rejectionLog);
    }

    /** Snapshot of non-zero positions by symbol. */
    public synchronized Map<String, Double> positionsSnapshot() {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        positions.forEach((symbol, p) -> {
            if (p.quantity != 0) {
                out.put(symbol, p.quantity);
            }
        });
        return out;
    }

    /** One internally consistent view of the whole account (single lock acquisition). */
    public record AccountSnapshot(double cash, double equity, double realizedPnl,
                                  int rejectionCount, Map<String, Double> positions) {
    }

    public synchronized AccountSnapshot snapshot() {
        return new AccountSnapshot(cash, equity(), realizedPnl,
                rejectionCount, positionsSnapshot());
    }

    // ------------------------------------------------------------------

    private boolean passesRiskGate(WorkingOrder order, double referencePrice) {
        if (limitChecker == null) {
            return true;
        }
        Quote q = quotes.get(order.symbol);
        double mid = q == null ? Double.NaN : q.mid();
        PreTradeLimitChecker.CheckResult check = limitChecker.check(
                new PreTradeLimitChecker.OrderRequest(order.symbol, order.side,
                        order.quantity, referencePrice, "PAPER"),
                mid, Math.round(position(order.symbol)), 0);
        if (!check.approved()) {
            reject(order, String.valueOf(check.violations()));
            return false;
        }
        return true;
    }

    private void reject(WorkingOrder order, String reason) {
        order.state = OrderStateMachine.REJECTED;
        rejectionCount++;
        if (rejectionLog.size() < REJECTION_LOG_CAP) {
            rejectionLog.add("order " + order.id + ": " + reason);
        }
    }

    /** Fills up to the displayed size on the side this order takes; consumes that size. */
    private void takeLiquidity(WorkingOrder order, Quote q, double price, List<Fill> out) {
        double displayed = order.side == Side.BUY ? q.askSize : q.bidSize;
        long qty = (long) Math.min((double) order.leaves(), Math.floor(displayed));
        if (qty <= 0) {
            return;
        }
        if (order.side == Side.BUY) {
            q.askSize -= qty;
        } else {
            q.bidSize -= qty;
        }
        order.filled += qty;
        order.state = OrderStateMachine.next(order.state, order.leaves() == 0
                ? OrderStateMachine.EVENT_FULL_FILL : OrderStateMachine.EVENT_PARTIAL_FILL);
        applyFill(order.symbol, order.side, price, qty);
        out.add(new Fill(order.id, order.symbol, order.side, price, qty, System.nanoTime()));
    }

    private void dispatch(List<Fill> fills) {
        for (Fill f : fills) {
            for (ExecutionListener l : listeners) {
                l.onFill(f.id(), f.symbol(), f.side(), f.price(), f.qty(), f.ts());
            }
        }
    }

    /** Signed average-cost accounting with realised P&amp;L on position reduction. */
    private void applyFill(String symbol, Side side, double price, long qty) {
        double signed = side == Side.BUY ? qty : -qty;
        double notional = price * qty;
        cash += side == Side.BUY ? -notional : notional;
        double fee = notional * commissionRate;
        cash -= fee;

        Position p = positions.computeIfAbsent(symbol, k -> new Position());
        if (p.quantity == 0 || Math.signum(p.quantity) == Math.signum(signed)) {
            double newQty = p.quantity + signed;
            p.avgCost = (p.avgCost * Math.abs(p.quantity) + price * qty) / Math.abs(newQty);
            p.quantity = newQty;
        } else {
            double closing = Math.min(Math.abs(p.quantity), (double) qty);
            realizedPnl += (price - p.avgCost) * closing * Math.signum(p.quantity);
            double newQty = p.quantity + signed;
            if (Math.signum(newQty) != Math.signum(p.quantity) && newQty != 0) {
                p.avgCost = price;   // position flipped: remainder opened at this fill
            }
            p.quantity = newQty;
            if (newQty == 0) {
                p.avgCost = 0;
            }
        }
    }
}

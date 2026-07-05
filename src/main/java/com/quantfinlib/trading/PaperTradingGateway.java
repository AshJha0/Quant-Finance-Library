package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.risk.PreTradeLimitChecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quote-driven paper trading venue: closes the research-to-production loop by
 * running real strategy + risk-gate code against simulated fills.
 *
 * <ul>
 *   <li>Feed top-of-book quotes with {@link #onQuote}; market orders fill at
 *       the touch, resting limit orders fill when the market crosses them.</li>
 *   <li>Every order passes the optional {@link PreTradeLimitChecker} first —
 *       rejected orders never reach the market, exactly like production.</li>
 *   <li>Full account tracking: signed positions with average cost, cash,
 *       realized/unrealized P&amp;L, commission, and mark-to-market equity.</li>
 * </ul>
 *
 * Thread-safe: all operations synchronize on the gateway, and
 * {@link #snapshot()} returns an internally consistent view of the account —
 * safe to read from a dashboard thread while another thread trades.
 */
public final class PaperTradingGateway implements OrderGateway {

    private static final class WorkingOrder {
        final long id;
        final String symbol;
        final Side side;
        final double limitPrice;   // NaN = market
        long quantity;
        OrderStatus status;

        WorkingOrder(long id, String symbol, Side side, long quantity, double limitPrice) {
            this.id = id;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.limitPrice = limitPrice;
            this.status = OrderStatus.NEW;
        }
    }

    private static final class Position {
        double quantity;
        double avgCost;
    }

    private record Quote(double bid, double ask) {
        double mid() {
            return (bid + ask) / 2;
        }
    }

    private final PreTradeLimitChecker limitChecker;   // null = no gate
    private final double commissionRate;
    private final Map<String, Quote> quotes = new HashMap<>();
    private final Map<Long, WorkingOrder> orders = new HashMap<>();
    private final List<WorkingOrder> resting = new ArrayList<>();
    private final Map<String, Position> positions = new HashMap<>();
    private final List<ExecutionListener> listeners = new ArrayList<>();
    private final List<String> rejectionLog = new ArrayList<>();
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

    /** Updates the top of book and fills any resting limit orders that now cross. */
    public synchronized void onQuote(String symbol, double bid, double ask) {
        quotes.put(symbol, new Quote(bid, ask));
        resting.removeIf(order -> {
            if (!order.symbol.equals(symbol)) {
                return false;
            }
            if (order.side == Side.BUY && ask <= order.limitPrice) {
                fill(order, Math.min(order.limitPrice, ask));
                return true;
            }
            if (order.side == Side.SELL && bid >= order.limitPrice) {
                fill(order, Math.max(order.limitPrice, bid));
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------
    // OrderGateway
    // ------------------------------------------------------------------

    @Override
    public synchronized long submitLimit(String symbol, Side side, long quantity, double price) {
        WorkingOrder order = new WorkingOrder(nextId++, symbol, side, quantity, price);
        orders.put(order.id, order);
        if (!passesRiskGate(order, price)) {
            return order.id;
        }
        Quote q = quotes.get(symbol);
        if (q != null && (side == Side.BUY ? q.ask() <= price : q.bid() >= price)) {
            fill(order, side == Side.BUY ? q.ask() : q.bid());   // marketable: fill at touch
        } else {
            resting.add(order);
        }
        return order.id;
    }

    @Override
    public synchronized long submitMarket(String symbol, Side side, long quantity) {
        WorkingOrder order = new WorkingOrder(nextId++, symbol, side, quantity, Double.NaN);
        orders.put(order.id, order);
        Quote q = quotes.get(symbol);
        if (q == null) {
            order.status = OrderStatus.REJECTED;
            rejectionLog.add("order " + order.id + ": NO_QUOTE for " + symbol);
            return order.id;
        }
        double touch = side == Side.BUY ? q.ask() : q.bid();
        if (!passesRiskGate(order, touch)) {
            return order.id;
        }
        fill(order, touch);
        return order.id;
    }

    @Override
    public synchronized boolean cancel(long orderId) {
        WorkingOrder order = orders.get(orderId);
        if (order == null || order.status != OrderStatus.NEW) {
            return false;
        }
        order.status = OrderStatus.CANCELED;
        resting.remove(order);
        return true;
    }

    @Override
    public synchronized OrderStatus status(long orderId) {
        WorkingOrder order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("unknown order " + orderId);
        }
        return order.status;
    }

    @Override
    public synchronized void addExecutionListener(ExecutionListener listener) {
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

    public synchronized List<String> rejectionLog() {
        return List.copyOf(rejectionLog);
    }

    /** Snapshot of non-zero positions by symbol (for dashboards/monitoring). */
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
                rejectionLog.size(), positionsSnapshot());
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
            order.status = OrderStatus.REJECTED;
            rejectionLog.add("order " + order.id + ": " + check.violations());
            return false;
        }
        return true;
    }

    private void fill(WorkingOrder order, double price) {
        long qty = order.quantity;
        applyFill(order.symbol, order.side, price, qty);
        order.quantity = 0;
        order.status = OrderStatus.FILLED;
        long ts = System.nanoTime();
        for (ExecutionListener l : listeners) {
            l.onFill(order.id, order.symbol, order.side, price, qty, ts);
        }
    }

    /** Signed average-cost accounting with realized P&L on position reduction. */
    private void applyFill(String symbol, Side side, double price, long qty) {
        double signed = side == Side.BUY ? qty : -qty;
        double notional = price * qty;
        cash += side == Side.BUY ? -notional : notional;
        double fee = notional * commissionRate;
        cash -= fee;

        Position p = positions.computeIfAbsent(symbol, k -> new Position());
        if (p.quantity == 0 || Math.signum(p.quantity) == Math.signum(signed)) {
            // Opening or adding: blend the average cost.
            double newQty = p.quantity + signed;
            p.avgCost = (p.avgCost * Math.abs(p.quantity) + price * qty) / Math.abs(newQty);
            p.quantity = newQty;
        } else {
            double closing = Math.min(Math.abs(p.quantity), qty);
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

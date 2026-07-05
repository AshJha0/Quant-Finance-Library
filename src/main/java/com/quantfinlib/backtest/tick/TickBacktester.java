package com.quantfinlib.backtest.tick;

import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.data.TickFileReader;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Event-driven tick-level backtester: replays a captured QFLT tick file
 * through a {@link TickStrategy} with microstructure-aware fills — the level
 * below the bar-based engines, where queue position decides whether your
 * passive order actually trades.
 *
 * <p>Fill model (trade-print data, no book snapshots):</p>
 * <ul>
 *   <li><b>Market orders</b> fill instantly at the last trade price ± half
 *       the configured spread — the aggressor pays the spread.</li>
 *   <li><b>Limit orders</b> fill fully when a print trades <i>through</i> the
 *       limit price. Prints <i>at</i> the limit price accumulate: the order
 *       starts behind {@code defaultQueueAhead} simulated resting quantity
 *       and fills (partially) only as traded volume works that queue off —
 *       passive fills must be earned, not assumed.</li>
 *   <li>Orders placed while handling a tick are eligible from the next tick,
 *       never against the print that triggered them.</li>
 * </ul>
 *
 * <p>Equity is sampled every {@code equitySampleEvery} ticks; metrics are
 * computed on that sampled curve with {@code periodsPerYear} left at the
 * bar-engine default, so compare Sharpe-style numbers only between runs with
 * the same sampling interval.</p>
 */
public final class TickBacktester implements TickFileReader.ReplayHandler, TickTradingContext {

    /**
     * {@code tickSize > 0} snaps limit-order matching to the exchange price
     * grid (prices within the same tick are one level); 0 falls back to
     * epsilon equality — use a real tick size with real market data.
     */
    public record Config(double initialCash, double spreadBps, double commissionBps,
                         long defaultQueueAhead, int equitySampleEvery, double tickSize) {

        public static Config defaults() {
            return new Config(1_000_000, 2.0, 0.2, 0, 1_000, 0);
        }

        public Config withSpreadBps(double bps) {
            return new Config(initialCash, bps, commissionBps, defaultQueueAhead,
                    equitySampleEvery, tickSize);
        }

        public Config withCommissionBps(double bps) {
            return new Config(initialCash, spreadBps, bps, defaultQueueAhead,
                    equitySampleEvery, tickSize);
        }

        public Config withDefaultQueueAhead(long qty) {
            return new Config(initialCash, spreadBps, commissionBps, qty,
                    equitySampleEvery, tickSize);
        }

        public Config withEquitySampleEvery(int ticks) {
            return new Config(initialCash, spreadBps, commissionBps, defaultQueueAhead,
                    ticks, tickSize);
        }

        public Config withTickSize(double size) {
            return new Config(initialCash, spreadBps, commissionBps, defaultQueueAhead,
                    equitySampleEvery, size);
        }
    }

    public record TickBacktestResult(String strategyName, List<Execution> fills,
                                     double[] sampledEquity, double finalEquity,
                                     long ticksProcessed, PerformanceMetrics metrics) {
    }

    private static final double PRICE_EPS = 1e-9;

    private static final class WorkingOrder {
        final long id;
        final int symbolId;
        final Side side;
        final double price;
        final double queueAhead;
        long remaining;
        double volumeAtPrice;
        long filledFromQueue;

        WorkingOrder(long id, int symbolId, Side side, double price, long quantity, double queueAhead) {
            this.id = id;
            this.symbolId = symbolId;
            this.side = side;
            this.price = price;
            this.remaining = quantity;
            this.queueAhead = queueAhead;
        }
    }

    private final TickStrategy strategy;
    private final Config config;
    private final List<WorkingOrder> working = new ArrayList<>();
    private final List<Execution> fills = new ArrayList<>();
    private final List<Double> equitySamples = new ArrayList<>();
    private long[] positions = new long[16];
    private double[] lastPrices = new double[16];
    private String[] symbols = new String[16];
    private double cash;
    private long ticks;
    private long lastTimestamp;
    private long nextOrderId = 1;

    private TickBacktester(TickStrategy strategy, Config config) {
        this.strategy = strategy;
        this.config = config;
        this.cash = config.initialCash();
        Arrays.fill(lastPrices, Double.NaN);
        equitySamples.add(config.initialCash());
    }

    /** Replays the tick file through the strategy and returns the result. */
    public static TickBacktestResult run(TickStrategy strategy, Path tickFile, Config config)
            throws IOException {
        TickBacktester engine = new TickBacktester(strategy, config);
        strategy.init(engine);
        TickFileReader.replay(tickFile, engine);
        return engine.finish();
    }

    // ------------------------------------------------------------------
    // ReplayHandler
    // ------------------------------------------------------------------

    @Override
    public void onSymbol(int symbolId, String symbol) {
        ensureCapacity(symbolId);
        symbols[symbolId] = symbol;
    }

    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        lastTimestamp = timestampNanos;
        // 1. Resting orders are judged against the print before the strategy reacts.
        matchWorkingOrders(symbolId, price, size, timestampNanos);
        lastPrices[symbolId] = price;
        // 2. Strategy sees the tick (orders it places wait for the next print).
        strategy.onTick(symbolId, price, size, timestampNanos);
        // 3. Periodic equity sampling.
        if (++ticks % config.equitySampleEvery() == 0) {
            equitySamples.add(equity());
        }
    }

    private void matchWorkingOrders(int symbolId, double price, double size, long ts) {
        Iterator<WorkingOrder> it = working.iterator();
        while (it.hasNext()) {
            WorkingOrder order = it.next();
            if (order.symbolId != symbolId) {
                continue;
            }
            boolean through = tradesThrough(order.side, price, order.price);
            if (through) {
                // Traded through the level: we would have been filled at our price.
                applyFill(order.symbolId, order.side, order.price, order.remaining, ts);
                order.remaining = 0;
            } else if (samePriceLevel(price, order.price)) {
                // Trading at our level: volume works off the queue ahead first.
                order.volumeAtPrice += size;
                long fillable = (long) Math.max(0, order.volumeAtPrice - order.queueAhead);
                long qty = Math.min(order.remaining, fillable - order.filledFromQueue);
                if (qty > 0) {
                    order.filledFromQueue += qty;
                    order.remaining -= qty;
                    applyFill(order.symbolId, order.side, order.price, qty, ts);
                }
            }
            if (order.remaining == 0) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    // TickTradingContext
    // ------------------------------------------------------------------

    @Override
    public long submitLimit(int symbolId, Side side, double price, long quantity) {
        if (quantity <= 0 || price <= 0) {
            throw new IllegalArgumentException("need positive price and quantity");
        }
        ensureCapacity(symbolId);
        WorkingOrder order = new WorkingOrder(nextOrderId++, symbolId, side, price,
                quantity, config.defaultQueueAhead());
        working.add(order);
        return order.id;
    }

    @Override
    public long submitMarket(int symbolId, Side side, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("need positive quantity");
        }
        ensureCapacity(symbolId);
        double last = lastPrices[symbolId];
        if (Double.isNaN(last)) {
            return -1;   // no price seen yet
        }
        double fillPrice = last * (1 + side.sign() * config.spreadBps() / 2 / 1e4);
        applyFill(symbolId, side, fillPrice, quantity, lastTimestamp);
        return nextOrderId++;
    }

    @Override
    public boolean cancel(long orderId) {
        Iterator<WorkingOrder> it = working.iterator();
        while (it.hasNext()) {
            if (it.next().id == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    @Override
    public long position(int symbolId) {
        return symbolId < positions.length ? positions[symbolId] : 0;
    }

    @Override
    public double cash() {
        return cash;
    }

    @Override
    public double lastPrice(int symbolId) {
        return symbolId < lastPrices.length ? lastPrices[symbolId] : Double.NaN;
    }

    @Override
    public String symbolName(int symbolId) {
        String name = symbolId < symbols.length ? symbols[symbolId] : null;
        return name == null ? "#" + symbolId : name;
    }

    // ------------------------------------------------------------------

    private boolean samePriceLevel(double a, double b) {
        if (config.tickSize() > 0) {
            return Math.round(a / config.tickSize()) == Math.round(b / config.tickSize());
        }
        return Math.abs(a - b) <= PRICE_EPS;
    }

    private boolean tradesThrough(Side side, double printPrice, double limitPrice) {
        if (config.tickSize() > 0) {
            long print = Math.round(printPrice / config.tickSize());
            long limit = Math.round(limitPrice / config.tickSize());
            return side == Side.BUY ? print < limit : print > limit;
        }
        return side == Side.BUY
                ? printPrice < limitPrice - PRICE_EPS
                : printPrice > limitPrice + PRICE_EPS;
    }

    private void applyFill(int symbolId, Side side, double price, long quantity, long ts) {
        double notional = price * quantity;
        cash += side == Side.SELL ? notional : -notional;
        cash -= notional * config.commissionBps() / 1e4;
        positions[symbolId] += side.sign() * quantity;
        fills.add(new Execution(symbolName(symbolId), side, price, quantity, ts, "TICK_SIM"));
    }

    private double equity() {
        double value = cash;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] != 0) {
                value += positions[i] * lastPrices[i];
            }
        }
        return value;
    }

    private TickBacktestResult finish() {
        equitySamples.add(equity());
        double[] sampled = new double[equitySamples.size()];
        for (int i = 0; i < sampled.length; i++) {
            sampled[i] = equitySamples.get(i);
        }
        PerformanceMetrics metrics = PerformanceAnalytics.compute(sampled, List.of(), 252);
        return new TickBacktestResult(strategy.name(), List.copyOf(fills),
                sampled, sampled[sampled.length - 1], ticks, metrics);
    }

    private void ensureCapacity(int symbolId) {
        if (symbolId < positions.length) {
            return;
        }
        int cap = Integer.highestOneBit(symbolId) * 2;
        int old = positions.length;
        positions = Arrays.copyOf(positions, cap);
        lastPrices = Arrays.copyOf(lastPrices, cap);
        symbols = Arrays.copyOf(symbols, cap);
        Arrays.fill(lastPrices, old, cap, Double.NaN);
    }
}

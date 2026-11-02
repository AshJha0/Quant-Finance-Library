package com.quantfinlib.marketdata;

import com.quantfinlib.risk.Portfolio;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Real-Time Market Data Processing pipeline. Producers publish ticks into a
 * lock-free ring buffer; a dedicated consumer thread dispatches to per-symbol
 * and global listeners, maintains a latest-price cache, and can keep a
 * {@link Portfolio} continuously marked to market.
 */
public final class MarketDataProcessor implements AutoCloseable {

    private final RingBuffer<MarketDataEvent> buffer;
    private final Map<String, List<MarketDataListener>> symbolListeners = new ConcurrentHashMap<>();
    private final List<MarketDataListener> globalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Double> latestPrices = new ConcurrentHashMap<>();
    private final List<Portfolio> monitoredPortfolios = new CopyOnWriteArrayList<>();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private Thread consumer;

    public MarketDataProcessor(int bufferCapacity) {
        this.buffer = new RingBuffer<>(bufferCapacity);
    }

    public MarketDataProcessor() {
        this(1 << 16);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        consumer = new Thread(this::consumeLoop, "market-data-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    /** Stops the consumer after draining any queued events. */
    public synchronized void stop() {
        running = false;
        if (consumer != null) {
            try {
                consumer.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            consumer = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Publishes a tick; single-producer. Returns false if the buffer was full (tick dropped). */
    public boolean publish(MarketDataEvent event) {
        boolean ok = buffer.offer(event);
        if (!ok) {
            dropped.incrementAndGet();
        }
        return ok;
    }

    public void subscribe(String symbol, MarketDataListener listener) {
        symbolListeners.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void subscribeAll(MarketDataListener listener) {
        globalListeners.add(listener);
    }

    /** Keeps the given portfolio's marks synchronized with incoming ticks. */
    public void monitor(Portfolio portfolio) {
        monitoredPortfolios.add(portfolio);
    }

    public Double latestPrice(String symbol) {
        return latestPrices.get(symbol);
    }

    public long processedCount() {
        return processed.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    private void consumeLoop() {
        while (true) {
            MarketDataEvent e = buffer.poll();
            if (e == null) {
                if (!running) {
                    return;         // stopped and drained
                }
                LockSupport.parkNanos(1_000);
                continue;
            }
            latestPrices.put(e.symbol(), e.price());
            for (Portfolio p : monitoredPortfolios) {
                p.updatePrice(e.symbol(), e.price());
            }
            List<MarketDataListener> ls = symbolListeners.get(e.symbol());
            if (ls != null) {
                for (MarketDataListener l : ls) {
                    l.onEvent(e);
                }
            }
            for (MarketDataListener l : globalListeners) {
                l.onEvent(e);
            }
            processed.incrementAndGet();
        }
    }
}

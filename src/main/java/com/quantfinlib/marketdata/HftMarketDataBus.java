package com.quantfinlib.marketdata;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Ultra-low-latency market data bus. The hot path — {@link #publish} through
 * {@link TickListener#onTick} — performs <b>zero allocation, zero locking, and
 * zero map lookups</b>:
 *
 * <ul>
 *   <li>Ticks travel through a preallocated primitive {@link TickRingBuffer}.</li>
 *   <li>Symbols are dense int ids ({@link SymbolRegistry}); listener dispatch
 *       and the last-price cache are plain array indexing.</li>
 *   <li>The consumer thread can busy-spin ({@link Thread#onSpinWait()}) for
 *       minimum hand-off latency, or park when latency matters less than CPU.</li>
 * </ul>
 *
 * <p>Setup calls ({@code registerSymbol}, {@code subscribe}) are not hot-path
 * and may allocate. For the convenience object-based API (String symbols,
 * multiple producers) see {@link MarketDataProcessor}; this bus is the
 * single-producer HFT path.</p>
 *
 * <p><b>Dispatch cost note:</b> each subscribed listener is an interface
 * call; with three or more distinct {@link TickListener} implementations on
 * one symbol the call site goes megamorphic (~10-20&nbsp;ns per listener,
 * no inlining). Keep the per-symbol listener count at one or two and fan
 * out inside your own listener when you need more consumers.</p>
 */
public final class HftMarketDataBus implements AutoCloseable {

    private static final TickListener[] NO_LISTENERS = new TickListener[0];

    private final SymbolRegistry registry = new SymbolRegistry();
    private final TickRingBuffer ring;
    private final int maxSymbols;
    private final boolean busySpin;
    private final AtomicLongArray latestPriceBits;
    private final AtomicReferenceArray<TickListener[]> symbolListeners;
    private volatile TickListener[] globalListeners = NO_LISTENERS;
    private final TickListener dispatcher = this::dispatch;

    private volatile boolean running;
    // Consumer-thread counter in its own object (no line sharing with the
    // producer-written ringFull) and updated once per DRAIN BATCH with a
    // release store — a per-tick volatile store would pay a StoreLoad
    // fence on every dispatch for an observability-only number.
    private final java.util.concurrent.atomic.AtomicLong processed =
            new java.util.concurrent.atomic.AtomicLong();
    private long ringFull;             // single-writer (producer thread)
    private Thread consumer;

    public HftMarketDataBus(int ringCapacity, int maxSymbols, boolean busySpin) {
        this.ring = new TickRingBuffer(ringCapacity);
        this.maxSymbols = maxSymbols;
        this.busySpin = busySpin;
        this.latestPriceBits = new AtomicLongArray(maxSymbols);
        this.symbolListeners = new AtomicReferenceArray<>(maxSymbols);
        long nan = Double.doubleToRawLongBits(Double.NaN);
        for (int i = 0; i < maxSymbols; i++) {
            latestPriceBits.set(i, nan);
        }
    }

    /** Parked (non-spinning) consumer with a 64K ring and up to 1024 symbols. */
    public HftMarketDataBus() {
        this(1 << 16, 1024, false);
    }

    // ------------------------------------------------------------------
    // Setup (cold path)
    // ------------------------------------------------------------------

    /** Registers a symbol and returns the dense id used on the hot path. */
    public int registerSymbol(String symbol) {
        int id = registry.register(symbol);
        if (id >= maxSymbols) {
            throw new IllegalStateException("maxSymbols (" + maxSymbols + ") exceeded");
        }
        return id;
    }

    public int symbolId(String symbol) {
        return registry.id(symbol);
    }

    public String symbol(int id) {
        return registry.symbol(id);
    }

    public synchronized void subscribe(int symbolId, TickListener listener) {
        TickListener[] cur = symbolListeners.get(symbolId);
        TickListener[] next = cur == null ? new TickListener[1] : Arrays.copyOf(cur, cur.length + 1);
        next[next.length - 1] = listener;
        symbolListeners.set(symbolId, next);
    }

    public synchronized void subscribeAll(TickListener listener) {
        TickListener[] next = Arrays.copyOf(globalListeners, globalListeners.length + 1);
        next[next.length - 1] = listener;
        globalListeners = next;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        consumer = new Thread(this::consumeLoop, "hft-market-data-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    /** Stops the consumer after draining queued ticks. */
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

    // ------------------------------------------------------------------
    // Hot path
    // ------------------------------------------------------------------

    /**
     * Publishes a tick; single producer thread, zero allocation. Returns false
     * when the ring is full (caller decides: spin, drop, or shed load).
     */
    public boolean publish(int symbolId, double price, double size, long timestampNanos) {
        boolean ok = ring.publish(symbolId, price, size, timestampNanos);
        if (!ok) {
            ringFull++;
        }
        return ok;
    }

    private void dispatch(int symbolId, double price, double size, long timestampNanos) {
        latestPriceBits.lazySet(symbolId, Double.doubleToRawLongBits(price));
        TickListener[] ls = symbolListeners.get(symbolId);
        if (ls != null) {
            for (TickListener l : ls) {
                l.onTick(symbolId, price, size, timestampNanos);
            }
        }
        TickListener[] gs = globalListeners;
        for (TickListener l : gs) {
            l.onTick(symbolId, price, size, timestampNanos);
        }
    }

    private void consumeLoop() {
        while (true) {
            int n = ring.drainTo(dispatcher, 1024);
            if (n > 0) {
                processed.setRelease(processed.getPlain() + n);
            } else {
                if (!running && ring.isEmpty()) {
                    return;
                }
                if (busySpin) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(200);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Observability
    // ------------------------------------------------------------------

    /** Last traded price by symbol id; NaN before the first tick. */
    public double latestPrice(int symbolId) {
        return Double.longBitsToDouble(latestPriceBits.get(symbolId));
    }

    public double latestPrice(String symbol) {
        return latestPrice(registry.id(symbol));
    }

    public long processedCount() {
        return processed.get();
    }

    /**
     * Failed publish attempts due to a full ring (backpressure events). A
     * retrying producer increments this per attempt; it does not by itself
     * mean ticks were lost — that is the caller's backpressure policy.
     */
    public long ringFullCount() {
        return ringFull;
    }
}

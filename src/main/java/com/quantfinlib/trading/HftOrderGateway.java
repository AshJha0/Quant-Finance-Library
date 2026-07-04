package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/**
 * Ultra-low-latency order gateway — the fast lane from signal to venue. The
 * hot path ({@link #submit}) performs <b>zero allocation, zero locking, zero
 * map lookups</b>: an inline {@link HftRiskGate} check over primitive arrays,
 * then a release-store publish into a preallocated {@link OrderRingBuffer}.
 * A dedicated venue thread (busy-spin optional) drains orders to the
 * registered {@link OrderListener}s — the venue adapter (exchange session,
 * simulator, ...) lives on that side.
 *
 * <p>Return convention of {@link #submit}: positive = accepted order id;
 * negative = risk-rejected, with {@code -returnValue} the
 * {@link HftRiskGate} reason code; {@code 0} = ring full (backpressure — the
 * caller chooses spin, drop, or kill-switch).</p>
 *
 * <p>Single producer (the trading thread), single consumer (the venue
 * thread) — the same threading model as {@code HftMarketDataBus}.</p>
 */
public final class HftOrderGateway implements AutoCloseable {

    private static final OrderListener[] NO_LISTENERS = new OrderListener[0];

    private final OrderRingBuffer ring;
    private final HftRiskGate riskGate;
    private final boolean busySpin;
    private volatile OrderListener[] listeners = NO_LISTENERS;
    private final OrderListener dispatcher = this::dispatch;

    private volatile boolean running;
    private volatile long delivered;    // single-writer (venue thread)
    private long submitted;             // single-writer (trading thread)
    private long ringFull;              // single-writer (trading thread)
    private long nextId = 1;            // single-writer (trading thread)
    private Thread venueThread;

    public HftOrderGateway(int ringCapacity, HftRiskGate riskGate, boolean busySpin) {
        this.ring = new OrderRingBuffer(ringCapacity);
        this.riskGate = riskGate;
        this.busySpin = busySpin;
    }

    /** Parked venue thread, 8K ring. */
    public HftOrderGateway(HftRiskGate riskGate) {
        this(1 << 13, riskGate, false);
    }

    // ------------------------------------------------------------------
    // Setup (cold path)
    // ------------------------------------------------------------------

    public synchronized void addOrderListener(OrderListener listener) {
        OrderListener[] next = Arrays.copyOf(listeners, listeners.length + 1);
        next[next.length - 1] = listener;
        listeners = next;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        venueThread = new Thread(this::venueLoop, "hft-order-venue");
        venueThread.setDaemon(true);
        venueThread.start();
    }

    /** Stops the venue thread after draining queued orders. */
    public synchronized void stop() {
        running = false;
        if (venueThread != null) {
            try {
                venueThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            venueThread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------
    // Hot path (trading thread)
    // ------------------------------------------------------------------

    /**
     * Risk-checks and submits one order. Zero allocation.
     *
     * @return positive order id if accepted; negative {@link HftRiskGate}
     *         reason code if rejected; 0 if the ring is full
     */
    public long submit(int symbolId, Side side, long quantity, double price, long timestampNanos) {
        int code = riskGate.check(symbolId, side, quantity, price);
        if (code != HftRiskGate.OK) {
            return -code;
        }
        long id = nextId;
        if (!ring.publish(id, symbolId, side, quantity, price, timestampNanos)) {
            ringFull++;
            return 0;
        }
        nextId = id + 1;
        submitted++;
        return id;
    }

    private void dispatch(long orderId, int symbolId, Side side, long quantity,
                          double price, long timestampNanos) {
        OrderListener[] ls = listeners;
        for (OrderListener l : ls) {
            l.onOrder(orderId, symbolId, side, quantity, price, timestampNanos);
        }
        delivered = delivered + 1;
    }

    private void venueLoop() {
        while (true) {
            int n = ring.drainTo(dispatcher, 256);
            if (n == 0) {
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

    public HftRiskGate riskGate() {
        return riskGate;
    }

    public long submittedCount() {
        return submitted;
    }

    /** Orders handed to the venue thread so far. */
    public long deliveredCount() {
        return delivered;
    }

    public long ringFullCount() {
        return ringFull;
    }
}

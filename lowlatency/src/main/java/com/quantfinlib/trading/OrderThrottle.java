package com.quantfinlib.trading;

/**
 * Order-rate throttle: a nanosecond token bucket for exchange message-rate
 * limits (every real venue enforces one; exceeding it earns disconnects or
 * fines, so the gateway must self-limit). Sustained rate {@code ratePerSec}
 * with bursts up to {@code burst} — a quiet spell banks up to one burst of
 * headroom, then the bucket refills continuously.
 *
 * <p>Single-writer (call from the order-entry thread), zero allocation,
 * no clock reads of its own — the caller passes {@code System.nanoTime()}
 * so tests are deterministic and the hot path controls its syscalls.</p>
 */
public final class OrderThrottle {

    private final double tokensPerNano;
    private final double burst;
    private double tokens;
    private long lastNanos;
    private long acquired;
    private long throttled;

    /**
     * @param ratePerSec sustained messages per second (> 0)
     * @param burst      bucket depth: messages allowed back-to-back (≥ 1)
     */
    public OrderThrottle(double ratePerSec, int burst) {
        if (ratePerSec <= 0 || burst < 1) {
            throw new IllegalArgumentException("need ratePerSec > 0, burst >= 1");
        }
        this.tokensPerNano = ratePerSec / 1e9;
        this.burst = burst;
        this.tokens = burst;                     // start full: allow an opening burst
        this.lastNanos = Long.MIN_VALUE;
    }

    /**
     * Attempts to take one send permit at {@code nowNanos}. False = do not
     * send (queue or drop per your policy — this class only counts).
     */
    public boolean tryAcquire(long nowNanos) {
        refill(nowNanos);
        // The 1e-9 slack absorbs floating-point drift from split refills
        // (a+b in two steps can sum a hair under one step): it grants at
        // most sub-nanosecond-early permits, never extra ones.
        if (tokens >= 1 - 1e-9) {
            tokens = Math.max(0, tokens - 1);
            acquired++;
            return true;
        }
        throttled++;
        return false;
    }

    /**
     * Nanoseconds until a permit would be available (0 when one already
     * is) — for pacing loops that would rather sleep than spin-fail.
     */
    public long nanosUntilAvailable(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / tokensPerNano);
    }

    private void refill(long nowNanos) {
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = nowNanos;
            return;
        }
        long dt = nowNanos - lastNanos;
        if (dt > 0) {
            tokens = Math.min(burst, tokens + dt * tokensPerNano);
            lastNanos = nowNanos;
        }
    }

    /** Permits granted so far. */
    public long acquiredCount() {
        return acquired;
    }

    /** Denials so far — a persistent nonzero rate means the strategy outruns the venue limit. */
    public long throttledCount() {
        return throttled;
    }
}

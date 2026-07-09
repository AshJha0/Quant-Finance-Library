package com.quantfinlib.microstructure;

import com.quantfinlib.util.MathUtils;

/**
 * Self-exciting (Hawkes) event intensity — the model behind the trader's
 * observation that "activity breeds activity": one trade raises the
 * probability of the next, so order flow arrives in bursts, not as a
 * steady Poisson drizzle. The exponential Hawkes form keeps the whole
 * process in two numbers:
 *
 * <pre>  λ(t) = μ + S(t),   S(t) = Σ over past events of α·e^(−β(t−tᵢ))</pre>
 *
 * where μ is the baseline arrival rate, α the excitation each event adds,
 * and β the decay. {@code S} updates in O(1) per event — decay what is
 * there, add α — which is what makes Hawkes streaming-friendly while
 * richer clustering models are not.
 *
 * <p><b>Stability is enforced, not assumed:</b> the branching ratio
 * {@code α/β} is the expected number of children each event spawns; at
 * ≥ 1 the process is explosive (each event begets more than one, and the
 * intensity diverges), so the constructor rejects it. Read
 * {@link #burstScore} as the regime signal: 0 = baseline flow, 1 = the
 * self-excited component equals the baseline (activity running 2×), and
 * it decays back with the configured half-life when the burst ends.</p>
 *
 * <p>Uses: pre-positioning for activity bursts (a burst means queues
 * consume faster — {@code QueueModel} horizons shorten), conflation
 * pressure forecasting, and as a dimensionless ensemble input. Feed every
 * arrival of the event type you care about (trades, quotes, or your own
 * fills) via {@link #onEvent}; timestamps must be non-decreasing (a
 * backwards timestamp is ignored — feed-merge jitter must not inject
 * negative decay). Zero allocation per event, single writer, one
 * instance per symbol per event type.</p>
 */
public final class HawkesIntensity {

    private final double baselineRatePerSec;
    private final double excitation;       // α: intensity added per event (per sec)
    private final long decayHalfLifeNanos;

    private double excited;                // S(t) as of lastEventNanos
    private long lastEventNanos;
    private boolean hasEvent;
    private long events;

    /**
     * @param baselineRatePerSec  μ — arrival rate with no excitation, e.g. 2.0
     * @param excitation          α — intensity each event adds (per second),
     *                            e.g. 0.1
     * @param decayHalfLifeNanos  how fast excitation fades, e.g. 2s. Stability
     *                            requires branching ratio α/β &lt; 1 where
     *                            β = ln2/halfLife (per second): at a 2s
     *                            half-life, β ≈ 0.35/s, so α must stay
     *                            below ~0.35 — rejected otherwise.
     */
    public HawkesIntensity(double baselineRatePerSec, double excitation,
                           long decayHalfLifeNanos) {
        if (baselineRatePerSec <= 0 || excitation < 0 || decayHalfLifeNanos <= 0) {
            throw new IllegalArgumentException(
                    "need baselineRate > 0, excitation >= 0, halfLife > 0");
        }
        double beta = Math.log(2) / (decayHalfLifeNanos * 1e-9);
        if (excitation / beta >= 1) {
            throw new IllegalArgumentException("explosive: branching ratio "
                    + (excitation / beta) + " >= 1 (each event spawns >= 1 child)");
        }
        this.baselineRatePerSec = baselineRatePerSec;
        this.excitation = excitation;
        this.decayHalfLifeNanos = decayHalfLifeNanos;
    }

    /** Baseline 2 events/s, excitation 0.1/s per event, 2s half-life (branching ~0.29). */
    public HawkesIntensity() {
        this(2.0, 0.1, 2_000_000_000L);
    }

    /**
     * One event arrival. Timestamps must be non-decreasing; an
     * out-of-order timestamp is dropped (negative decay would GROW past
     * excitation instead of fading it).
     */
    public void onEvent(long timestampNanos) {
        if (hasEvent) {
            long dt = timestampNanos - lastEventNanos;
            if (dt < 0) {
                return;
            }
            excited = excited * MathUtils.decayFactor(dt, decayHalfLifeNanos) + excitation;
        } else {
            excited = excitation;
            hasEvent = true;
        }
        lastEventNanos = timestampNanos;
        events++;
    }

    /** The current intensity λ(now) in events per second. */
    public double intensity(long nowNanos) {
        return baselineRatePerSec + excitedAt(nowNanos);
    }

    /**
     * The dimensionless burst regime: self-excited intensity over the
     * baseline, clamped to [0, 1] at "activity running 2× baseline".
     * 0 in steady flow; decays back with the configured half-life.
     */
    public double burstScore(long nowNanos) {
        return MathUtils.clamp(excitedAt(nowNanos) / baselineRatePerSec, 0, 1);
    }

    private double excitedAt(long nowNanos) {
        if (!hasEvent) {
            return 0;
        }
        long dt = nowNanos - lastEventNanos;
        return dt <= 0 ? excited : excited * MathUtils.decayFactor(dt, decayHalfLifeNanos);
    }

    public long events() {
        return events;
    }
}

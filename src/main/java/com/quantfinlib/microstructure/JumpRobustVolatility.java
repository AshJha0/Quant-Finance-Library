package com.quantfinlib.microstructure;

import com.quantfinlib.util.MathUtils;

/**
 * Jump-robust streaming volatility. A squared-return estimator (the one
 * inside {@code SignalEngine}) cannot tell a news gap from diffusion: one
 * headline print enters as r² and reads as a volatility regime shift for
 * the estimator's whole memory. Bipower variation (Barndorff-Nielsen &
 * Shephard, 2004) fixes this with a beautifully simple trick: use the
 * product of CONSECUTIVE absolute returns, {@code (π/2)·|rₜ|·|rₜ₋₁|},
 * instead of r². Diffusion moves both factors together, so the product
 * estimates the same σ²; a single jump inflates only ONE factor of two
 * neighboring products instead of one whole squared term — its weight in
 * the estimate collapses.
 *
 * <p>Both estimators run side by side on time-decayed rates per second:
 * {@link #volPerSqrtSecond} is the jump-robust (bipower) volatility — the
 * one to feed {@link VolatilityCurve} and any model that should read
 * regimes, not headlines — while {@link #rawVolPerSqrtSecond} is the
 * squared-return volatility, and {@link #jumpFraction} is the share of
 * raw variance the robust estimator attributes to jumps
 * ({@code 1 − BV/RV}, clamped to [0,1]).</p>
 *
 * <p>Gap discipline: a non-finite return or non-positive Δt drops the
 * sample AND resets the consecutive-return pairing — multiplying across a
 * feed gap would pair returns that were never neighbors. The first return
 * after a gap therefore updates only the raw estimator; the bipower leg
 * resumes one sample later. Irregular sampling is handled exactly: the
 * two-return product is normalized by {@code √(Δtₜ·Δtₜ₋₁)} (each |r|
 * scales with √ of ITS OWN interval), so event-time feeds — where
 * activity accelerates precisely when volatility bursts — do not bias
 * the estimator. Zero allocation per event, single writer, one instance
 * per symbol.</p>
 */
public final class JumpRobustVolatility {

    private static final double HALF_PI = Math.PI / 2;

    private final long halfLifeNanos;

    private double rawRatePerSec;          // decayed E[r²/dt]
    private double bipowerRatePerSec;      // decayed E[(π/2)|r||r₋₁|/√(dt·dt₋₁)]
    private double prevAbsReturn;
    private double prevDtSec;
    private boolean hasPrev;
    private boolean bipowerSeeded;
    private long samples;

    /** @param halfLifeNanos decay half-life, e.g. 10s = {@code 10_000_000_000L} */
    public JumpRobustVolatility(long halfLifeNanos) {
        if (halfLifeNanos <= 0) {
            throw new IllegalArgumentException("halfLifeNanos must be positive");
        }
        this.halfLifeNanos = halfLifeNanos;
    }

    /** 10-second half-life. */
    public JumpRobustVolatility() {
        this(10_000_000_000L);
    }

    /**
     * One return observation: the relative mid change over the elapsed
     * {@code dtNanos}. Non-finite returns or non-positive gaps drop the
     * sample and break the pairing (see class doc).
     */
    public void onReturn(double ret, long dtNanos) {
        if (!Double.isFinite(ret) || dtNanos <= 0) {
            hasPrev = false;               // gap: never pair across it
            return;
        }
        double dtSec = dtNanos * 1e-9;
        double a = 1 - MathUtils.decayFactor(dtNanos, halfLifeNanos);

        double rawObs = ret * ret / dtSec;
        rawRatePerSec = samples == 0 ? rawObs : rawRatePerSec + a * (rawObs - rawRatePerSec);

        double absRet = Math.abs(ret);
        if (hasPrev) {
            // Each |r| carries √ of its OWN interval, so the product is
            // normalized by the geometric mean of the two — exact under
            // irregular sampling, where dt-of-the-moment normalization
            // would read a cadence change as a volatility change.
            double bpObs = HALF_PI * absRet * prevAbsReturn / Math.sqrt(dtSec * prevDtSec);
            bipowerRatePerSec = bipowerSeeded
                    ? bipowerRatePerSec + a * (bpObs - bipowerRatePerSec)
                    : bpObs;
            bipowerSeeded = true;
        }
        prevAbsReturn = absRet;
        prevDtSec = dtSec;
        hasPrev = true;
        samples++;
    }

    /**
     * The jump-robust volatility, as return per √second — the diffusion
     * component, with jumps down-weighted. 0 until two consecutive valid
     * returns exist.
     */
    public double volPerSqrtSecond() {
        return Math.sqrt(Math.max(bipowerRatePerSec, 0));
    }

    /** The plain squared-return volatility (jumps and all), per √second. */
    public double rawVolPerSqrtSecond() {
        return Math.sqrt(Math.max(rawRatePerSec, 0));
    }

    /**
     * The share of raw variance attributed to jumps:
     * {@code clamp(1 − bipower/raw, 0, 1)}. Near 0 in pure diffusion,
     * spikes after a discontinuous move, and decays back as the jump
     * washes out of the raw estimator's memory. 0 while either estimator
     * is unlearned.
     */
    public double jumpFraction() {
        if (rawRatePerSec <= 0 || !bipowerSeeded) {
            return 0;
        }
        return MathUtils.clamp(1 - bipowerRatePerSec / rawRatePerSec, 0, 1);
    }

    public long samples() {
        return samples;
    }
}

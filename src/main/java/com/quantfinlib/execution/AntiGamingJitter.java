package com.quantfinlib.execution;

import java.util.Random;

/**
 * Anti-gaming randomization for schedule-driven algos — a TWAP that
 * fires identical children on a metronome is a gift to anyone watching
 * the tape: predators detect the clock in a handful of intervals and
 * lean on every child. The counter-measure is controlled jitter that
 * keeps the schedule on track while removing the pattern:
 *
 * <ul>
 *   <li><b>Size jitter</b> — each child ±{@code sizeFraction}, with the
 *       differences redistributed so the total is preserved exactly
 *       (the parent must complete; anti-gaming never changes what gets
 *       done, only how recognizable it looks);</li>
 *   <li><b>Time jitter</b> — each firing time ±{@code timeFraction} of
 *       its own interval, monotonicity preserved (children never
 *       reorder) and end time never exceeded.</li>
 * </ul>
 *
 * <p>Deterministic per seed: replayable in backtests, auditable in
 * production (compliance can reconstruct exactly why each child fired
 * when it did). Research/warm lane. Relationship to
 * {@link TwapScheduler#scheduleRandomized}: that method jitters TWAP
 * slice sizes at construction. This class is the generic overlay; it
 * jitters any existing child plan (VWAP, benchmark-executor,
 * hand-built) and adds the time dimension the schedulers do not
 * randomize.</p>
 */
public final class AntiGamingJitter {

    private final Random rnd;
    private final double sizeFraction;
    private final double timeFraction;

    /**
     * @param seed         deterministic seed (replayable, auditable)
     * @param sizeFraction max relative size perturbation, in [0, 0.5]
     * @param timeFraction max relative time perturbation within each
     *                     interval, in [0, 0.5]
     */
    public AntiGamingJitter(long seed, double sizeFraction, double timeFraction) {
        if (!(sizeFraction >= 0 && sizeFraction <= 0.5)) {
            throw new IllegalArgumentException("sizeFraction must be in [0, 0.5]");
        }
        if (!(timeFraction >= 0 && timeFraction <= 0.5)) {
            throw new IllegalArgumentException("timeFraction must be in [0, 0.5]");
        }
        this.rnd = new Random(seed);
        this.sizeFraction = sizeFraction;
        this.timeFraction = timeFraction;
    }

    /**
     * Jitters child sizes ±{@code sizeFraction}, preserving the total
     * exactly and never producing a negative child. Perturbations are
     * paired (child i gives what child i+1 takes), so the completion
     * curve wanders inside a one-child envelope of the original.
     */
    public long[] jitterSizes(long[] childQty) {
        long[] out = childQty.clone();
        for (long q : out) {
            if (q < 0) {
                throw new IllegalArgumentException("child quantities must be >= 0");
            }
        }
        for (int i = 0; i + 1 < out.length; i++) {
            // Transfer between neighbors: total invariant by construction.
            long maxShift = (long) Math.floor(Math.min(out[i], out[i + 1]) * sizeFraction);
            if (maxShift <= 0) {
                continue;
            }
            long shift = Math.round((2 * rnd.nextDouble() - 1) * maxShift);
            out[i] += shift;
            out[i + 1] -= shift;
        }
        return out;
    }

    /**
     * Jitters firing times within their intervals. Each time moves back by at
     * most {@code timeFraction} of the gap to its original predecessor (or to
     * {@code startNanos}) and forward by at most {@code timeFraction} of the
     * gap to its original successor; the last time never moves forward. Both
     * bounds come from the original schedule only, and each is also held
     * below half its gap, so two neighbours can never meet: consecutive
     * outputs stay at least {@code g (1 - 2 timeFraction) - 1} nanoseconds
     * apart, where {@code g} is their original gap. Strictly increasing,
     * first time not before {@code startNanos}, last not after the original
     * end.
     */
    public long[] jitterTimes(long[] timesNanos, long startNanos) {
        long[] out = timesNanos.clone();
        for (int i = 1; i < out.length; i++) {
            if (out[i] <= out[i - 1]) {
                throw new IllegalArgumentException("times must be strictly increasing");
            }
        }
        if (out.length > 0 && out[0] < startNanos) {
            throw new IllegalArgumentException("first time is before start");
        }
        for (int i = 0; i < out.length; i++) {
            long gapBefore = timesNanos[i] - (i == 0 ? startNanos : timesNanos[i - 1]);
            long back = (long) Math.floor(gapBefore * timeFraction);
            if (i > 0) {
                back = Math.min(back, (gapBefore - 1) / 2);
            }
            long forward = 0;
            if (i + 1 < out.length) {
                long gapAfter = timesNanos[i + 1] - timesNanos[i];
                forward = Math.min((long) Math.floor(gapAfter * timeFraction), (gapAfter - 1) / 2);
            }
            double u = 2 * rnd.nextDouble() - 1;
            out[i] = timesNanos[i] + Math.round(u * (u >= 0 ? forward : back));
        }
        return out;
    }
}

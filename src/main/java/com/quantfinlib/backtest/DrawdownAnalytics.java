package com.quantfinlib.backtest;

import java.util.ArrayList;
import java.util.List;

/**
 * DRAWDOWN structure — because "max drawdown 18%" hides the number that
 * actually fires clients: how LONG the pain lasted. A strategy that loses
 * 18% and recovers in three weeks and one that spends two years under
 * water have the same max drawdown and completely different survival
 * odds. Redemptions, risk-committee reviews and career risk are all
 * functions of drawdown DURATION, not just depth.
 *
 * <p>The walk: track the running peak; a drawdown episode opens the first
 * period equity dips below it and closes when equity regains the peak
 * (recovery) or the series ends (still open — {@code recoveryIndex = -1},
 * a fact worth surfacing, not hiding: an open drawdown at the end of a
 * backtest is often the honest state of the strategy today).</p>
 *
 * <ul>
 *   <li><b>depth</b> — 1 − trough/peak per episode;</li>
 *   <li><b>duration</b> — periods from the peak to recovery (or to the
 *       last bar for an open episode);</li>
 *   <li><b>time under water</b> — the fraction of ALL periods spent below
 *       the running peak. A strategy under water 60% of the time is
 *       painful to hold even when each individual dip is shallow;</li>
 *   <li><b>episodes</b> — the full chronological list, so callers can
 *       take the top-k by depth, histogram durations, or line episodes up
 *       against market events.</li>
 * </ul>
 *
 * <p>Complements {@link com.quantfinlib.risk.RiskMetrics#maxDrawdown},
 * which returns only the max depth; the two agree exactly on it (tested).
 * Equity must be positive throughout — a ratio-of-peak drawdown is
 * meaningless through zero or negative equity. Static, deterministic,
 * research lane.</p>
 */
public final class DrawdownAnalytics {

    /**
     * One peak-to-recovery episode. {@code recoveryIndex} is {@code -1}
     * while the drawdown is still open at series end; duration then runs
     * to the last bar.
     */
    public record Drawdown(int peakIndex, int troughIndex, int recoveryIndex, double depth) {

        /** Periods from peak to recovery, or to series end if open. */
        public int duration(int seriesLength) {
            return (recoveryIndex >= 0 ? recoveryIndex : seriesLength - 1) - peakIndex;
        }
    }

    /**
     * @param maxDepth       deepest episode's depth (0 if equity never dips)
     * @param maxDuration    longest episode duration in periods
     * @param timeUnderWater fraction of periods below the running peak
     * @param episodes       chronological drawdown episodes
     */
    public record Result(double maxDepth, int maxDuration, double timeUnderWater,
                         List<Drawdown> episodes) {
    }

    private DrawdownAnalytics() {
    }

    /** @param equity equity curve, &ge; 2 points, all finite and &gt; 0 */
    public static Result analyze(double[] equity) {
        int n = equity.length;
        if (n < 2) {
            throw new IllegalArgumentException("need >= 2 equity points, got " + n);
        }
        for (double e : equity) {
            if (!(e > 0) || e == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("equity must be finite and > 0, got " + e);
            }
        }
        List<Drawdown> episodes = new ArrayList<>();
        double peak = equity[0];
        int peakIdx = 0;
        int underWater = 0;
        boolean open = false;
        double trough = 0;
        int troughIdx = -1;

        for (int i = 1; i < n; i++) {
            if (equity[i] >= peak) {
                if (open) {
                    episodes.add(new Drawdown(peakIdx, troughIdx, i, 1 - trough / peak));
                    open = false;
                }
                peak = equity[i];
                peakIdx = i;
            } else {
                underWater++;
                if (!open) {
                    open = true;
                    trough = equity[i];
                    troughIdx = i;
                } else if (equity[i] < trough) {
                    trough = equity[i];
                    troughIdx = i;
                }
            }
        }
        if (open) {
            episodes.add(new Drawdown(peakIdx, troughIdx, -1, 1 - trough / peak));
        }

        double maxDepth = 0;
        int maxDuration = 0;
        for (Drawdown d : episodes) {
            maxDepth = Math.max(maxDepth, d.depth());
            maxDuration = Math.max(maxDuration, d.duration(n));
        }
        return new Result(maxDepth, maxDuration, (double) underWater / n, List.copyOf(episodes));
    }
}

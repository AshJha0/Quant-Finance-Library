package com.quantfinlib.regulatory;

import java.util.Arrays;

/**
 * WM/Reuters-style 4pm fix analysis: computes the fix rate from mid samples
 * inside the fixing window (median, per WM/R methodology) and screens a
 * participant's flow for the classic "banging the close" signature — a large
 * share of window volume, a price run-up aligned with the participant's net
 * flow into the fix, and reversion afterwards.
 */
public final class FixAnalyzer {

    public record FixImpactReport(
            double fixRate,
            double runUpBps,           // pre-window mid -> fix
            double reversionBps,       // fix -> post-window mid
            double participationShare, // participant volume / market volume in the window
            long netFlow,              // participant buys - sells
            boolean flagged) {
    }

    private FixAnalyzer() {
    }

    /** Fix rate = median of the mid samples captured inside the fixing window. */
    public static double calculateFix(double[] midSamplesInWindow) {
        if (midSamplesInWindow.length == 0) {
            throw new IllegalArgumentException("no samples");
        }
        double[] sorted = midSamplesInWindow.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /**
     * Screens one participant's fixing-window activity.
     *
     * <p>Flags when all three hold: participation share ≥ threshold, the
     * run-up into the fix is aligned with the participant's net flow, and the
     * price reverts against that flow after the window (impact that decays is
     * the footprint of pressure, not information).</p>
     *
     * @param midSamplesInWindow mid samples inside the fixing window
     * @param preWindowMid       mid just before the window opens
     * @param postWindowMid      mid after the window closes
     * @param participantBuyQty  participant buy volume in the window
     * @param participantSellQty participant sell volume in the window
     * @param marketVolume       total market volume in the window
     * @param shareThreshold     participation share that triggers scrutiny (e.g. 0.25)
     */
    public static FixImpactReport analyze(double[] midSamplesInWindow,
                                          double preWindowMid, double postWindowMid,
                                          long participantBuyQty, long participantSellQty,
                                          long marketVolume, double shareThreshold) {
        double fix = calculateFix(midSamplesInWindow);
        double runUpBps = (fix - preWindowMid) / preWindowMid * 1e4;
        double reversionBps = (postWindowMid - fix) / fix * 1e4;
        long netFlow = participantBuyQty - participantSellQty;
        double share = marketVolume <= 0 ? 0
                : (participantBuyQty + participantSellQty) / (double) marketVolume;

        int flowSign = Long.signum(netFlow);
        boolean alignedRunUp = flowSign != 0 && flowSign * runUpBps > 0;
        boolean revertsAfter = flowSign != 0 && flowSign * reversionBps < 0;
        boolean flagged = share >= shareThreshold && alignedRunUp && revertsAfter;

        return new FixImpactReport(fix, runUpBps, reversionBps, share, netFlow, flagged);
    }
}

package com.quantfinlib.fx;

/**
 * Analytics for benchmark-fixing exposure — the risk concentrated in the
 * short calculation window of an official fix (WM/R 4pm London, the RBI
 * reference rate an NDF settles on, an equity closing auction).
 *
 * <p>A book that must trade <em>at</em> the fix (to hedge an NDF settlement
 * or match a benchmarked mandate) cannot execute at a single instant: it
 * works the order through the window and receives roughly the window
 * TWAP/VWAP, while its liability references the fix print. These helpers
 * quantify both sides:</p>
 * <ul>
 *   <li>{@link #windowTwap}/{@link #windowVwap} — what execution across the
 *       window actually achieves;</li>
 *   <li>{@link #slippageVsFix} — realized tracking difference, in pips;</li>
 *   <li>{@link #trackingErrorStd} — the ex-ante 1σ of that difference from
 *       diffusion alone, the number a pre-trade check compares against
 *       limits;</li>
 *   <li>{@link #participationRate} — share of window volume, the standard
 *       impact red flag around fixes.</li>
 * </ul>
 *
 * <p>All methods are static and allocation-free — usable from a pre-trade
 * gate as well as post-trade TCA.</p>
 */
public final class FixingRisk {

    private FixingRisk() {
    }

    /** Time-weighted average of window prices (equally spaced observations). */
    public static double windowTwap(double[] prices) {
        if (prices.length == 0) {
            throw new IllegalArgumentException("empty window");
        }
        double sum = 0;
        for (double p : prices) {
            sum += p;
        }
        return sum / prices.length;
    }

    /** Volume-weighted average of window prices. */
    public static double windowVwap(double[] prices, double[] sizes) {
        if (prices.length == 0 || prices.length != sizes.length) {
            throw new IllegalArgumentException("prices/sizes must be non-empty and aligned");
        }
        double pv = 0;
        double v = 0;
        for (int i = 0; i < prices.length; i++) {
            pv += prices[i] * sizes[i];
            v += sizes[i];
        }
        if (v <= 0) {
            throw new IllegalArgumentException("window volume must be > 0");
        }
        return pv / v;
    }

    /** Realized slippage of an achieved price vs the fix print, in pips (signed, buy side). */
    public static double slippageVsFix(CurrencyPair pair, double achievedPrice, double fixPrice) {
        return pair.pips(achievedPrice - fixPrice);
    }

    /**
     * Ex-ante 1σ tracking error (price terms) between a fix print and a
     * uniform execution across the window, from diffusion alone.
     *
     * <p>For arithmetic Brownian motion with per-√minute vol σ, the variance
     * of (fix − TWAP) over a window of length T minutes ending at the fix is
     * σ²·T/3 — the classic TWAP-vs-close result. Multiply by notional for a
     * P&amp;L figure.</p>
     *
     * @param volPerSqrtMinute price vol per √minute (e.g. daily vol / √(24·60))
     * @param windowMinutes    fix calculation window length
     */
    public static double trackingErrorStd(double volPerSqrtMinute, double windowMinutes) {
        if (volPerSqrtMinute < 0 || windowMinutes <= 0) {
            throw new IllegalArgumentException("vol must be >= 0 and window > 0");
        }
        return volPerSqrtMinute * Math.sqrt(windowMinutes / 3.0);
    }

    /**
     * Order size as a fraction of expected window volume — above ~20% the
     * order moves the fix it is trying to match, and impact (not tracking
     * noise) dominates.
     */
    public static double participationRate(double orderQty, double expectedWindowVolume) {
        if (expectedWindowVolume <= 0) {
            throw new IllegalArgumentException("expected window volume must be > 0");
        }
        return Math.abs(orderQty) / expectedWindowVolume;
    }
}

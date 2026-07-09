package com.quantfinlib.risk;

/**
 * FRTB P&amp;L attribution test (PLAT) — the exam a risk MODEL must pass
 * to keep internal-model approval: does the risk engine's theoretical
 * P&amp;L (RTPL — what the model's factors and pricers say the desk made)
 * actually track the desk's hypothetical P&amp;L (HPL — what revaluing
 * the real book on real prices says)? Two statistics, per MAR32:
 *
 * <ul>
 *   <li><b>Spearman correlation</b> between daily HPL and RTPL — do
 *       they RANK days the same way;</li>
 *   <li><b>Kolmogorov-Smirnov</b> statistic between their empirical
 *       distributions — do they have the same SHAPE.</li>
 * </ul>
 *
 * Zones per the regulation: GREEN (corr &gt; 0.80 and KS &lt; 0.09),
 * RED (corr &lt; 0.70 or KS &gt; 0.12), AMBER between — amber adds a
 * capital surcharge, red kicks the desk to the standardized approach.
 * Failing PLAT usually means missing risk factors: the model prices
 * with fewer factors than move the real book. Styled after BCBS MAR32,
 * not certified (same stance as {@link FrtbEs}). Research lane.
 */
public final class PnlAttribution {

    /** The PLAT verdict for one desk over one window. */
    public record Result(double spearmanCorrelation, double ksStatistic, Zone zone) {
    }

    public enum Zone {
        GREEN, AMBER, RED
    }

    private PnlAttribution() {
    }

    /**
     * Runs the PLAT over aligned daily P&amp;L series (250 days is the
     * regulatory window; anything ≥ 20 computes).
     *
     * @param hypotheticalPnl HPL — actual book, actual prices
     * @param riskTheoreticalPnl RTPL — the risk model's factors + pricers
     */
    public static Result test(double[] hypotheticalPnl, double[] riskTheoreticalPnl) {
        if (hypotheticalPnl.length != riskTheoreticalPnl.length
                || hypotheticalPnl.length < 20) {
            throw new IllegalArgumentException("need aligned series of >= 20 days");
        }
        double corr = Dependence.spearman(hypotheticalPnl, riskTheoreticalPnl);
        double ks = ksStatistic(hypotheticalPnl, riskTheoreticalPnl);
        Zone zone;
        if (corr < 0.70 || ks > 0.12) {
            zone = Zone.RED;
        } else if (corr > 0.80 && ks < 0.09) {
            zone = Zone.GREEN;
        } else {
            zone = Zone.AMBER;
        }
        return new Result(corr, ks, zone);
    }

    /**
     * The two-sample KS statistic: max gap between the empirical CDFs,
     * evaluated after BOTH samples have consumed each distinct value —
     * ties must not register a transient gap (identical series score
     * exactly 0).
     */
    public static double ksStatistic(double[] a, double[] b) {
        if (a.length == 0 || b.length == 0) {
            throw new IllegalArgumentException("need non-empty samples");
        }
        double[] sa = a.clone();
        double[] sb = b.clone();
        java.util.Arrays.sort(sa);
        java.util.Arrays.sort(sb);
        // A NaN would freeze the tie-consuming advance below (NaN == NaN
        // is false — neither index ever moves), turning one missing P&L
        // day into an INFINITE LOOP. Sorted arrays put -Inf first and
        // NaN/+Inf last, so checking the ends rejects every non-finite.
        if (!Double.isFinite(sa[0]) || !Double.isFinite(sa[sa.length - 1])
                || !Double.isFinite(sb[0]) || !Double.isFinite(sb[sb.length - 1])) {
            throw new IllegalArgumentException("P&L series must be finite");
        }
        int i = 0;
        int j = 0;
        double maxGap = 0;
        while (i < sa.length && j < sb.length) {
            double v = Math.min(sa[i], sb[j]);
            while (i < sa.length && sa[i] == v) {
                i++;
            }
            while (j < sb.length && sb[j] == v) {
                j++;
            }
            double gap = Math.abs((double) i / sa.length - (double) j / sb.length);
            if (gap > maxGap) {
                maxGap = gap;
            }
        }
        return maxGap;
    }
}

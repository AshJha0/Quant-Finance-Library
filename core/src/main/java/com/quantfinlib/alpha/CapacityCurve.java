package com.quantfinlib.alpha;

import java.util.Arrays;
import java.util.Locale;

/**
 * Strategy capacity: net Sharpe ratio against book size.
 *
 * <p>The same factor and construction are backtested at each capital level
 * with {@link AlphaBacktester}, so the square-root impact
 * ({@code microstructure.MarketImpactModel}, cost per unit traded growing
 * with {@code √(shares/ADV)}) and the participation cap (unfilled trades
 * carried forward, so a large book tracks its target late) both act as the
 * book grows. Gross Sharpe does not depend on capital without a cap;
 * net Sharpe falls as impact grows, and the capacity at a Sharpe floor is
 * where the curve crosses it (Ratcliffe, Miranda and Ang 2017, "Capacity
 * of Smart Beta Strategies from a Transaction Cost Perspective", J. Index
 * Investing 8(3), use the same construction of cost against AUM).</p>
 */
public final class CapacityCurve {

    private final double[] capitals;
    private final double[] netSharpe;
    private final double[] grossSharpe;
    private final double[] impactDrag;
    private final double[] totalCostDrag;
    private final double[] trackingGap;

    private CapacityCurve(double[] capitals, double[] netSharpe, double[] grossSharpe,
                          double[] impactDrag, double[] totalCostDrag, double[] trackingGap) {
        this.capitals = capitals;
        this.netSharpe = netSharpe;
        this.grossSharpe = grossSharpe;
        this.impactDrag = impactDrag;
        this.totalCostDrag = totalCostDrag;
        this.trackingGap = trackingGap;
    }

    /**
     * Runs the backtest at each capital (ascending, each &gt; 0) with the
     * given construction.
     *
     * @param maxParticipation per-bar fraction of ADV (0 = no cap)
     */
    public static CapacityCurve compute(AlphaContext ctx, AlphaFactor factor,
                                        AlphaBacktester.Config base,
                                        AlphaBacktester.WeightBuilder builder,
                                        double[] capitals, double maxParticipation) {
        return compute(capitals, maxParticipation, base,
                cfg -> AlphaBacktester.run(ctx, factor, cfg, builder));
    }

    /** As {@link #compute(AlphaContext, AlphaFactor, AlphaBacktester.Config,
     * AlphaBacktester.WeightBuilder, double[], double)} with the risk-model construction. */
    public static CapacityCurve compute(AlphaContext ctx, AlphaFactor factor,
                                        AlphaBacktester.Config base,
                                        AlphaBacktester.RiskOptions options,
                                        double[] capitals, double maxParticipation) {
        return compute(capitals, maxParticipation, base,
                cfg -> AlphaBacktester.run(ctx, factor, cfg, options));
    }

    private static CapacityCurve compute(double[] capitals, double maxParticipation,
                                         AlphaBacktester.Config base,
                                         java.util.function.Function<AlphaBacktester.Config,
                                                 AlphaBacktester.Result> runner) {
        if (capitals.length == 0 || !(maxParticipation >= 0)) {
            throw new IllegalArgumentException("need capitals and maxParticipation >= 0");
        }
        for (int i = 0; i < capitals.length; i++) {
            if (!(capitals[i] > 0) || Double.isInfinite(capitals[i])
                    || (i > 0 && capitals[i] <= capitals[i - 1])) {
                throw new IllegalArgumentException("capitals must be positive and ascending");
            }
        }
        int k = capitals.length;
        double[] ns = new double[k];
        double[] gs = new double[k];
        double[] imp = new double[k];
        double[] tc = new double[k];
        double[] gap = new double[k];
        for (int i = 0; i < k; i++) {
            AlphaBacktester.Config c = new AlphaBacktester.Config(base.startIndex(),
                    base.rebalanceEveryBars(), base.commissionBps(), base.halfSpreadBps(),
                    base.slippageBps(), capitals[i], base.impactWindow(), base.periodsPerYear(),
                    base.fillPolicy(), base.borrowBpsPerYear(), base.cashRatePerYear(),
                    base.marginRatePerYear(), maxParticipation);
            AlphaBacktester.Result r = runner.apply(c);
            ns[i] = r.netMetrics().sharpeRatio();
            gs[i] = r.grossMetrics().sharpeRatio();
            imp[i] = r.impactDrag();
            tc[i] = r.totalCostDrag();
            gap[i] = r.meanTrackingGap();
        }
        return new CapacityCurve(capitals.clone(), ns, gs, imp, tc, gap);
    }

    public int size() {
        return capitals.length;
    }

    public double capital(int i) {
        return capitals[i];
    }

    public double netSharpe(int i) {
        return netSharpe[i];
    }

    public double grossSharpe(int i) {
        return grossSharpe[i];
    }

    /** Cumulative impact cost, fraction of equity. */
    public double impactDrag(int i) {
        return impactDrag[i];
    }

    /** Cumulative cost of every kind, fraction of equity. */
    public double totalCostDrag(int i) {
        return totalCostDrag[i];
    }

    /** Mean distance from target while working trades under the cap. */
    public double trackingGap(int i) {
        return trackingGap[i];
    }

    public double[] capitalsCopy() {
        return capitals.clone();
    }

    public double[] netSharpeCopy() {
        return netSharpe.clone();
    }

    /**
     * Largest capital at which net Sharpe is still at least {@code floor},
     * interpolating linearly in log capital between grid points. NaN when
     * the smallest capital already misses the floor; the largest capital
     * when no grid point misses it (a lower bound).
     */
    public double capacityAt(double floor) {
        if (netSharpe[0] < floor) {
            return Double.NaN;
        }
        for (int i = 1; i < capitals.length; i++) {
            if (netSharpe[i] < floor) {
                double x0 = Math.log(capitals[i - 1]);
                double x1 = Math.log(capitals[i]);
                double y0 = netSharpe[i - 1];
                double y1 = netSharpe[i];
                double x = x0 + (floor - y0) * (x1 - x0) / (y1 - y0);
                return Math.exp(x);
            }
        }
        return capitals[capitals.length - 1];
    }

    /** A plain-text table. */
    public String format() {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "%14s %10s %10s %10s %10s%n", "capital", "netSharpe", "grossSharpe",
                "impact", "gap"));
        for (int i = 0; i < capitals.length; i++) {
            b.append(String.format(Locale.ROOT, "%14.0f %10.3f %10.3f %10.5f %10.5f%n",
                    capitals[i], netSharpe[i], grossSharpe[i], impactDrag[i], trackingGap[i]));
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "CapacityCurve" + Arrays.toString(capitals);
    }
}

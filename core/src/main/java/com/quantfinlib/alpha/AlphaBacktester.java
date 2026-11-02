package com.quantfinlib.alpha;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.MarketImpactModel;

import com.quantfinlib.risk.FactorRiskModel;

import java.util.List;

/**
 * Execution-aware factor backtest: runs a factor through a construction
 * pipeline into target weights at each rebalance and simulates holding,
 * trading and financing the book bar by bar.
 *
 * <p><b>Timing.</b> Scores and targets are computed from data up to
 * {@code close(t)}. The trade fills according to {@link Config#fillPolicy()}:
 * at {@code close(t)} ({@code CLOSE}, the zero-latency upper bound), at
 * {@code open(t+1)} ({@code NEXT_OPEN}, the default) or at
 * {@code close(t+1)} ({@code NEXT_CLOSE}). Each bar is split at the open
 * into an overnight segment {@code close(t) → open(t+1)} and an intraday
 * segment {@code open(t+1) → close(t+1)} so a next-open fill earns exactly
 * what it should.</p>
 *
 * <p><b>Drift.</b> Weights are fractions of equity and drift with prices
 * between trades: after a segment with name returns {@code r_i} and book
 * return {@code r_p = Σ w_i r_i},
 * {@code w_i ← w_i (1 + r_i)/(1 + r_p)}. Turnover at a rebalance is
 * measured against the drifted book. (Holding the target weights fixed
 * every bar would be a free daily rebalance, which manufactures return for
 * reversal-heavy books and understates turnover.) Cash is
 * {@code 1 − Σ w_i} and earns nothing inside a segment; financing is
 * charged separately.</p>
 *
 * <p><b>Costs</b> are charged on the traded fraction of equity: commission,
 * half-spread and slippage in flat bps, and square-root market impact
 * ({@link MarketImpactModel#estimate}, with ADV and volatility from the
 * bars before the fill bar) on the traded shares
 * {@code |Δw|·capital·equity/price}. Costs come out of equity and the
 * executed weights are fractions of post-cost equity.</p>
 *
 * <p><b>Participation cap.</b> With {@code maxParticipation > 0} each name
 * trades at most {@code maxParticipation × ADV} shares per bar; the
 * unexecuted remainder carries over to the next bar's fill point until the
 * target is reached or replaced by the next rebalance. The mean distance
 * from the target is reported as {@link Result#meanTrackingGap()}.</p>
 *
 * <p><b>Financing</b> per bar, from the book held over the bar: a borrow
 * fee on short notional, interest on positive cash ({@code 1 − Σw}, which
 * includes short sale proceeds, so the short rebate is implicit), and a
 * margin rate on negative cash (leverage). Rates are annual and accrue
 * {@code 1/periodsPerYear} per bar.</p>
 *
 * <p><b>Terminal events.</b> Returns come from
 * {@link AlphaContext#returnBetween}, so a held name that delists earns its
 * delisting return; the position then becomes cash, or acquirer shares for
 * a stock merger whose acquirer is in the panel. Names dead at a fill point
 * cannot be traded.</p>
 *
 * <p>The gross curve applies the same book without trading costs or
 * financing. For share-level accounting with dividends and index drops use
 * {@code backtest.portfolio.PortfolioBacktester}.</p>
 */
public final class AlphaBacktester {

    /** Builds target weights from raw scores at a rebalance (the construction hook). */
    @FunctionalInterface
    public interface WeightBuilder {
        double[] weights(AlphaContext ctx, double[] scores, int index);
    }

    /**
     * Backtest settings: decision schedule, costs and capital.
     *
     * @param startIndex         first decision bar (covers factor warm-up and,
     *                           when capital &gt; 0, more than {@code impactWindow} bars)
     * @param rebalanceEveryBars bars between decisions
     * @param commissionBps      commission per side on traded notional
     * @param halfSpreadBps      half the quoted spread, paid per trade
     * @param slippageBps        fixed implementation shortfall per trade
     * @param capital            book size in currency at the start; impact and
     *                           participation need real size (0 disables both)
     * @param impactWindow       trailing bars for ADV/vol estimation
     * @param periodsPerYear     bars per year for annualised metrics and financing
     * @param fillPolicy         when a decision fills (default {@code NEXT_OPEN})
     * @param borrowBpsPerYear   annual borrow fee on short notional
     * @param cashRatePerYear    annual rate earned on positive cash (fraction, 0.03 = 3%)
     * @param marginRatePerYear  annual rate paid on negative cash
     * @param maxParticipation   max fraction of ADV traded per name per bar (0 = no cap)
     */
    public record Config(int startIndex, int rebalanceEveryBars, double commissionBps,
                         double halfSpreadBps, double slippageBps, double capital,
                         int impactWindow, int periodsPerYear, FillPolicy fillPolicy,
                         double borrowBpsPerYear, double cashRatePerYear,
                         double marginRatePerYear, double maxParticipation) {

        public Config {
            if (startIndex < 0 || rebalanceEveryBars <= 0 || commissionBps < 0
                    || halfSpreadBps < 0 || slippageBps < 0 || capital < 0
                    || impactWindow < 2 || periodsPerYear <= 0 || fillPolicy == null
                    || borrowBpsPerYear < 0 || marginRatePerYear < 0
                    || !Double.isFinite(cashRatePerYear) || maxParticipation < 0) {
                throw new IllegalArgumentException("invalid backtest config");
            }
            if (maxParticipation > 0 && capital == 0) {
                throw new IllegalArgumentException("a participation cap needs capital > 0");
            }
        }

        /** No financing, no participation cap, next-open fills. */
        public Config(int startIndex, int rebalanceEveryBars, double commissionBps,
                      double halfSpreadBps, double slippageBps, double capital,
                      int impactWindow, int periodsPerYear) {
            this(startIndex, rebalanceEveryBars, commissionBps, halfSpreadBps, slippageBps,
                    capital, impactWindow, periodsPerYear, FillPolicy.NEXT_OPEN, 0, 0, 0, 0);
        }

        /**
         * Daily-bar defaults: monthly (21-bar) rebalance, 1 bp commission,
         * 2 bp half-spread, 1 bp slippage, $100m book, 20-bar impact window,
         * next-open fills, no financing, no participation cap.
         */
        public static Config defaults(int startIndex) {
            return new Config(startIndex, 21, 1.0, 2.0, 1.0, 100_000_000, 20, 252);
        }

        public Config withFillPolicy(FillPolicy policy) {
            return new Config(startIndex, rebalanceEveryBars, commissionBps, halfSpreadBps,
                    slippageBps, capital, impactWindow, periodsPerYear, policy,
                    borrowBpsPerYear, cashRatePerYear, marginRatePerYear, maxParticipation);
        }

        /** Annual borrow fee (bps of short notional), cash rate and margin rate. */
        public Config withFinancing(double borrowBps, double cashRate, double marginRate) {
            return new Config(startIndex, rebalanceEveryBars, commissionBps, halfSpreadBps,
                    slippageBps, capital, impactWindow, periodsPerYear, fillPolicy,
                    borrowBps, cashRate, marginRate, maxParticipation);
        }

        public Config withMaxParticipation(double fractionOfAdv) {
            return new Config(startIndex, rebalanceEveryBars, commissionBps, halfSpreadBps,
                    slippageBps, capital, impactWindow, periodsPerYear, fillPolicy,
                    borrowBpsPerYear, cashRatePerYear, marginRatePerYear, fractionOfAdv);
        }

        public Config withRebalanceEvery(int bars) {
            return new Config(startIndex, bars, commissionBps, halfSpreadBps,
                    slippageBps, capital, impactWindow, periodsPerYear, fillPolicy,
                    borrowBpsPerYear, cashRatePerYear, marginRatePerYear, maxParticipation);
        }

        /** The same config with every cost, financing rate and the cap set to zero. */
        public Config frictionless() {
            return new Config(startIndex, rebalanceEveryBars, 0, 0, 0, 0, impactWindow,
                    periodsPerYear, fillPolicy, 0, 0, 0, 0);
        }
    }

    /**
     * Net and gross curves (one point per close from {@code startIndex}),
     * the cumulative fraction of equity each cost consumed, and turnover.
     */
    public static final class Result {
        private final double[] netEquity;
        private final double[] grossEquity;
        private final PerformanceMetrics netMetrics;
        private final PerformanceMetrics grossMetrics;
        private final double commissionDrag;
        private final double spreadDrag;
        private final double slippageDrag;
        private final double impactDrag;
        private final double financingDrag;
        private final double meanTurnover;
        private final double meanTrackingGap;
        private final FillPolicy fillPolicy;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param financingDrag cumulative net financing paid (negative when the book earned interest)
         * @param meanTurnover mean one-way turnover per rebalance decision
         * @param meanTrackingGap mean over bars of {@code ½‖target − w‖₁} while a target was still being worked (0 without a participation cap)
         */
        public Result(double[] netEquity, double[] grossEquity, PerformanceMetrics netMetrics,
                PerformanceMetrics grossMetrics, double commissionDrag, double spreadDrag,
                double slippageDrag, double impactDrag, double financingDrag, double meanTurnover,
                double meanTrackingGap, FillPolicy fillPolicy) {
            this.netEquity = netEquity == null ? null : netEquity.clone();
            this.grossEquity = grossEquity == null ? null : grossEquity.clone();
            this.netMetrics = netMetrics;
            this.grossMetrics = grossMetrics;
            this.commissionDrag = commissionDrag;
            this.spreadDrag = spreadDrag;
            this.slippageDrag = slippageDrag;
            this.impactDrag = impactDrag;
            this.financingDrag = financingDrag;
            this.meanTurnover = meanTurnover;
            this.meanTrackingGap = meanTrackingGap;
            this.fillPolicy = fillPolicy;
        }

        /** Element {@code i} of {@code netEquity}. */
        public double netEquity(int i) {
            return netEquity[i];
        }

        /** Length of {@code netEquity}. */
        public int netEquityLength() {
            return netEquity.length;
        }

        /** A fresh copy of {@code netEquity}. */
        public double[] netEquityCopy() {
            return netEquity == null ? null : netEquity.clone();
        }

        /** Element {@code i} of {@code grossEquity}. */
        public double grossEquity(int i) {
            return grossEquity[i];
        }

        /** Length of {@code grossEquity}. */
        public int grossEquityLength() {
            return grossEquity.length;
        }

        /** A fresh copy of {@code grossEquity}. */
        public double[] grossEquityCopy() {
            return grossEquity == null ? null : grossEquity.clone();
        }

        /** {@code netMetrics}. */
        public PerformanceMetrics netMetrics() {
            return netMetrics;
        }

        /** {@code grossMetrics}. */
        public PerformanceMetrics grossMetrics() {
            return grossMetrics;
        }

        /** {@code commissionDrag}. */
        public double commissionDrag() {
            return commissionDrag;
        }

        /** {@code spreadDrag}. */
        public double spreadDrag() {
            return spreadDrag;
        }

        /** {@code slippageDrag}. */
        public double slippageDrag() {
            return slippageDrag;
        }

        /** {@code impactDrag}. */
        public double impactDrag() {
            return impactDrag;
        }

        /** {@code financingDrag} (cumulative net financing paid (negative when the book earned interest)). */
        public double financingDrag() {
            return financingDrag;
        }

        /** {@code meanTurnover} (mean one-way turnover per rebalance decision). */
        public double meanTurnover() {
            return meanTurnover;
        }

        /** {@code meanTrackingGap}. */
        public double meanTrackingGap() {
            return meanTrackingGap;
        }

        /** {@code fillPolicy}. */
        public FillPolicy fillPolicy() {
            return fillPolicy;
        }

        /** Total trading-cost drag (financing excluded). */
        public double totalCostDrag() {
            return commissionDrag + spreadDrag + slippageDrag + impactDrag;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result o)) {
                return false;
            }
            return java.util.Arrays.equals(netEquity, o.netEquity)
                    && java.util.Arrays.equals(grossEquity, o.grossEquity)
                    && java.util.Objects.equals(netMetrics, o.netMetrics)
                    && java.util.Objects.equals(grossMetrics, o.grossMetrics)
                    && Double.compare(commissionDrag, o.commissionDrag) == 0
                    && Double.compare(spreadDrag, o.spreadDrag) == 0
                    && Double.compare(slippageDrag, o.slippageDrag) == 0
                    && Double.compare(impactDrag, o.impactDrag) == 0
                    && Double.compare(financingDrag, o.financingDrag) == 0
                    && Double.compare(meanTurnover, o.meanTurnover) == 0
                    && Double.compare(meanTrackingGap, o.meanTrackingGap) == 0
                    && java.util.Objects.equals(fillPolicy, o.fillPolicy);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(netEquity);
            h = 31 * h + java.util.Arrays.hashCode(grossEquity);
            h = 31 * h + java.util.Objects.hashCode(netMetrics);
            h = 31 * h + java.util.Objects.hashCode(grossMetrics);
            h = 31 * h + Double.hashCode(commissionDrag);
            h = 31 * h + Double.hashCode(spreadDrag);
            h = 31 * h + Double.hashCode(slippageDrag);
            h = 31 * h + Double.hashCode(impactDrag);
            h = 31 * h + Double.hashCode(financingDrag);
            h = 31 * h + Double.hashCode(meanTurnover);
            h = 31 * h + Double.hashCode(meanTrackingGap);
            h = 31 * h + java.util.Objects.hashCode(fillPolicy);
            return h;
        }

        @Override
        public String toString() {
            return "Result[" + "netEquity=" + java.util.Arrays.toString(netEquity) + ", " + "grossEquity=" + java.util.Arrays.toString(grossEquity) + ", " + "netMetrics=" + netMetrics + ", " + "grossMetrics=" + grossMetrics + ", " + "commissionDrag=" + commissionDrag + ", " + "spreadDrag=" + spreadDrag + ", " + "slippageDrag=" + slippageDrag + ", " + "impactDrag=" + impactDrag + ", " + "financingDrag=" + financingDrag + ", " + "meanTurnover=" + meanTurnover + ", " + "meanTrackingGap=" + meanTrackingGap + ", " + "fillPolicy=" + fillPolicy + "]";
        }
    }

    private AlphaBacktester() {
    }

    /** Runs with the standard z-score construction (gross 1.0, 5% name cap). */
    public static Result run(AlphaContext ctx, AlphaFactor factor, Config config) {
        return run(ctx, factor, config,
                (c, scores, index) -> PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05));
    }

    /** Runs with a caller-supplied construction pipeline. */
    public static Result run(AlphaContext ctx, AlphaFactor factor, Config config,
                             WeightBuilder builder) {
        return new Sim(ctx, factor, config, wrap(builder), false).run();
    }

    /**
     * Risk-model-driven construction for {@link #run(AlphaContext, AlphaFactor, Config,
     * RiskOptions)}: at each rebalance a {@link FactorRiskModel} is rebuilt
     * from the panel up to the decision bar ({@link AlphaRiskModels}) and the
     * book comes from {@link TurnoverAwareOptimizer} started at the drifted
     * book actually held.
     *
     * <p>Over the holding horizon {@code H = rebalanceEveryBars} the
     * optimiser maximises {@code αᵀw − λ wᵀ(H·V)w − κ‖w − w₀‖₁} with
     * {@code α_i = IC · σ_i · √H · z_i} (Grinold 1994, "Alpha is Volatility
     * Times IC Times Score", J. Portfolio Management 20(4)), {@code z} the
     * cross-sectional z-score of the factor, {@code σ_i² = V_ii} the model's
     * per-bar variance and {@code κ} the one-way flat cost
     * {@code (commission + halfSpread + slippage)/10⁴} of the config.
     * Names without a score or a forecast get a zero cap.</p>
     *
     * <p><b>Automatic %ADV trade limit.</b> With {@code advTradeFraction > 0}
     * and {@code capital > 0} each name may trade at most
     * {@code advTradeFraction × ADV_i × price_i / (capital × equity)} of
     * equity per rebalance (ADV from the {@code impactWindow} bars before
     * the decision bar). A name held beyond its cap may always trade back to
     * the cap.</p>
     *
     * @param spec                   risk model window and settings
     * @param sectors                sector label per symbol in context order (null = one sector)
     * @param riskAversion           λ &gt; 0
     * @param informationCoefficient IC used to scale scores into alphas (&gt; 0)
     * @param maxWeight              per-name |w| cap (&gt; 0)
     * @param advTradeFraction       per-rebalance trade limit as a fraction of ADV (0 = none)
     * @param dollarNeutral          add the constraint {@code Σw = 0}
     */
    public record RiskOptions(AlphaRiskModels.Spec spec, List<String> sectors,
                              double riskAversion, double informationCoefficient,
                              double maxWeight, double advTradeFraction, boolean dollarNeutral) {

        public RiskOptions {
            if (spec == null || !(riskAversion > 0) || !(informationCoefficient > 0)
                    || !(maxWeight > 0) || !(advTradeFraction >= 0)
                    || Double.isInfinite(riskAversion) || Double.isInfinite(advTradeFraction)) {
                throw new IllegalArgumentException("invalid risk options");
            }
            sectors = sectors == null ? null : List.copyOf(sectors);
        }

        /** Default spec, one sector, λ = 5, IC = 0.05, 10% cap, 10% of ADV, dollar neutral. */
        public static RiskOptions defaults() {
            return new RiskOptions(AlphaRiskModels.Spec.defaults(), null, 5, 0.05, 0.10, 0.10,
                    true);
        }

        public RiskOptions withSectors(List<String> labels) {
            return new RiskOptions(spec, labels, riskAversion, informationCoefficient, maxWeight,
                    advTradeFraction, dollarNeutral);
        }

        public RiskOptions withSpec(AlphaRiskModels.Spec s) {
            return new RiskOptions(s, sectors, riskAversion, informationCoefficient, maxWeight,
                    advTradeFraction, dollarNeutral);
        }

        public RiskOptions withRiskAversion(double lambda) {
            return new RiskOptions(spec, sectors, lambda, informationCoefficient, maxWeight,
                    advTradeFraction, dollarNeutral);
        }

        public RiskOptions withAdvTradeFraction(double fraction) {
            return new RiskOptions(spec, sectors, riskAversion, informationCoefficient, maxWeight,
                    fraction, dollarNeutral);
        }
    }

    /**
     * A run with its per-bar trace: the book held over each close-to-close
     * bar, each name's P&amp;L, the cost and the financing, all as fractions
     * of equity at the start of the bar. Per bar,
     * {@code Σ_i namePnl + cost + financing} equals the net return exactly
     * (cost and financing are signed P&amp;L, so normally negative).
     */
    public static final class Traced {
        private final Result result;
        private final int startIndex;
        private final double[][] held;
        private final double[][] pnl;
        private final double[] cost;
        private final double[] financing;

        Traced(Result result, int startIndex, double[][] held, double[][] pnl, double[] cost,
               double[] financing) {
            this.result = result;
            this.startIndex = startIndex;
            this.held = held;
            this.pnl = pnl;
            this.cost = cost;
            this.financing = financing;
        }

        public Result result() {
            return result;
        }

        /**
         * Context bar index of trace step 0; step {@code s} covers
         * {@code close(start + s) → close(start + s + 1)}.
         */
        public int startIndex() {
            return startIndex;
        }

        /** Number of traced bars. */
        public int steps() {
            return cost.length;
        }

        /** Weight of name {@code i} held over step {@code s}, after any fill at the step's opening close. */
        public double held(int s, int i) {
            return held[s][i];
        }

        public double[] heldCopy(int s) {
            return held[s].clone();
        }

        /** P&amp;L of name {@code i} over step {@code s}, fraction of equity at the step start. */
        public double namePnl(int s, int i) {
            return pnl[s][i];
        }

        public double[] namePnlCopy(int s) {
            return pnl[s].clone();
        }

        /** Trading cost P&amp;L of step {@code s} (&le; 0). */
        public double cost(int s) {
            return cost[s];
        }

        /** Financing P&amp;L of step {@code s}. */
        public double financing(int s) {
            return financing[s];
        }

        /** Net return of step {@code s} from the equity curve. */
        public double netReturn(int s) {
            return result.netEquity(s + 1) / result.netEquity(s) - 1;
        }
    }

    /**
     * Runs with the risk-model-driven turnover-aware optimiser as the
     * construction (see {@link RiskOptions}). {@code config.startIndex()}
     * must be at least {@code options.spec().firstIndex()}. The
     * three-argument {@link #run(AlphaContext, AlphaFactor, Config)} keeps
     * the z-score construction.
     */
    public static Result run(AlphaContext ctx, AlphaFactor factor, Config config,
                             RiskOptions options) {
        return new Sim(ctx, factor, config, riskBuilder(ctx, config, options), false).run();
    }

    /** {@link #run(AlphaContext, AlphaFactor, Config, WeightBuilder)} with the per-bar trace. */
    public static Traced runTraced(AlphaContext ctx, AlphaFactor factor, Config config,
                                   WeightBuilder builder) {
        Sim sim = new Sim(ctx, factor, config, wrap(builder), true);
        Result r = sim.run();
        return new Traced(r, config.startIndex(), sim.trHeld, sim.trPnl, sim.trCost, sim.trFin);
    }

    /** {@link #run(AlphaContext, AlphaFactor, Config, RiskOptions)} with the per-bar trace. */
    public static Traced runTraced(AlphaContext ctx, AlphaFactor factor, Config config,
                                   RiskOptions options) {
        Sim sim = new Sim(ctx, factor, config, riskBuilder(ctx, config, options), true);
        Result r = sim.run();
        return new Traced(r, config.startIndex(), sim.trHeld, sim.trPnl, sim.trCost, sim.trFin);
    }

    /** Construction hook that also sees the drifted book and current equity. */
    @FunctionalInterface
    interface BookBuilder {
        double[] weights(AlphaContext ctx, double[] scores, int index, double[] current,
                         double equity);
    }

    private static BookBuilder wrap(WeightBuilder b) {
        return (c, scores, index, current, equity) -> b.weights(c, scores, index);
    }

    static BookBuilder riskBuilder(AlphaContext ctx, Config cfg, RiskOptions o) {
        if (cfg.startIndex() < o.spec().firstIndex()) {
            throw new IllegalArgumentException("startIndex " + cfg.startIndex()
                    + " must be at least " + o.spec().firstIndex() + " for the risk model");
        }
        String[] sectors = o.sectors() == null ? null : o.sectors().toArray(new String[0]);
        AlphaRiskModels.Builder models = new AlphaRiskModels.Builder(ctx, sectors, o.spec());
        double kappa = (cfg.commissionBps() + cfg.halfSpreadBps() + cfg.slippageBps()) / 1e4;
        int h = cfg.rebalanceEveryBars();
        return (c, scores, index, current, equity) -> {
            FactorRiskModel model = models.estimate(index);
            int m = c.symbolCount();
            double[] z = Factors.zScore(scores);
            double[][] v = model.covariance();
            double[] alpha = new double[m];
            double[] caps = new double[m];
            double[] limits = new double[m];
            java.util.Arrays.fill(limits, Double.POSITIVE_INFINITY);
            double diag = 0;
            int live = 0;
            for (int i = 0; i < m; i++) {
                int e = c.terminalIndex(i);
                boolean ok = model.covers(i) && Double.isFinite(z[i]) && c.isActive(i, index)
                        && !(e >= 0 && index + 1 >= e);
                if (!ok) {
                    continue; // zero cap, unlimited trade: the name can only be exited
                }
                double sigma = Math.sqrt(v[i][i]);
                alpha[i] = o.informationCoefficient() * sigma * Math.sqrt(h) * z[i];
                caps[i] = o.maxWeight();
                diag += v[i][i];
                live++;
                if (o.advTradeFraction() > 0 && cfg.capital() > 0) {
                    MarketImpactModel im = MarketImpactModel.estimate(c.series(i), index,
                            cfg.impactWindow());
                    // no volume data: no liquidity to size against, so no new trading
                    double lim = im == null ? 0 : o.advTradeFraction() * im.adv()
                            * c.series(i).close(index) / (cfg.capital() * equity);
                    limits[i] = Math.max(lim, Math.abs(current[i]) - caps[i]);
                }
            }
            if (live == 0 || !(diag > 0)) {
                return new double[m];
            }
            // Dividing the objective by 2λH·mean(V_ii) leaves the minimiser unchanged and
            // makes ρ = 1 well conditioned.
            double scale = 2 * o.riskAversion() * h * diag / live;
            double[][] cov = new double[m][m];
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    cov[i][j] = h * v[i][j] / scale;
                }
                alpha[i] /= scale;
            }
            double[][] eq = o.dollarNeutral() ? new double[][]{ones(m)} : new double[0][];
            TurnoverAwareOptimizer.Settings st = new TurnoverAwareOptimizer.Settings(
                    o.riskAversion(), kappa / scale, 1.0, 20_000, 1e-10);
            return TurnoverAwareOptimizer.optimize(alpha, cov, current, eq, caps, limits, st)
                    .weightsCopy();
        };
    }

    static double[] ones(int m) {
        double[] o = new double[m];
        java.util.Arrays.fill(o, 1);
        return o;
    }

    /** One simulation's mutable state. */
    private static final class Sim {
        final AlphaContext ctx;
        final AlphaFactor factor;
        final Config cfg;
        final BookBuilder builder;
        final int m;
        final boolean trace;
        double[][] trHeld;
        double[][] trPnl;
        double[] trCost;
        double[] trFin;
        double[] barPnl;
        double barCost;
        double barFin;
        final double[] w;          // current weights, fractions of net equity
        double[] target;           // pending target, null when none
        double net = 1;
        double gross = 1;
        double commission;
        double spread;
        double slippage;
        double impact;
        double financing;
        double turnover;
        int decisions;
        double gapSum;
        int gapBars;

        Sim(AlphaContext ctx, AlphaFactor factor, Config cfg, BookBuilder builder,
            boolean trace) {
            this.ctx = ctx;
            this.factor = factor;
            this.cfg = cfg;
            this.builder = builder;
            this.m = ctx.symbolCount();
            this.w = new double[m];
            this.trace = trace;
        }

        Result run() {
            int n = ctx.bars();
            if (cfg.startIndex() >= n - 1) {
                throw new IllegalArgumentException("startIndex leaves no bars to trade");
            }
            if (cfg.capital() > 0 && cfg.startIndex() <= cfg.impactWindow()) {
                throw new IllegalArgumentException(
                        "startIndex " + cfg.startIndex() + " must exceed impactWindow "
                                + cfg.impactWindow() + ": impact estimation reads that many bars"
                                + " before the fill bar (or set capital to 0)");
            }
            double[] netEq = new double[n - cfg.startIndex()];
            double[] grossEq = new double[netEq.length];
            netEq[0] = 1;
            grossEq[0] = 1;
            if (trace) {
                int steps = netEq.length - 1;
                trHeld = new double[steps][];
                trPnl = new double[steps][];
                trCost = new double[steps];
                trFin = new double[steps];
            }
            FillPolicy fp = cfg.fillPolicy();
            for (int t = cfg.startIndex(); t < n - 1; t++) {
                int outIdx = t - cfg.startIndex();
                if (outIdx % cfg.rebalanceEveryBars() == 0) {
                    double[] tgt = builder.weights(ctx, factor.scores(ctx, t), t, w.clone(), net);
                    if (tgt.length != m) {
                        throw new IllegalArgumentException("weight builder returned misaligned array");
                    }
                    for (double x : tgt) {
                        if (!Double.isFinite(x)) {
                            throw new IllegalArgumentException("weight builder returned a non-finite weight");
                        }
                    }
                    target = tgt.clone();
                    decisions++;
                }
                double barStart = net;
                if (trace) {
                    barPnl = new double[m];
                    barCost = 0;
                    barFin = 0;
                }
                if (fp == FillPolicy.CLOSE) {
                    execute(t, false);
                }
                double[] held = w.clone(); // book over the bar, for financing
                segment(t, false, t + 1, true);
                if (fp == FillPolicy.NEXT_OPEN) {
                    execute(t + 1, true);
                }
                segment(t + 1, true, t + 1, false);
                finance(held);
                if (fp == FillPolicy.NEXT_CLOSE) {
                    execute(t + 1, false);
                }
                if (target != null) {
                    double gap = 0;
                    for (int i = 0; i < m; i++) {
                        gap += Math.abs(target[i] - w[i]);
                    }
                    gapSum += gap / 2;
                    gapBars++;
                }
                if (trace) {
                    for (int i = 0; i < m; i++) {
                        barPnl[i] /= barStart;
                    }
                    trHeld[outIdx] = held;
                    trPnl[outIdx] = barPnl;
                    trCost[outIdx] = barCost / barStart;
                    trFin[outIdx] = barFin / barStart;
                }
                netEq[outIdx + 1] = net;
                grossEq[outIdx + 1] = gross;
            }
            return new Result(netEq, grossEq,
                    PerformanceAnalytics.compute(netEq, List.of(), cfg.periodsPerYear()),
                    PerformanceAnalytics.compute(grossEq, List.of(), cfg.periodsPerYear()),
                    commission, spread, slippage, impact, financing,
                    decisions == 0 ? 0 : turnover / decisions,
                    cfg.maxParticipation() > 0 && gapBars > 0 ? gapSum / gapBars : 0,
                    cfg.fillPolicy());
        }

        /** Earn one price segment and drift the weights; converts terminated names. */
        void segment(int fromBar, boolean fromOpen, int toBar, boolean toOpen) {
            double rp = 0;
            double[] r = new double[m];
            for (int i = 0; i < m; i++) {
                if (w[i] != 0) {
                    r[i] = ctx.returnBetween(i, fromBar, fromOpen, toBar, toOpen);
                    if (Double.isNaN(r[i])) {
                        throw new IllegalStateException("held a dead name: " + ctx.symbols().get(i));
                    }
                    rp += w[i] * r[i];
                }
            }
            if (rp <= -1) {
                throw new IllegalStateException("book lost all equity at bar " + toBar);
            }
            if (trace) {
                for (int i = 0; i < m; i++) {
                    if (w[i] != 0) {
                        barPnl[i] += net * w[i] * r[i];
                    }
                }
            }
            for (int i = 0; i < m; i++) {
                if (w[i] != 0) {
                    w[i] = w[i] * (1 + r[i]) / (1 + rp);
                }
            }
            net *= 1 + rp;
            gross *= 1 + rp;
            for (int i = 0; i < m; i++) {
                int e = ctx.terminalIndex(i);
                if (w[i] != 0 && e >= 0 && toBar >= e) {
                    // The terminal value now sits in w[i]: move the stock
                    // component to the acquirer, the rest becomes cash.
                    int a = ctx.acquirerIndex(i);
                    double frac = stockFraction(i, toBar, toOpen);
                    if (a >= 0 && frac > 0) {
                        w[a] += w[i] * frac;
                    }
                    w[i] = 0;
                }
            }
        }

        /** Fraction of the terminal value held as acquirer stock at a price point. */
        double stockFraction(int i, int bar, boolean atOpen) {
            int a = ctx.acquirerIndex(i);
            double total = ctx.mark(i, bar, atOpen);
            if (a < 0 || total <= 0) {
                return 0;
            }
            BarSeries acq = ctx.series(a);
            double stock = ctx.terminalAcquirerShares(i) * (atOpen ? acq.open(bar) : acq.close(bar));
            return Math.max(0, Math.min(1, stock / total));
        }

        /** Trade toward the pending target at a fill point. */
        void execute(int bar, boolean atOpen) {
            if (target == null) {
                return;
            }
            double cost;
            double com = 0;
            double spr = 0;
            double slp = 0;
            double imp = 0;
            double traded = 0;
            boolean done = true;
            for (int i = 0; i < m; i++) {
                int e = ctx.terminalIndex(i);
                boolean dead = e >= 0 && bar >= e;
                double tgt = dead ? 0 : target[i];
                double delta = tgt - w[i];
                if (delta == 0 || dead) {
                    continue;
                }
                BarSeries s = ctx.series(i);
                double price = atOpen ? s.open(bar) : s.close(bar);
                MarketImpactModel model = cfg.capital() > 0
                        ? MarketImpactModel.estimate(s, bar, cfg.impactWindow()) : null;
                double size = Math.abs(delta);
                if (cfg.maxParticipation() > 0 && model != null) {
                    double maxShares = cfg.maxParticipation() * model.adv();
                    double maxFraction = maxShares * price / (cfg.capital() * net);
                    if (size > maxFraction) {
                        size = maxFraction;
                        done = false;
                    }
                }
                double exec = Math.signum(delta) * size;
                com += size * cfg.commissionBps() / 1e4;
                spr += size * cfg.halfSpreadBps() / 1e4;
                slp += size * cfg.slippageBps() / 1e4;
                if (model != null) {
                    double shares = size * cfg.capital() * net / price;
                    imp += size * model.squareRootImpactBps(shares) / 1e4;
                }
                traded += size;
                w[i] += exec;
            }
            cost = com + spr + slp + imp;
            if (cost >= 1) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                        "execution cost %.1f%% of equity at bar %d (impact %.1f%%): the book is "
                                + "too large for this universe's liquidity; reduce capital, add a "
                                + "participation cap, or trade less", cost * 100, bar, imp * 100));
            }
            if (trace) {
                barCost -= net * cost;
            }
            net *= 1 - cost;
            commission += com;
            spread += spr;
            slippage += slp;
            impact += imp;
            turnover += traded / 2;
            if (done) {
                target = null;
            }
        }

        /** One bar of financing on the book held over it. */
        void finance(double[] held) {
            double borrow = cfg.borrowBpsPerYear() / 1e4 / cfg.periodsPerYear();
            double cashRate = cfg.cashRatePerYear() / cfg.periodsPerYear();
            double marginRate = cfg.marginRatePerYear() / cfg.periodsPerYear();
            if (borrow == 0 && cashRate == 0 && marginRate == 0) {
                return;
            }
            double sum = 0;
            double shorts = 0;
            for (double x : held) {
                sum += x;
                if (x < 0) {
                    shorts -= x;
                }
            }
            double cash = 1 - sum;
            double pnl = -shorts * borrow + (cash >= 0 ? cash * cashRate : cash * marginRate);
            if (trace) {
                barFin += net * pnl;
            }
            net *= 1 + pnl;
            financing -= pnl;
        }
    }
}

package com.quantfinlib.alpha;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.MarketImpactModel;

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
     *
     * @param financingDrag   cumulative net financing paid (negative when the book earned interest)
     * @param meanTurnover    mean one-way turnover per rebalance decision
     * @param meanTrackingGap mean over bars of {@code ½‖target − w‖₁} while a
     *                        target was still being worked (0 without a participation cap)
     */
    public record Result(double[] netEquity, double[] grossEquity, PerformanceMetrics netMetrics,
                         PerformanceMetrics grossMetrics, double commissionDrag, double spreadDrag,
                         double slippageDrag, double impactDrag, double financingDrag,
                         double meanTurnover, double meanTrackingGap, FillPolicy fillPolicy) {

        /** Total trading-cost drag (financing excluded). */
        public double totalCostDrag() {
            return commissionDrag + spreadDrag + slippageDrag + impactDrag;
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
        return new Sim(ctx, factor, config, builder).run();
    }

    /** One simulation's mutable state. */
    private static final class Sim {
        final AlphaContext ctx;
        final AlphaFactor factor;
        final Config cfg;
        final WeightBuilder builder;
        final int m;
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

        Sim(AlphaContext ctx, AlphaFactor factor, Config cfg, WeightBuilder builder) {
            this.ctx = ctx;
            this.factor = factor;
            this.cfg = cfg;
            this.builder = builder;
            this.m = ctx.symbolCount();
            this.w = new double[m];
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
            FillPolicy fp = cfg.fillPolicy();
            for (int t = cfg.startIndex(); t < n - 1; t++) {
                int outIdx = t - cfg.startIndex();
                if (outIdx % cfg.rebalanceEveryBars() == 0) {
                    double[] tgt = builder.weights(ctx, factor.scores(ctx, t), t);
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
            double cost = 0;
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
            net *= 1 + pnl;
            financing -= pnl;
        }
    }
}

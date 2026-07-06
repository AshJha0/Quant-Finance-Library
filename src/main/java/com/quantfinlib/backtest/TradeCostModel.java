package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.MarketImpactModel;

/**
 * A pluggable per-trade cost model — the ONE definition of "what a trade
 * costs" shared by the backtest engines, so an execution-aware number and a
 * survivorship-aware number can come out of the <em>same</em> run:
 *
 * <ul>
 *   <li>{@link #flat} — a fixed all-in bps (the classic commission
 *       assumption, and the exact equivalent of the legacy
 *       {@code commissionRate} configs);</li>
 *   <li>{@link #institutional} — commission + half-spread + slippage +
 *       square-root market impact, with per-symbol ADV/vol estimated from
 *       the trailing bars via {@link MarketImpactModel#estimate}. This is
 *       the same four-component decomposition {@code alpha.AlphaBacktester}
 *       charges, expressed at the shared seam — the impact term is what
 *       makes cost grow with book size, i.e. what turns "capacity" into a
 *       number.</li>
 * </ul>
 *
 * <p>The contract prices ONE side of a trade (a buy or a sell), all-in, in
 * basis points of traded notional. Implementations must be pure functions
 * of their arguments — engines may call them at any bar in any order.</p>
 */
@FunctionalInterface
public interface TradeCostModel {

    /**
     * All-in one-way cost, in bps of traded notional, for trading
     * {@code notional} (currency units, always positive) of {@code series}
     * at bar {@code index}.
     */
    double costBps(BarSeries series, int index, double notional);

    /** Fixed all-in bps per trade — commission-only, size-independent. */
    static TradeCostModel flat(double bps) {
        if (bps < 0) {
            throw new IllegalArgumentException("bps must be >= 0");
        }
        return (series, index, notional) -> bps;
    }

    /**
     * The institutional four-component model. Impact needs trailing
     * ADV/vol: bars before {@code impactWindow} charge the flat components
     * only (rather than reading before bar 0), as do series without volume
     * data — documented degradation, never a crash.
     *
     * @param commissionBps commission per side
     * @param halfSpreadBps half the quoted spread, paid on every trade
     * @param slippageBps   fixed implementation noise
     * @param impactWindow  trailing bars for ADV/vol estimation (>= 2)
     */
    static TradeCostModel institutional(double commissionBps, double halfSpreadBps,
                                        double slippageBps, int impactWindow) {
        if (commissionBps < 0 || halfSpreadBps < 0 || slippageBps < 0 || impactWindow < 2) {
            throw new IllegalArgumentException(
                    "cost components must be >= 0 and impactWindow >= 2");
        }
        double flat = commissionBps + halfSpreadBps + slippageBps;
        return (series, index, notional) -> {
            if (index < impactWindow) {
                return flat;
            }
            MarketImpactModel impact = MarketImpactModel.estimate(series, index, impactWindow);
            if (impact == null) {
                return flat; // no volume data: impact unknowable, flat costs stand
            }
            double shares = notional / series.close(index);
            return flat + impact.squareRootImpactBps(shares);
        };
    }
}

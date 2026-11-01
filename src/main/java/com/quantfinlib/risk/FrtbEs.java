package com.quantfinlib.risk;

/**
 * FRTB Internal Models Approach expected shortfall — the market-risk
 * capital measure that replaced 10-day VaR: ES at 97.5%, computed on a
 * base 10-day horizon and scaled up across LIQUIDITY HORIZONS (how long
 * each risk factor class realistically takes to exit under stress: 10
 * days for major FX and rates, up to 120 for exotic credit), then
 * anchored to a STRESSED period:
 *
 * <pre>  ES = √( Σⱼ [ ESⱼ · √((LHⱼ − LHⱼ₋₁)/10) ]² )      (the LH cascade)
 *  ES   = ES_R,S · max(1, ES_F,C / ES_R,C)                    (MAR33, stressed calibration)</pre>
 *
 * <p><b>Styled after BCBS MAR33, not certified</b> — the same
 * stance as the LULD and ESMA-tick implementations elsewhere in this
 * library: the FORMULAS are the regulation's, the tests pin their
 * arithmetic, but regulatory capital additionally requires desk-level
 * approvals, the full P&amp;L attribution program
 * ({@link PnlAttribution}), non-modellable risk factor (NMRF) capital,
 * and the standardized-approach floor — all named in
 * {@code docs/MARKET_RISK.md} as out of scope. Standard
 * liquidity horizons are provided as {@link #LH_10} … {@link #LH_120}.
 * Research lane, static, deterministic.</p>
 */
public final class FrtbEs {

    /** The five regulatory liquidity horizons, in days. */
    public static final int LH_10 = 10;
    public static final int LH_20 = 20;
    public static final int LH_40 = 40;
    public static final int LH_60 = 60;
    public static final int LH_120 = 120;

    private FrtbEs() {
    }

    /**
     * ES at 97.5% of a loss sample (positive losses), the FRTB tail
     * measure, using the Acerbi-Tasche estimator of {@link TailRisk}
     * (floored at zero). On a 250-day window the tail holds 6.25
     * observations: the worst six at full weight plus a quarter of the
     * seventh, divided by 6.25.
     */
    public static double es975(double[] losses) {
        return VarEngine.tail(losses, 0.975).expectedShortfall();
    }

    /**
     * The liquidity-horizon cascade: given the base 10-day ES computed
     * on the FULL factor set and the ESs of the nested subsets that
     * remain shocked at each longer horizon, aggregates per MAR33.5:
     *
     * @param esByHorizon esByHorizon[j] = 10-day ES with only the
     *                    factors of liquidity horizon ≥ horizons[j]
     *                    shocked; index 0 is the full set at LH 10
     * @param horizons    ascending, starting at 10 (e.g. {10, 20, 60})
     */
    public static double liquidityHorizonEs(double[] esByHorizon, int[] horizons) {
        if (esByHorizon.length != horizons.length || horizons.length < 1
                || horizons[0] != 10) {
            throw new IllegalArgumentException(
                    "need aligned arrays with horizons starting at 10");
        }
        double sumSq = 0;
        for (int j = 0; j < horizons.length; j++) {
            if (j > 0 && horizons[j] <= horizons[j - 1]) {
                throw new IllegalArgumentException("horizons must ascend");
            }
            if (!(esByHorizon[j] >= 0) || esByHorizon[j] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("ES terms must be >= 0 and finite");
            }
            int prev = j == 0 ? 0 : horizons[j - 1];
            double scaled = esByHorizon[j] * Math.sqrt((horizons[j] - prev) / 10.0);
            sumSq += scaled * scaled;
        }
        return Math.sqrt(sumSq);
    }

    /** Minimum share of full-set current ES the reduced set must explain (BCBS MAR33). */
    public static final double MIN_REDUCED_SET_COVERAGE = 0.75;

    /**
     * Stressed calibration of the IMA expected shortfall (BCBS MAR33):
     *
     * <pre>  ES = ES_R,S · max(1, ES_F,C / ES_R,C)</pre>
     *
     * where ES_R,S is the ES of the reduced factor set over the stressed
     * 12-month period, ES_F,C the ES of the full factor set over the current
     * 12 months, and ES_R,C the ES of the reduced set over the current
     * period. The stressed reduced-set ES is the base; the full/reduced
     * ratio, measured today, scales it up for the factors the stress period
     * does not cover, and that ratio is floored at 1. The reduced set must
     * explain at least 75% of the full-set current ES
     * ({@code ES_R,C >= 0.75 · ES_F,C}); a set that does not is rejected.
     *
     * <p>Example: ES_F,C = 100, ES_R,C = 80, ES_R,S = 150 gives
     * 150 · 1.25 = 187.5.</p>
     *
     * @param esCurrentFull     ES_F,C
     * @param esStressedReduced ES_R,S
     * @param esCurrentReduced  ES_R,C, must be &gt; 0
     * @throws IllegalArgumentException if an input is negative or not finite,
     *                                  or the reduced set explains less than 75%
     */
    public static double stressCalibratedEs(double esCurrentFull, double esStressedReduced,
                                            double esCurrentReduced) {
        if (!(esCurrentFull >= 0) || esCurrentFull == Double.POSITIVE_INFINITY
                || !(esStressedReduced >= 0) || esStressedReduced == Double.POSITIVE_INFINITY
                || !(esCurrentReduced > 0) || esCurrentReduced == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "ES inputs must be non-negative and finite (current reduced > 0)");
        }
        if (esCurrentReduced < MIN_REDUCED_SET_COVERAGE * esCurrentFull) {
            throw new IllegalArgumentException("reduced factor set explains "
                    + esCurrentReduced / esCurrentFull + " of full-set current ES; MAR33 needs >= 0.75");
        }
        return esStressedReduced * Math.max(1, esCurrentFull / esCurrentReduced);
    }

    /**
     * The Basel backtesting traffic light over 250 days of 99% VaR
     * exceptions: GREEN ≤ 4 (model fine), AMBER 5-9 (capital multiplier
     * rises), RED ≥ 10 (model presumed wrong). The one-page summary of
     * what {@code VarBacktest}'s Kupiec/Christoffersen statistics say
     * with p-values.
     */
    public enum TrafficLight {
        GREEN, AMBER, RED;

        public static TrafficLight of(int exceptions250d) {
            if (exceptions250d < 0) {
                throw new IllegalArgumentException("exceptions must be >= 0");
            }
            if (exceptions250d <= 4) {
                return GREEN;
            }
            return exceptions250d <= 9 ? AMBER : RED;
        }
    }
}

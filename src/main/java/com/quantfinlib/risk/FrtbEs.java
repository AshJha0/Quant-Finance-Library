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
 *  IMCC = ES_current · (ES_stressed,reduced / ES_current,reduced)</pre>
 *
 * <p><b>Styled after BCBS MAR33, not certified</b> — the same honesty
 * stance as the LULD and ESMA-tick implementations elsewhere in this
 * library: the FORMULAS are the regulation's, the tests pin their
 * arithmetic, but regulatory capital additionally requires desk-level
 * approvals, the full P&amp;L attribution program
 * ({@link PnlAttribution}), non-modellable risk factor (NMRF) capital,
 * and the standardized-approach floor — all named in
 * {@code docs/MARKET_RISK.md} as deliberately out of scope. Standard
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

    /** ES at 97.5% of a loss sample (positive losses), the FRTB tail measure. */
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

    /**
     * The stressed-calibration multiplier (MAR33.6): current full-factor
     * ES scaled by the reduced-factor-set ratio between the stressed
     * period and today. The reduced set exists because stressed-period
     * data rarely covers every factor; the ratio transports the stress.
     * The regulatory floor: the ratio is at least 1 — a calmer-than-
     * today stressed period must not DISCOUNT capital.
     */
    public static double stressCalibratedEs(double esCurrentFull, double esStressedReduced,
                                            double esCurrentReduced) {
        if (!(esCurrentFull >= 0) || esCurrentFull == Double.POSITIVE_INFINITY
                || !(esStressedReduced >= 0) || esStressedReduced == Double.POSITIVE_INFINITY
                || !(esCurrentReduced > 0) || esCurrentReduced == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "ES inputs must be non-negative and finite (current reduced > 0)");
        }
        double ratio = Math.max(1, esStressedReduced / esCurrentReduced);
        return esCurrentFull * ratio;
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

package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the 3.0 fold of the 2.x {@code ExposureSimulator} into
 * {@link MultiCurrencyExposureSimulator} (decision D4, docs/MIGRATION_3.0.md
 * section 6).
 *
 * <p>The expected numbers were printed by the 2.4.0 code (tag {@code r24-base},
 * {@code ExposureSimulator.simulate}) for the same market, trades, seed and
 * path count. Evidence class: own test against the previous release's output.
 * The multi-currency engine draws the same random numbers but sums its
 * covariance terms in a different order, so the agreement is to rounding, not
 * to the bit. Tolerance stated: relative 1e-12 (12 significant digits). The
 * largest relative difference observed when these values were taken was
 * 6.7e-14 (a 95 % PFE); expected exposures agreed to about 5e-15.</p>
 */
class ExposureSimulatorFoldTest {

    private static final double A = 0.05;
    private static final double SIG = 0.01;
    private static final double REL = 1e-12;
    private static final double[] TIMES = {0.25, 0.75, 1, 1.6, 2.5, 3.5, 4.5};

    // 2.4.0 ExposureSimulator, one pair, swap + FX forward + CSA, 2,000 paths, seed 11
    private static final double[] EE_24 = {1.6579640195972690, 1.4657727185407514, 1.4982231783932394, 1.3726805380761204, 1.2069507629512684, 0.53997085088301800, 0.28358489896999300};
    private static final double[] ENE_24 = {0.33987625821658620, 0.53515197164009940, 0.57734233555015380, 0.69947796480258930, 0.81181954837230150, 0.28909963524752785, 0.15229212940058445};
    private static final double[] PFE95_24 = {4.4347979693795665, 4.4183777510936760, 4.5885379513779310, 4.5997444719474750, 4.6780394087103300, 1.7324625758387233, 1.0340093101261478};
    private static final double CVA_WW_RATES_24 = 0.084416378571704800;
    private static final double CVA_WW_FX_24 = 0.11540210740169156;
    // 2.4.0 ExposureSimulator, rates only (Market.ratesOnly), swap, no CSA, 1,000 paths, seed 3
    private static final double[] EE_RATES_ONLY_24 = {0.97504503853382260, 1.6286436638695256, 1.7902746806176462, 1.9138375637176600, 1.6480943030102144, 1.1243851153631343, 0.42851302561043236};

    private static YieldCurve dom() {
        return YieldCurve.ofZeroRates(new double[]{0.5, 1, 2, 3, 5, 7, 10},
                new double[]{0.025, 0.027, 0.029, 0.031, 0.033, 0.034, 0.035});
    }

    private static YieldCurve fgn() {
        return YieldCurve.ofZeroRates(new double[]{1, 5, 10}, new double[]{0.01, 0.012, 0.015});
    }

    private static void close(double expected, double actual, String what) {
        assertEquals(expected, actual, REL * Math.abs(expected), what);
    }

    @Test
    void onePairMarketReproducesThe24SingleCurrencySimulator() {
        // 2.4 Market(dom, a, sigma, fgn, spot 1.10, fx vol 0.10, rho -0.4) is one
        // pair with foreign rate vol 0 and FX-rate correlation -0.4.
        double rho = -0.4;
        var mkt = new MultiCurrencyExposureSimulator.Market(
                new MultiCurrencyExposureSimulator.Domestic(MultiCurrencyExposureSimulator.Curve.of(dom()), A, SIG),
                List.of(new MultiCurrencyExposureSimulator.Foreign(
                        MultiCurrencyExposureSimulator.Curve.of(fgn()), A, 0, 1.10, 0.10)),
                new double[][]{{1, 0, rho}, {0, 1, 0}, {rho, 0, 1}});
        var swap = new MultiCurrencyExposureSimulator.SwapTerms(100, 0.032, true, 0, 5, 2);
        var fwd = new MultiCurrencyExposureSimulator.FxForwardTerms(80, 1.12, 3);
        var csa = new MultiCurrencyExposureSimulator.Csa(2, 0.5, 10.0 / 250);
        ExposureProfile p = MultiCurrencyExposureSimulator.simulate(mkt,
                List.of(new MultiCurrencyExposureSimulator.Swap(swap, null)),
                List.of(new MultiCurrencyExposureSimulator.FxForward(0, fwd)), csa, TIMES, 2_000, 11L);
        for (int i = 0; i < TIMES.length; i++) {
            close(EE_24[i], p.discountedExpectedExposure(i), "EE t=" + TIMES[i]);
            close(ENE_24[i], p.discountedExpectedNegativeExposure(i), "ENE t=" + TIMES[i]);
            close(PFE95_24[i], p.pfe(i, 0.95), "PFE t=" + TIMES[i]);
        }
        CreditCurve c = CreditCurve.bootstrap(new int[]{5}, new double[]{0.02}, 0.4, dom());
        close(CVA_WW_RATES_24, CvaApproximator.cvaWrongWay(p, c, 0.6, 0.4, ExposureProfile.Factor.RATES), "CVA rates");
        close(CVA_WW_FX_24, CvaApproximator.cvaWrongWay(p, c, 0.6, 0.4, ExposureProfile.Factor.FX), "CVA fx");
    }

    @Test
    void zeroPairMarketReproducesThe24RatesOnlySimulator() {
        var mkt = new MultiCurrencyExposureSimulator.Market(
                new MultiCurrencyExposureSimulator.Domestic(MultiCurrencyExposureSimulator.Curve.of(dom()), A, SIG),
                List.of(), new double[][]{{1}});
        var swap = new MultiCurrencyExposureSimulator.SwapTerms(100, 0.032, true, 0, 5, 2);
        ExposureProfile p = MultiCurrencyExposureSimulator.simulate(mkt,
                List.of(new MultiCurrencyExposureSimulator.Swap(swap, MultiCurrencyExposureSimulator.Curve.of(dom()))),
                List.of(), null, TIMES, 1_000, 3L);
        for (int i = 0; i < TIMES.length; i++) {
            close(EE_RATES_ONLY_24[i], p.discountedExpectedExposure(i), "EE t=" + TIMES[i]);
        }
    }
}

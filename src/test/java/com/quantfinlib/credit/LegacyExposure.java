package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test helper: the 2.x single-currency {@code ExposureSimulator} call shape,
 * expressed on {@link MultiCurrencyExposureSimulator} exactly as
 * docs/MIGRATION_3.0.md section 6 tells users to do it (zero pairs, or one
 * pair with foreign rate vol 0). It keeps the 2.x exposure tests readable
 * after the 3.0 fold; {@link ExposureSimulatorFoldTest} pins the equivalence.
 */
final class LegacyExposure {

    private LegacyExposure() {
    }

    /** The 2.x market parameters; {@code foreign == null} means rates only. */
    record Market(YieldCurve domestic, double meanReversion, double rateVol,
                  YieldCurve foreign, double fxSpot, double fxVol, double fxRateCorrelation) {

        static Market ratesOnly(YieldCurve domestic, double meanReversion, double rateVol) {
            return new Market(domestic, meanReversion, rateVol, null, 0, 0, 0);
        }

        MultiCurrencyExposureSimulator.Market toMarket() {
            var d = new MultiCurrencyExposureSimulator.Domestic(
                    MultiCurrencyExposureSimulator.Curve.of(domestic), meanReversion, rateVol);
            if (foreign == null) {
                return new MultiCurrencyExposureSimulator.Market(d, List.of(), new double[][]{{1}});
            }
            var f = new MultiCurrencyExposureSimulator.Foreign(
                    MultiCurrencyExposureSimulator.Curve.of(foreign), meanReversion, 0, fxSpot, fxVol);
            double r = fxRateCorrelation;
            return new MultiCurrencyExposureSimulator.Market(d, List.of(f),
                    new double[][]{{1, 0, r}, {0, 1, 0}, {r, 0, 1}});
        }
    }

    static ExposureProfile simulate(Market market, List<MultiCurrencyExposureSimulator.SwapTerms> swaps,
                                    List<MultiCurrencyExposureSimulator.FxForwardTerms> forwards,
                                    MultiCurrencyExposureSimulator.Csa csa, double[] times, int paths, long seed) {
        Objects.requireNonNull(market, "market");
        List<MultiCurrencyExposureSimulator.Swap> sw = new ArrayList<>();
        for (var s : swaps) {
            sw.add(new MultiCurrencyExposureSimulator.Swap(s, null));
        }
        List<MultiCurrencyExposureSimulator.FxForward> fw = new ArrayList<>();
        for (var f : forwards) {
            fw.add(new MultiCurrencyExposureSimulator.FxForward(0, f));
        }
        return MultiCurrencyExposureSimulator.simulate(market.toMarket(), sw, fw, csa, times, paths, seed);
    }
}

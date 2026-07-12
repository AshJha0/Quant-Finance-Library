package com.quantfinlib.markets;

import com.quantfinlib.commodities.CommodityCurve;
import com.quantfinlib.rates.RatesOptions;
import com.quantfinlib.rates.SwapPricer;
import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The new asset-class layer pinned by hand: swaps (par identity, DV01),
 * commodity curves (roll yield, implied carry), index construction
 * (divisor continuity, turnover) and private-market analytics (IRR,
 * multiples, KS-PME, Geltner round trip).
 */
class AssetClassRoundTest {

    private static YieldCurve flat5() {
        double[] t = {1, 2, 3, 4, 5, 7, 10};
        double[] z = new double[t.length];
        java.util.Arrays.fill(z, 0.05);
        return YieldCurve.ofZeroRates(t, z);
    }

    // ------------------------------------------------------------------- swaps

    @Test
    void parSwapPricesToZeroAndMatchesRatesOptions() {
        YieldCurve c = flat5();
        double par = SwapPricer.parRate(c, 5);
        assertEquals(0.0, SwapPricer.payerPv(c, 5, par), 1e-12,
                "a swap struck at par is worth exactly zero");
        // Same object RatesOptions computes as the 0-into-5y forward rate.
        assertEquals(RatesOptions.forwardSwapRate(c, 0, 5), par, 1e-12);
        // Flat cc curve: par = the simple annual forward.
        assertEquals(Math.exp(0.05) - 1, par, 1e-12);
        // Below-par fixed rate favors the payer.
        assertTrue(SwapPricer.payerPv(c, 5, par - 0.01) > 0);
    }

    @Test
    void dv01IsAnnuityTimesTheParRateSensitivity() {
        // Subtlety worth pinning: a 1bp bump to the CC ZERO curve moves
        // the SIMPLE par rate by e^z bp (d(e^z - 1)/dz), so the payer
        // DV01 on a flat 5% curve is annuity * e^0.05 * 1bp — the naive
        // "annuity * 1bp" is the sensitivity to the PAR rate, a
        // different derivative.
        YieldCurve c = flat5();
        double par = SwapPricer.parRate(c, 5);
        double dv01 = SwapPricer.dv01(c, 5, par);
        assertTrue(dv01 > 0, "rates up helps the fixed payer");
        assertEquals(SwapPricer.annuity(c, 5) * 1e-4 * Math.exp(0.05), dv01,
                SwapPricer.annuity(c, 5) * 1e-4 * 0.01,
                "DV01 = annuity * e^z * 1bp on a flat cc curve (within 1%)");
    }

    // -------------------------------------------------------------- commodities

    @Test
    void contangoCurveChargesTheLongAndCarryIsExact() {
        CommodityCurve curve = CommodityCurve.of(100,
                new double[]{0.25, 0.5, 1.0}, new double[]{101, 102, 104});
        assertTrue(curve.isContango());
        assertFalse(curve.isBackwardation());
        assertEquals(103, curve.price(0.75), 1e-12, "linear between pillars");
        assertTrue(curve.annualizedRollYield(0.25, 1.0) < 0,
                "rolling long in contango pays away");
        // F = S e^{(r+u-y)t}: implied u-y = ln(104/100)/1 - 3%.
        assertEquals(Math.log(1.04) - 0.03, curve.impliedCarry(1.0, 0.03), 1e-12);
    }

    @Test
    void backwardationPaysTheRollAndGatesHold() {
        CommodityCurve curve = CommodityCurve.of(100,
                new double[]{0.25, 0.5, 1.0}, new double[]{99, 97, 95});
        assertTrue(curve.isBackwardation());
        assertEquals(Math.log(99.0 / 95.0) / 0.75,
                curve.annualizedRollYield(0.25, 1.0), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> curve.price(2.0)); // no extrapolation
        assertThrows(IllegalArgumentException.class, () -> CommodityCurve.of(100,
                new double[]{0.5, 0.25}, new double[]{99, 98}));             // descending
        assertThrows(IllegalArgumentException.class,
                () -> curve.annualizedRollYield(1.0, 0.5));                  // far <= near
    }

    // -------------------------------------------------------- index construction

    @Test
    void weightSchemesMatchHandArithmetic() {
        assertArrayEquals(new double[]{0.5, 0.5}, IndexConstruction.capWeights(
                new double[]{10, 20}, new double[]{100, 50}, new double[]{1, 1}), 1e-12);
        assertArrayEquals(new double[]{2.0 / 3, 1.0 / 3}, IndexConstruction.capWeights(
                new double[]{10, 20}, new double[]{100, 50}, new double[]{1, 0.5}), 1e-12);
        assertArrayEquals(new double[]{1.0 / 9, 8.0 / 9},
                IndexConstruction.priceWeights(new double[]{50, 400}), 1e-12,
                "the Dow accident: the expensive stock owns the index");
        assertArrayEquals(new double[]{0.25, 0.25, 0.25, 0.25},
                IndexConstruction.equalWeights(4), 0.0);
    }

    @Test
    void divisorAdjustmentKeepsTheLevelContinuousThroughAMemberSwap() {
        double[] p = {10, 20};
        double[] s = {100, 50};
        double[] f = {1, 1};
        double divisor = 20;
        double level = IndexConstruction.level(p, s, f, divisor); // 2000/20 = 100
        assertEquals(100, level, 1e-12);

        // Swap member 2 (cap 1000) for a new stock with cap 1200: the
        // divisor rescales 20 * 2200/2000 = 22 and the level holds at 100.
        double newDivisor = IndexConstruction.adjustDivisor(divisor, 2000, 2200);
        assertEquals(22, newDivisor, 1e-12);
        assertEquals(level, IndexConstruction.level(new double[]{10, 30},
                new double[]{100, 40}, f, newDivisor), 1e-12,
                "membership changes must not move the index");
    }

    @Test
    void turnoverIsHalfTheAbsoluteWeightShift() {
        assertEquals(0.1, IndexConstruction.turnover(
                new double[]{0.5, 0.5}, new double[]{0.6, 0.4}), 1e-12);
        assertEquals(0.0, IndexConstruction.turnover(
                new double[]{0.3, 0.7}, new double[]{0.3, 0.7}), 0.0);
    }

    // ------------------------------------------------------------ private markets

    @Test
    void irrRecoversPlantedRatesAndRefusesSignlessFlows() {
        assertEquals(0.10, PrivateMarketAnalytics.irr(new double[]{-100, 110}), 1e-9);
        // -100 + 10/1.1 + 110/1.21 = 0 exactly: IRR 10% with an interim flow.
        assertEquals(0.10, PrivateMarketAnalytics.irr(new double[]{-100, 10, 110}), 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> PrivateMarketAnalytics.irr(new double[]{-100, -10, -5}));
        assertThrows(IllegalArgumentException.class,
                () -> PrivateMarketAnalytics.irr(new double[]{100}));
    }

    @Test
    void multiplesAreExactFractions() {
        assertEquals(1.2, PrivateMarketAnalytics.tvpi(100, 80, 40), 1e-12);
        assertEquals(0.8, PrivateMarketAnalytics.dpi(100, 80, 40), 1e-12);
        assertEquals(0.4, PrivateMarketAnalytics.rvpi(100, 80, 40), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> PrivateMarketAnalytics.tvpi(0, 80, 40));
    }

    @Test
    void ksPmeIsExactlyOneWhenTheFundIsTheIndex() {
        // Invest 100 at t0, let it ride the index to 121: NAV 121,
        // FV(contribution) = 100 * 121/100 = 121 -> PME = 1 exactly.
        double pme = PrivateMarketAnalytics.ksPme(
                new double[]{100, 0, 0}, new double[]{0, 0, 0}, 121,
                new double[]{100, 110, 121});
        assertEquals(1.0, pme, 1e-12);
        // Beating the index: same flows, higher NAV.
        assertTrue(PrivateMarketAnalytics.ksPme(new double[]{100, 0, 0},
                new double[]{0, 0, 0}, 150, new double[]{100, 110, 121}) > 1);
    }

    @Test
    void geltnerDesmoothingInvertsSmoothingExactly() {
        double[] truth = {0.02, -0.01, 0.03, 0.015, -0.02, 0.01};
        double phi = 0.4;
        double[] observed = new double[truth.length];
        observed[0] = truth[0];
        for (int t = 1; t < truth.length; t++) {
            observed[t] = (1 - phi) * truth[t] + phi * observed[t - 1];
        }
        double[] recovered = PrivateMarketAnalytics.geltnerDesmooth(observed, phi);
        assertArrayEquals(truth, recovered, 1e-12,
                "smoothing then desmoothing must round-trip");
        assertThrows(IllegalArgumentException.class,
                () -> PrivateMarketAnalytics.geltnerDesmooth(observed, 1.0));
    }
}

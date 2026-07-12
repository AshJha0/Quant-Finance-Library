package com.quantfinlib.markets;

import com.quantfinlib.commodities.CommodityCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge pins for the asset-class layer, complementing
 * {@link AssetClassRoundTest}: the flat commodity curve (neither shape,
 * zero roll, carry = -r), index gates and a second turnover hand pin,
 * IRR at planted +100% and -50%, the TVPI = DPI + RVPI identity, a fully
 * hand-computed KS-PME with distributions, and the Geltner phi = 0
 * identity.
 */
class AssetClassEdgeTest {

    // -------------------------------------------------------------- commodities

    @Test
    void flatCommodityCurveIsNeitherShapeAndCarriesMinusR() {
        // All futures AT spot: both shape tests are strict, so both are
        // false; ln(F/F) = 0 makes the roll yield exactly zero and the
        // implied carry exactly -r (ln(F/S)/t = 0).
        CommodityCurve flat = CommodityCurve.of(100,
                new double[]{0.25, 0.5, 1.0}, new double[]{100, 100, 100});
        assertFalse(flat.isContango());
        assertFalse(flat.isBackwardation());
        assertEquals(0.0, flat.annualizedRollYield(0.25, 1.0), 0.0);
        assertEquals(-0.03, flat.impliedCarry(1.0, 0.03), 0.0);
        assertEquals(100, flat.spot(), 0.0);
    }

    @Test
    void pillarPricesAreExactAndBothWingsRefuseToExtrapolate() {
        CommodityCurve curve = CommodityCurve.of(100,
                new double[]{0.5, 1.0}, new double[]{102, 104});
        assertEquals(102, curve.price(0.5), 0.0, "exact at the pillar");
        assertEquals(103, curve.price(0.75), 1e-12, "linear midpoint");
        assertThrows(IllegalArgumentException.class, () -> curve.price(0.25)); // before first
        assertThrows(IllegalArgumentException.class, () -> curve.price(1.5));  // after last
        assertThrows(IllegalArgumentException.class,
                () -> curve.impliedCarry(1.0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> CommodityCurve.of(0, new double[]{1}, new double[]{100}));  // zero spot
        assertThrows(IllegalArgumentException.class,
                () -> CommodityCurve.of(100, new double[]{1}, new double[]{-5})); // negative px
    }

    // -------------------------------------------------------- index construction

    @Test
    void weightsNormalizeToOneAndTurnoverMatchesASecondHandPin() {
        // priceWeights {20,30,50} -> {0.2, 0.3, 0.5} exactly.
        assertArrayEquals(new double[]{0.2, 0.3, 0.5},
                IndexConstruction.priceWeights(new double[]{20, 30, 50}), 1e-15);
        double[] cap = IndexConstruction.capWeights(
                new double[]{11, 23, 47}, new double[]{9, 13, 3}, new double[]{1, 0.7, 0.4});
        double sum = cap[0] + cap[1] + cap[2];
        assertEquals(1.0, sum, 1e-15, "cap weights are a probability vector");
        // Equal quarter weights to {0.4, 0.3, 0.2, 0.1}:
        // 0.5 * (0.15 + 0.05 + 0.05 + 0.15) = 0.2.
        assertEquals(0.2, IndexConstruction.turnover(
                IndexConstruction.equalWeights(4),
                new double[]{0.4, 0.3, 0.2, 0.1}), 1e-12);
    }

    @Test
    void indexGatesRefuseNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> IndexConstruction.turnover(new double[]{0.5, 0.5}, new double[]{1.0}));
        assertThrows(IllegalArgumentException.class, () -> IndexConstruction.capWeights(
                new double[]{10}, new double[]{100}, new double[]{1.5}));   // float > 1
        assertThrows(IllegalArgumentException.class, () -> IndexConstruction.level(
                new double[]{10}, new double[]{100}, new double[]{1}, 0));  // divisor 0
        assertThrows(IllegalArgumentException.class, () -> IndexConstruction.equalWeights(0));
        assertThrows(IllegalArgumentException.class,
                () -> IndexConstruction.adjustDivisor(-1, 100, 110));
        assertThrows(IllegalArgumentException.class,
                () -> IndexConstruction.priceWeights(new double[]{10, -5}));
    }

    // ------------------------------------------------------------ private markets

    @Test
    void irrHitsPlantedDoublingAndHalvingRates() {
        // Invest 100 today, receive 200 in one period:
        // -100 + 200/(1+r) = 0 -> r = 1.0 (a 100% money-weighted return).
        assertEquals(1.0, PrivateMarketAnalytics.irr(new double[]{-100, 200}), 1e-9);
        // Receive only 50: 1+r = 0.5 -> r = -0.5.
        assertEquals(-0.5, PrivateMarketAnalytics.irr(new double[]{-100, 50}), 1e-9);
        // Break-even: r = 0 exactly.
        assertEquals(0.0, PrivateMarketAnalytics.irr(new double[]{-100, 100}), 1e-9);
    }

    @Test
    void tvpiIsDpiPlusRvpiOnArbitraryNumbers() {
        // (D + NAV)/C = D/C + NAV/C: the accounting identity must hold on
        // numbers with no nice factors.
        double c = 137, d = 61, nav = 45;
        assertEquals(PrivateMarketAnalytics.dpi(c, d, nav)
                        + PrivateMarketAnalytics.rvpi(c, d, nav),
                PrivateMarketAnalytics.tvpi(c, d, nav), 1e-15);
    }

    @Test
    void ksPmeWithDistributionsMatchesTheHandFractionAndFlagsLaggards() {
        // Flat index: every growth factor is 1, so
        // PME = (30 + 100) / (50 + 50) = 1.3 exactly.
        assertEquals(1.3, PrivateMarketAnalytics.ksPme(
                new double[]{50, 50, 0}, new double[]{0, 0, 30}, 100,
                new double[]{100, 100, 100}), 1e-15);
        // Index up 10% after the contribution, fund NAV only 80:
        // PME = 80 / (100 * 110/100) = 8/11 < 1 — the fund lagged.
        assertEquals(80.0 / 110.0, PrivateMarketAnalytics.ksPme(
                new double[]{100, 0}, new double[]{0, 0}, 80,
                new double[]{100, 110}), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> PrivateMarketAnalytics.ksPme(
                new double[]{0, 0}, new double[]{0, 0}, 100, new double[]{100, 110}));
    }

    @Test
    void geltnerWithZeroPhiIsTheIdentityAndNegativePhiThrows() {
        // phi = 0: r_true = (r_obs - 0) / 1 = r_obs, element for element.
        double[] obs = {0.02, -0.01, 0.03, 0.005};
        assertArrayEquals(obs, PrivateMarketAnalytics.geltnerDesmooth(obs, 0.0), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> PrivateMarketAnalytics.geltnerDesmooth(obs, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> PrivateMarketAnalytics.geltnerDesmooth(new double[]{0.02}, 0.4));
    }
}

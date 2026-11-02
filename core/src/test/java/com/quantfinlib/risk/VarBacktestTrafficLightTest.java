package com.quantfinlib.risk;

import com.quantfinlib.risk.VarBacktest.TrafficLight;
import com.quantfinlib.risk.VarBacktest.Zone;
import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Basel traffic light against the Basel Framework tables (externally
 * anchored: MAR99.17 Table 2 cumulative probabilities and zone boundaries,
 * MAR32.9 Table 1 multipliers), and the χ²(1) tail against known values.
 */
class VarBacktestTrafficLightTest {

    /** MAR99 Table 2, cumulative probability in percent for 0..10 exceptions in 250 at 99 %. */
    static final double[] TABLE2_CUMULATIVE_PCT = {8.11, 28.58, 54.32, 75.81, 89.22, 95.88, 98.63, 99.60,
        99.89, 99.97, 99.99};
    /** MAR32.9 Table 1 multipliers for 0..10 (10 or more). */
    static final double[] TABLE1_MULTIPLIER = {1.50, 1.50, 1.50, 1.50, 1.50, 1.70, 1.76, 1.83, 1.88, 1.92, 2.00};

    @Test
    void cumulativeProbabilitiesMatchMar99Table2() {
        for (int x = 0; x <= 10; x++) {
            TrafficLight t = VarBacktest.trafficLight(x, 250);
            // Table 2 prints two decimals of a percent.
            assertEquals(TABLE2_CUMULATIVE_PCT[x], 100 * t.cumulativeProbability(), 0.005 + 1e-9, "x=" + x);
        }
    }

    @Test
    void zonesAndMultipliersMatchMar32Table1() {
        for (int x = 0; x <= 14; x++) {
            TrafficLight t = VarBacktest.trafficLight(x, 250);
            Zone expected = x <= 4 ? Zone.GREEN : x <= 9 ? Zone.AMBER : Zone.RED;
            assertEquals(expected, t.zone(), "x=" + x);
            assertEquals(TABLE1_MULTIPLIER[Math.min(x, 10)], t.multiplier(), 0, "x=" + x);
            assertEquals(x, t.exceptions());
            assertEquals(250, t.observations());
        }
    }

    @Test
    void otherSampleSizesUseTheBinomialRuleAndHaveNoMultiplier() {
        // 500 days at 99 %: mean 5. F(8) is about 0.93 (green), F(9) about 0.97 (amber); own arithmetic,
        // cross-checked by the direct pmf sum below.
        assertEquals(Zone.GREEN, VarBacktest.trafficLight(8, 500).zone());
        assertEquals(Zone.AMBER, VarBacktest.trafficLight(9, 500).zone());
        assertTrue(Double.isNaN(VarBacktest.trafficLight(9, 500).multiplier()));
        double direct = 0;
        for (int i = 0; i <= 9; i++) {
            direct += Math.exp(SpecialFunctions.logGamma(501) - SpecialFunctions.logGamma(i + 1.0)
                    - SpecialFunctions.logGamma(501.0 - i) + i * Math.log(0.01) + (500 - i) * Math.log(0.99));
        }
        assertEquals(direct, VarBacktest.trafficLight(9, 500).cumulativeProbability(), 1e-7);
        // 97.5 % coverage on 250 days: mean 6.25.
        assertEquals(Zone.GREEN, VarBacktest.trafficLight(9, 250, 0.975).zone());
        assertTrue(Double.isNaN(VarBacktest.trafficLight(3, 250, 0.975).multiplier()));
        assertEquals(1.0, VarBacktest.trafficLight(250, 250).cumulativeProbability());
        assertEquals(Zone.RED, VarBacktest.trafficLight(250, 250).zone());
    }

    @Test
    void rejectsBadInputs() {
        assertThrows(IllegalArgumentException.class, () -> VarBacktest.trafficLight(-1, 250));
        assertThrows(IllegalArgumentException.class, () -> VarBacktest.trafficLight(251, 250));
        assertThrows(IllegalArgumentException.class, () -> VarBacktest.trafficLight(0, 0));
        assertThrows(IllegalArgumentException.class, () -> VarBacktest.trafficLight(1, 250, 1.0));
        assertThrows(IllegalArgumentException.class, () -> VarBacktest.trafficLight(1, 250, Double.NaN));
    }

    @Test
    void chiSquareTailKeepsRelativeAccuracyFarOut() {
        // P(chi2_1 > 100) = 2 Q(10) = erfc(sqrt(50)); Q(10) = 7.619853024160527e-24 (standard tables).
        assertEquals(1.5239706048321054e-23, VarBacktest.chiSquare1PValue(100), 1e-36);
        // P(chi2_1 > 3.841458820694124) = 0.05, the familiar 5 % critical value.
        assertEquals(0.05, VarBacktest.chiSquare1PValue(3.841458820694124), 1e-15);
        // The pre-3.1 form underflows to exactly 0 here.
        assertEquals(0.0, 2 * (1 - SpecialFunctions.normCdf(Math.sqrt(100))));
        assertTrue(VarBacktest.chiSquare1PValue(1000) > 0);
        assertEquals(1.0, VarBacktest.chiSquare1PValue(0));
        assertEquals(1.0, VarBacktest.chiSquare1PValue(Double.NaN));
    }

    @Test
    void newTailAgreesWithTheOldFormWhereTheOldFormWasAccurate() {
        for (double x = 0.01; x < 40; x *= 1.3) {
            double old = 2 * (1 - SpecialFunctions.normCdf(Math.sqrt(x)));
            assertEquals(old, VarBacktest.chiSquare1PValue(x), 5e-16, "x=" + x);
        }
    }

    @Test
    void kupiecPValueOfAHopelessModelIsTinyButPositive() {
        double[] r = new double[250];
        for (int i = 0; i < r.length; i++) {
            r[i] = i % 4 == 0 ? -0.05 : 0.001;     // 63 exceptions against a 1 % VaR of 2 %
        }
        VarBacktest.VarBacktestResult res = VarBacktest.test(r, 0.02, 0.99);
        assertEquals(63, res.exceptions());
        assertTrue(res.kupiecPValue() > 0 && res.kupiecPValue() < 1e-30, "p=" + res.kupiecPValue());
    }
}

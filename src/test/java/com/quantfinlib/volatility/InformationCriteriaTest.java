package com.quantfinlib.volatility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AIC/BIC: exact arithmetic, ranking direction, and the n-dependent penalty. */
class InformationCriteriaTest {

    @Test
    void formulasAreExact() {
        assertEquals(2 * 3 - 2 * (-100.0), InformationCriteria.aic(-100, 3), 0.0);
        assertEquals(206.0, InformationCriteria.aic(-100, 3), 0.0);
        assertEquals(3 * Math.log(500) - 2 * (-100.0), InformationCriteria.bic(-100, 3, 500), 0.0);
        // Zero parameters: the criteria are just -2 ln L.
        assertEquals(200.0, InformationCriteria.aic(-100, 0), 0.0);
        assertEquals(200.0, InformationCriteria.bic(-100, 0, 500), 0.0);
    }

    @Test
    void betterLikelihoodWinsAtEqualComplexity() {
        // Same k (and n): the model with the higher log-likelihood must
        // score LOWER on both criteria — lower is better.
        assertTrue(InformationCriteria.aic(-95, 4) < InformationCriteria.aic(-100, 4));
        assertTrue(InformationCriteria.bic(-95, 4, 250) < InformationCriteria.bic(-100, 4, 250));
    }

    @Test
    void bicPenalizesParametersHarderThanAicOnLargeSamples() {
        // One extra parameter costs 2 under AIC but ln(n) under BIC: for
        // n >= 8 (ln 8 > 2) BIC can reject an extra parameter AIC accepts.
        double gain = 1.2;   // log-likelihood improvement from the extra parameter
        double aicSimple = InformationCriteria.aic(-100, 1);
        double aicRich = InformationCriteria.aic(-100 + gain, 2);
        double bicSimple = InformationCriteria.bic(-100, 1, 1000);
        double bicRich = InformationCriteria.bic(-100 + gain, 2, 1000);
        assertTrue(aicRich < aicSimple, "AIC accepts: 2*1.2 > 2");
        assertTrue(bicRich > bicSimple, "BIC rejects: 2*1.2 < ln(1000)");
    }

    @Test
    void gatesRefuseNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> InformationCriteria.aic(Double.NaN, 3));
        assertThrows(IllegalArgumentException.class,
                () -> InformationCriteria.aic(Double.POSITIVE_INFINITY, 3));
        assertThrows(IllegalArgumentException.class,
                () -> InformationCriteria.aic(-100, -1));
        assertThrows(IllegalArgumentException.class,
                () -> InformationCriteria.bic(-100, 3, 0));
        assertThrows(IllegalArgumentException.class,
                () -> InformationCriteria.bic(Double.NEGATIVE_INFINITY, 3, 100));
    }
}

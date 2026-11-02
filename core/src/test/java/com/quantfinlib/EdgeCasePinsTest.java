package com.quantfinlib;

import com.quantfinlib.pricing.TouchOption;
import com.quantfinlib.rates.NelsonSiegel;
import com.quantfinlib.risk.VarEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary pins flagged by the review round as reachable but untested:
 * each test encodes the DOCUMENTED edge semantics so a refactor that
 * changes them fails here, not on a desk.
 */
class EdgeCasePinsTest {

    @Test
    void alreadyTouchingPaysEvenAtExpiry() {
        // Spot sitting exactly on the barrier is a touch — including at
        // T=0, where the general formula would otherwise say "no time
        // left, probability 0".
        assertEquals(1.0, TouchOption.hitProbability(100, 100, 0.02, 0, 0.2, 0.0), 0.0);
        assertEquals(1.0, TouchOption.hitProbability(100, 100, 0.02, 0, 0.2, 1.0), 0.0);
        // Off the barrier with zero time: no touch is possible.
        assertEquals(0.0, TouchOption.hitProbability(100, 110, 0.02, 0, 0.2, 0.0), 0.0);
    }

    @Test
    void minimumSampleHistoricalEsIsTheSingleWorstLoss() {
        // 20 scenarios at 99%: the VaR index lands on the maximum loss and
        // the ES averages exactly one observation — VaR == ES. Legal, but
        // a desk should know its "tail average" is a single point.
        double[] exposures = {1.0};
        double[][] scenarios = new double[20][1];
        for (int i = 0; i < 20; i++) {
            scenarios[i][0] = -(i + 1) / 100.0; // losses 0.01 .. 0.20
        }
        VarEngine.VarResult r = VarEngine.historicalVar(exposures, scenarios, 0.99);
        assertEquals(0.20, r.var(), 1e-12);
        assertEquals(r.var(), r.expectedShortfall(), 1e-12,
                "at n=20/99% the ES window is one observation");
    }

    @Test
    void nelsonSiegelRefusesAnUnfittableGridLoudly() {
        // Four observations at the same tenor: every lambda node's normal
        // matrix is singular, so no fit exists — the failure must be a
        // clear throw, not a garbage curve from a half-solved node.
        assertThrows(IllegalArgumentException.class, () -> NelsonSiegel.fit(
                new double[]{2, 2, 2, 2}, new double[]{0.04, 0.041, 0.039, 0.04}));
    }
}

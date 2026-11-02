package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinning tests for the 3.0 risk additions: CRE41.9/41.10 unknown W,
 * CRE41.15 junior unrated floor, MAR22.28/30/39 decomposition offsets and
 * CRE22.49/22.50 fund haircuts. Paragraphs checked against the
 * consolidated Basel Framework PDF on disk. Expected values are own hand
 * computations shown beside each assertion; the Basel text prints no
 * worked number for these rules except the 10-15% vs 10-12%/12-15%
 * tranche pattern of MAR22.39(3), used below.
 */
class Risk30RulesTest {

    private static final double TOL = 1e-12;

    @Test
    void cre41_9_unknownWAdjustsKa() {
        // 3% unknown, K_A known 0.10: 0.97*0.10 + 0.03 = 0.127
        assertEquals(0.127, FrtbDrcSecuritisation.kAPartlyUnknownW(0.10, 97, 3), TOL);
        // exactly 5% still allowed: 0.95*0.2 + 0.05 = 0.24
        assertEquals(0.24, FrtbDrcSecuritisation.kAPartlyUnknownW(0.2, 95, 5), TOL);
        // all known: unchanged
        assertEquals(0.2, FrtbDrcSecuritisation.kAPartlyUnknownW(0.2, 10, 0), TOL);
        assertTrue(FrtbDrcSecuritisation.secSaPermittedWithUnknownW(95, 5));
        assertEquals(0.05, FrtbDrcSecuritisation.MAX_UNKNOWN_W_SHARE, 0);
    }

    @Test
    void cre41_10_moreThanFivePercentUnknownIsRejected() {
        assertFalse(FrtbDrcSecuritisation.secSaPermittedWithUnknownW(94, 6));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.kAPartlyUnknownW(0.1, 94, 6));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.kAPartlyUnknownW(1.1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.kAPartlyUnknownW(-0.1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.secSaPermittedWithUnknownW(0, 0));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.secSaPermittedWithUnknownW(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.secSaPermittedWithUnknownW(1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.secSaPermittedWithUnknownW(Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.secSaPermittedWithUnknownW(Double.NaN, 1));
    }

    @Test
    void cre41_15_juniorUnratedNotBelowNextSeniorRated() {
        assertEquals(0.6, FrtbDrcSecuritisation.juniorUnratedFloor(0.3, 0.6), TOL);
        assertEquals(0.9, FrtbDrcSecuritisation.juniorUnratedFloor(0.9, 0.6), TOL);
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.juniorUnratedFloor(-0.1, 0.6));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.juniorUnratedFloor(0.1, -0.6));
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.juniorUnratedFloor(Double.POSITIVE_INFINITY, 0.6));
    }

    @Test
    void mar22_28_indexDecomposedIntoReplicatingTranches() {
        // 100 long index over 0-3, 3-7, 7-100%: 3, 4, 93
        double[] r = FrtbDrcSecuritisation.decomposeIndex(100,
                new double[]{0, 0.03, 0.07}, new double[]{0.03, 0.07, 1});
        assertArrayEquals(new double[]{3, 4, 93}, r, 1e-9);
        // gap, overlap, wrong ends, mismatched lengths, empty, NaN
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{0, 0.04}, new double[]{0.03, 1}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{0, 0.02}, new double[]{0.03, 1}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{0.01}, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{0}, new double[]{0.9}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{0, 0.5}, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{}, new double[]{}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1,
                new double[]{0, 0.5, 0.5}, new double[]{0.5, 0.5, 1}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.decomposeIndex(Double.NaN,
                new double[]{0}, new double[]{1}));
        assertThrows(NullPointerException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1, null, new double[]{1}));
        assertThrows(NullPointerException.class, () -> FrtbDrcSecuritisation.decomposeIndex(1, new double[]{0}, null));
    }

    @Test
    void mar22_39_3_trancheOffsetByReplication() {
        // long 50 in 10-15% vs short 20 in 10-12% and short 30 in 12-15%: perfect, residual 0
        double[] r = FrtbDrcSecuritisation.offsetByReplication(0.10, 0.15, 50,
                new double[]{0.10, 0.12}, new double[]{0.12, 0.15}, new double[]{-20, -30});
        assertArrayEquals(new double[]{0, 0}, r, 1e-9);
        // MAR22.30(1) imperfect: short 10 and 30: residual 10 and 0
        r = FrtbDrcSecuritisation.offsetByReplication(0.10, 0.15, 50,
                new double[]{0.10, 0.12}, new double[]{0.12, 0.15}, new double[]{-10, -30});
        assertArrayEquals(new double[]{10, 0}, r, 1e-9);
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.offsetByReplication(0.15, 0.10, 1,
                new double[]{0.15}, new double[]{0.10}, new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.offsetByReplication(-0.1, 0.10, 1,
                new double[]{-0.1}, new double[]{0.10}, new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.offsetByReplication(0.1, 1.1, 1,
                new double[]{0.1}, new double[]{1.1}, new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.offsetByReplication(0.10, 0.15, 1,
                new double[]{0.10}, new double[]{0.14}, new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.offsetByReplication(0.10, 0.15, 1,
                new double[]{0.10}, new double[]{0.15}, new double[]{0, 1}));
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.offsetByReplication(0.10, 0.15,
                Double.NaN, new double[]{0.10}, new double[]{0.15}, new double[]{0}));
        assertThrows(NullPointerException.class, () -> FrtbDrcSecuritisation.offsetByReplication(0.10, 0.15, 1,
                new double[]{0.10}, new double[]{0.15}, null));
    }

    @Test
    void mar22_39_2_singleNameDecompositionVanillaOnlyAndConsistent() {
        double[] in = {4, 6};
        double[] out = FrtbDrcSecuritisation.checkSingleNameDecomposition(10, in, false, 1e-9);
        assertArrayEquals(in, out, 0);
        out[0] = 99;
        assertEquals(4, in[0], 0);
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.checkSingleNameDecomposition(10, in, true, 1e-9));
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.checkSingleNameDecomposition(10.1, in, false, 0.05));
        assertEquals(2, FrtbDrcSecuritisation.checkSingleNameDecomposition(10.1, in, false, 0.1).length);
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.checkSingleNameDecomposition(10, in, false, -1));
        assertThrows(IllegalArgumentException.class,
                () -> FrtbDrcSecuritisation.checkSingleNameDecomposition(10, in, false, Double.NaN));
        assertThrows(NullPointerException.class,
                () -> FrtbDrcSecuritisation.checkSingleNameDecomposition(10, null, false, 0));
    }

    @Test
    void cre22_49_fundMandateTakesHighestHaircut() {
        // mandate: main-index equity 20%, A-rated 3y corporate 4%: H = 20%
        assertEquals(0.20, SaCcrHaircuts.fundHaircut10DayMandate(0.04, 0.20), TOL);
        assertEquals(0.0, SaCcrHaircuts.fundHaircut10DayMandate(0.0), TOL);
        assertThrows(IllegalArgumentException.class, SaCcrHaircuts::fundHaircut10DayMandate);
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayMandate(1.1));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayMandate(-0.01));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayMandate(Double.NaN));
        assertThrows(NullPointerException.class, () -> SaCcrHaircuts.fundHaircut10DayMandate((double[]) null));
    }

    @Test
    void cre22_50_fundLookThroughWeightedAverage() {
        // 60% at 20%, 40% at 4%: 0.12 + 0.016 = 0.136
        assertEquals(0.136, SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{0.6, 0.4}, new double[]{0.20, 0.04}), TOL);
        assertEquals(1.0, SaCcrHaircuts.fundHaircut10DayLookThrough(new double[]{1}, new double[]{1}), TOL);
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{0.6, 0.3}, new double[]{0.2, 0.04}));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{1.2, -0.2}, new double[]{0.2, 0.04}));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{Double.POSITIVE_INFINITY}, new double[]{0.2}));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{1}, new double[]{0.2, 0.1}));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{}, new double[]{}));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{1}, new double[]{2}));
        assertThrows(NullPointerException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(null, new double[]{1}));
        assertThrows(NullPointerException.class, () -> SaCcrHaircuts.fundHaircut10DayLookThrough(new double[]{1}, null));
    }

    @Test
    void boundaryInputsAreAccepted() {
        assertEquals(0.0, FrtbDrcSecuritisation.kAPartlyUnknownW(0, 1, 0), TOL);
        assertEquals(1.0, FrtbDrcSecuritisation.kAPartlyUnknownW(1, 1, 0), TOL);
        assertFalse(FrtbDrcSecuritisation.secSaPermittedWithUnknownW(0, 1));
        assertEquals(0.0, FrtbDrcSecuritisation.juniorUnratedFloor(0, 0), TOL);
        assertEquals(5, FrtbDrcSecuritisation.checkSingleNameDecomposition(5, new double[]{2, 3}, false, 0)[0]
                + FrtbDrcSecuritisation.checkSingleNameDecomposition(5, new double[]{2, 3}, false, 0)[1], TOL);
        assertArrayEquals(new double[]{0}, FrtbDrcSecuritisation.offsetByReplication(0, 1, 7,
                new double[]{0}, new double[]{1}, new double[]{-7}), TOL);
        assertEquals(0.2, SaCcrHaircuts.fundHaircut10DayLookThrough(
                new double[]{0, 1}, new double[]{0.9, 0.2}), TOL);
    }

    @Test
    void drcConversionIsEightPercentLibraryReading() {
        // RBC20.1(3) 8.0%, MAR20.1 x12.5; 1250% -> 100% of JTD (MAR22.34(3) cap)
        assertEquals(1.0, FrtbDrcSecuritisation.drcRiskWeight(12.5), TOL);
    }
}

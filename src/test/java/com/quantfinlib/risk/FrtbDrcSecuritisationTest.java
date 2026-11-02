package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FRTB DRC for securitisations (MAR22.27 to MAR22.45) and the CRE41/CRE42
 * risk weights it borrows. The MAR22.45 case is the published example;
 * every other expected value is an own hand computation shown beside it.
 */
class FrtbDrcSecuritisationTest {

    private static final double TOL = 1e-12;

    @Test
    void jtdIsMarketValueAndNthToDefaultPoints() {
        // MAR22.27/22.37: no LGD
        assertEquals(-37.5, FrtbDrcSecuritisation.grossJtd(-37.5), TOL);
        // MAR22.38: 3rd-to-default on 10 names spans 20% to 30%
        assertEquals(0.2, FrtbDrcSecuritisation.nthToDefaultAttachment(3, 10), TOL);
        assertEquals(0.3, FrtbDrcSecuritisation.nthToDefaultDetachment(3, 10), TOL);
        assertThrows(IllegalArgumentException.class, () -> FrtbDrcSecuritisation.nthToDefaultAttachment(11, 10));
        // MAR22.30: same tranche, long 100 over 2y, short 80 over 0.5y: 100 - 80*0.5 = 60
        assertEquals(60, FrtbDrcSecuritisation.netTranche(new double[]{100, -80}, new double[]{2, 0.5}), TOL);
    }

    @Test
    void secSaRiskWeights() {
        // CRE41.8: K_A = 0.9*0.06 + 0.5*0.1 = 0.104
        assertEquals(0.104, FrtbDrcSecuritisation.kA(0.06, 0.1), TOL);
        // D <= K_A: 1250% (CRE41.13(1))
        assertEquals(12.5, FrtbDrcSecuritisation.secSaRiskWeight(0.08, 0.0, 0.05, false, false, false), TOL);
        // A >= K_A, A=20%, D=100%, K_A=8%: a=-12.5, u=0.92, l=0.12,
        // K_SSFA=(e^-11.5 - e^-1.5)/(-12.5*0.8)=0.02231200; RW=0.2789000
        assertEquals(0.278900037568539,
                FrtbDrcSecuritisation.secSaRiskWeight(0.08, 0.2, 1.0, false, false, true), 1e-12);
        // straddling K_A, A=5%, D=15%: 12.5*0.3 + 12.5*K_SSFA*0.7 = 9.581380 (CRE41.13(3))
        assertEquals(9.581379803214915,
                FrtbDrcSecuritisation.secSaRiskWeight(0.08, 0.05, 0.15, false, false, false), 1e-12);
        // STC p=0.5 gives 3.11%, floored at 10% senior (CRE41.21-22), 15% non-senior
        assertEquals(0.10, FrtbDrcSecuritisation.secSaRiskWeight(0.08, 0.2, 1.0, false, true, true), TOL);
        assertEquals(0.15, FrtbDrcSecuritisation.secSaRiskWeight(0.08, 0.2, 1.0, false, true, false), TOL);
        // resecuritisation p=1.5 gives 68.9%, floored at 100% (CRE41.16, CRE41.18)
        assertEquals(1.0, FrtbDrcSecuritisation.secSaRiskWeight(0.08, 0.2, 1.0, true, false, true), TOL);
    }

    @Test
    void erbaOneYearRiskWeights() {
        FrtbDrcSecuritisation.ErbaRating bbb = FrtbDrcSecuritisation.ErbaRating.BBB;
        // CRE42.4 Table 2 senior 1y: 90%
        assertEquals(0.90, FrtbDrcSecuritisation.erbaRiskWeight(bbb, true, 0, false), TOL);
        // non-senior 220% * (1 - 0.10) = 198% (CRE42.5(2))
        assertEquals(1.98, FrtbDrcSecuritisation.erbaRiskWeight(bbb, false, 0.10, false), 1e-12);
        // AAA non-senior thick: 15% * 0.5 = 7.5% -> floor 15% (CRE42.7)
        assertEquals(0.15, FrtbDrcSecuritisation.erbaRiskWeight(
                FrtbDrcSecuritisation.ErbaRating.AAA, false, 0.6, false), TOL);
        // A non-senior thick: 80% * 0.5 = 40% < senior 50% -> 50% (CRE42.7)
        assertEquals(0.50, FrtbDrcSecuritisation.erbaRiskWeight(
                FrtbDrcSecuritisation.ErbaRating.A, false, 0.6, false), TOL);
        // STC Table 4: A non-senior 60% * 0.5 = 30%, no senior comparison (CRE42.14)
        assertEquals(0.30, FrtbDrcSecuritisation.erbaRiskWeight(
                FrtbDrcSecuritisation.ErbaRating.A, false, 0.6, true), TOL);
        assertEquals(0.10, FrtbDrcSecuritisation.erbaRiskWeight(
                FrtbDrcSecuritisation.ErbaRating.AAA, true, 0, true), TOL);
        // Tables 1 and 3
        assertEquals(0.50, FrtbDrcSecuritisation.ErbaShortTermRating.A2_P2.riskWeight(false), TOL);
        assertEquals(0.30, FrtbDrcSecuritisation.ErbaShortTermRating.A2_P2.riskWeight(true), TOL);
        // DRC weight: 1250% * 8% = 100%, ie a charge equal to market value
        assertEquals(1.0, FrtbDrcSecuritisation.drcRiskWeight(12.5), TOL);
    }

    @Test
    void nonCtpBucketsSumWithoutDiversification() {
        FrtbDrcSecuritisation.NonCtpBucket rmbsEu = new FrtbDrcSecuritisation.NonCtpBucket(
                FrtbDrcSecuritisation.AssetClass.RMBS, FrtbDrcSecuritisation.Region.EUROPE);
        double rwLong = FrtbDrcSecuritisation.drcRiskWeight(0.90);   // 7.2%
        double rwShort = FrtbDrcSecuritisation.drcRiskWeight(0.50);  // 4%
        List<FrtbDrcSecuritisation.Position<FrtbDrcSecuritisation.NonCtpBucket>> p = List.of(
                new FrtbDrcSecuritisation.Position<>(rmbsEu, new FrtbDrcSecuritisation.NetJtd(100, rwLong)),
                new FrtbDrcSecuritisation.Position<>(rmbsEu, new FrtbDrcSecuritisation.NetJtd(-50, rwShort)),
                new FrtbDrcSecuritisation.Position<>(FrtbDrcSecuritisation.NonCtpBucket.corporates(),
                        new FrtbDrcSecuritisation.NetJtd(-10, 0.1)));
        // RMBS EU: HBR 2/3, 7.2 - (2/3)*2 = 5.866667; corporates net short floors at 0 (MAR22.33, 22.35)
        assertEquals(7.2 - 2.0 / 3 * 2.0, FrtbDrcSecuritisation.nonCtpTotal(p), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> new FrtbDrcSecuritisation.NonCtpBucket(
                FrtbDrcSecuritisation.AssetClass.CORPORATES_EX_SME, FrtbDrcSecuritisation.Region.ASIA));
        assertThrows(IllegalArgumentException.class, () -> new FrtbDrcSecuritisation.NonCtpBucket(
                FrtbDrcSecuritisation.AssetClass.CLO, null));
    }

    @Test
    void ctpPublishedAggregationExample() {
        // MAR22.45: +100 and -100 give 100 - 0.5*100 = 50
        assertEquals(50, FrtbDrcSecuritisation.ctpAggregate(List.of(100.0, -100.0)), TOL);
        assertEquals(0, FrtbDrcSecuritisation.ctpAggregate(List.of(10.0, -100.0)), TOL);
    }

    @Test
    void ctpUsesPortfolioWideHbrAndNoBucketFloor() {
        List<FrtbDrcSecuritisation.Position<String>> p = List.of(
                new FrtbDrcSecuritisation.Position<>("CDX.NA.IG", new FrtbDrcSecuritisation.NetJtd(100, 0.06)),
                new FrtbDrcSecuritisation.Position<>("CDX.NA.IG", new FrtbDrcSecuritisation.NetJtd(-40, 0.03)),
                new FrtbDrcSecuritisation.Position<>("iTraxx.EU.IG", new FrtbDrcSecuritisation.NetJtd(-80, 0.06)));
        // HBR_ctp = 100/220; CDX: 6 - (5/11)*1.2 = 5.454545; iTraxx: -(5/11)*4.8 = -2.181818
        double hbr = 100.0 / 220;
        double cdx = 6 - hbr * 1.2;
        double itx = -hbr * 4.8;
        assertEquals(itx, FrtbDrcSecuritisation.ctpBucketCharge(
                List.of(new FrtbDrcSecuritisation.NetJtd(-80, 0.06)), hbr), 1e-12);
        // total = 5.454545 - 0.5*2.181818 = 4.363636
        assertEquals(cdx + 0.5 * itx, FrtbDrcSecuritisation.ctpTotal(p), 1e-12);
    }
}

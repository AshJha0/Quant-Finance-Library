package com.quantfinlib.risk.simm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand computations of each SIMM aggregation stage, on small parameter sets
 * built in the test so every number can be checked on paper. Evidence class:
 * own test, independent hand computation.
 */
class SimmCalculatorTest {

    private static final SimmParameters ILL = SimmParameters.illustrative();
    private static final double TOL = 1e-9;

    private static CrifSensitivity row(SimmProductClass p, String type, String q, String b,
                                       String l1, String l2, double amt) {
        return new CrifSensitivity(p, type, q, b, l1, l2, amt);
    }

    private static CrifSensitivity ir(String tenor, String curve, double amt) {
        return row(SimmProductClass.RATES_FX, "Risk_IRCurve", "USD", "USD", tenor, curve, amt);
    }

    private static CrifSensitivity eq(String q, String b, double amt) {
        return row(SimmProductClass.EQUITY, "Risk_Equity", q, b, "", "", amt);
    }

    private static double simm(SimmParameters p, CrifSensitivity... rows) {
        return new SimmCalculator(p).calculate(List.of(rows)).total();
    }

    @Test
    void singleDeltaReducesToRiskWeightTimesSensitivityInEveryRiskClass() {
        assertEquals(50.0 * 1000, simm(ILL, ir("5y", "OIS", 1000)), TOL);
        assertEquals(50.0 * 1000, simm(ILL, ir("5y", "OIS", -1000)), TOL);
        assertEquals(80.0 * 7, simm(ILL, row(SimmProductClass.CREDIT, "Risk_CreditQ", "ISSUER1", "1", "5y", "", 7)), TOL);
        assertEquals(300.0 * 7, simm(ILL, row(SimmProductClass.CREDIT, "Risk_CreditNonQ", "RMBS1", "1", "5y", "", 7)), TOL);
        assertEquals(30.0 * 7, simm(ILL, eq("ABC", "1", 7)), TOL);
        assertEquals(25.0 * 7, simm(ILL, row(SimmProductClass.COMMODITY, "Risk_Commodity", "WTI", "2", "", "", 7)), TOL);
        assertEquals(8.0 * 7, simm(ILL, row(SimmProductClass.RATES_FX, "Risk_FX", "EUR", "", "", "", 7)), TOL);
        assertEquals(50.0 * 7, simm(ILL, row(SimmProductClass.RATES_FX, "Risk_Inflation", "USD", "", "", "", 7)), TOL);
    }

    @Test
    void irIntraBucketUsesTenorCorrelation() {
        SimmParameters p = SimmParameters.builder().set("IR.delta.rw.1y", 60).set("IR.delta.rw.5y", 50)
                .set("IR.tenorRho.1y.5y", 0.7).set("IR.phi", 0.9).set("IR.gamma", 0.3).build();
        // WS = 6000 and -10000; K^2 = 36e6 + 1e8 - 2*0.7*6e7 = 52e6
        assertEquals(Math.sqrt(52e6), simm(p, ir("1y", "OIS", 100), ir("5y", "OIS", -200)), TOL);
        // different sub-curves: rho = 0.7 * 0.9 = 0.63
        assertEquals(Math.sqrt(136e6 - 2 * 0.63 * 6e7),
                simm(p, ir("1y", "OIS", 100), ir("5y", "Libor3m", -200)), 1e-6);
        // same tenor, different sub-curve: rho = phi; WS 5000 + 5000
        assertEquals(Math.sqrt(2 * 25e6 + 2 * 0.9 * 25e6),
                simm(p, ir("5y", "OIS", 100), ir("5y", "Libor3m", 100)), 1e-6);
    }

    @Test
    void sameKeyRowsAreNettedBeforeWeighting() {
        assertEquals(50.0 * 300, simm(ILL, ir("5y", "OIS", 1000), ir("5y", "OIS", -700)), TOL);
    }

    @Test
    void concentrationFactorScalesWeightedSensitivity() {
        SimmParameters p = SimmParameters.builder().from(ILL).set("Equity.delta.threshold", 100).build();
        // CR = sqrt(400/100) = 2
        assertEquals(30.0 * 400 * 2, simm(p, eq("A", "1", 400)), 1e-9);
        // below threshold: CR = 1
        assertEquals(30.0 * 50, simm(p, eq("A", "1", 50)), 1e-9);
        // two qualifiers, CR 2 and 1: f = 0.5, rho_diff = 0.2; WS = 24000, 3000
        double k2 = 24000.0 * 24000 + 3000.0 * 3000 + 2 * 0.2 * 0.5 * 24000 * 3000;
        assertEquals(Math.sqrt(k2), simm(p, eq("A", "1", 400), eq("B", "1", 100)), 1e-6);
    }

    @Test
    void crossBucketAggregationUsesGammaAndClampedS() {
        // two buckets, WS 300 each, gamma 0.15
        assertEquals(300 * Math.sqrt(2.3), simm(ILL, eq("A", "1", 10), eq("B", "2", 10)), 1e-9);
        // bucket 1 hedged inside (S_1 = 0), K_1 = 300 sqrt(1.6); bucket 2 K = S = 300
        double k1sq = 2 * 90000 - 2 * 0.2 * 90000;
        assertEquals(Math.sqrt(k1sq + 90000),
                simm(ILL, eq("A", "1", 10), eq("B", "1", -10), eq("C", "2", 10)), 1e-9);
        // S clamped to K: bucket with same-sign qualifiers has sum WS = 600 > K = 300 sqrt(2.4)
        double kb = 300 * Math.sqrt(2.4);
        double expected = Math.sqrt(kb * kb + 90000 + 2 * 0.15 * kb * 300);
        assertEquals(expected, simm(ILL, eq("A", "1", 10), eq("B", "1", 10), eq("C", "2", 10)), 1e-9);
    }

    @Test
    void residualBucketIsAddedNotCorrelated() {
        // Residual RW 60: 60*10 = 600 added to 300
        assertEquals(900, simm(ILL, eq("A", "1", 10), eq("Z", "Residual", 10)), 1e-9);
    }

    @Test
    void vegaAndCurvatureFromOneVegaRow() {
        CrifSensitivity v = row(SimmProductClass.RATES_FX, "Risk_IRVol", "USD", "USD", "1y", "", 1000);
        SimmResult.RiskClassMargin m = new SimmCalculator(ILL).calculate(List.of(v))
                .productClasses().get(SimmProductClass.RATES_FX)
                .riskClasses().get(SimmRiskClass.INTEREST_RATE);
        assertEquals(0, m.delta(), 0);
        assertEquals(0.2 * 1000, m.vega(), TOL);
        double cvr = 0.5 * 14.0 / 365.0 * 1000;
        // theta = 0, lambda = z^2 - 1, so curvature = z^2 * CVR
        double z = SimmCalculator.Z995;
        assertEquals(z * z * cvr, m.curvature(), 1e-9);
        // a single short-vega row: theta = -1, lambda = 1, max(-c + c, 0) = 0
        CrifSensitivity s = row(SimmProductClass.RATES_FX, "Risk_IRVol", "USD", "USD", "1y", "", -1000);
        assertEquals(0, new SimmCalculator(ILL).calculate(List.of(s)).productClasses()
                .get(SimmProductClass.RATES_FX).riskClasses().get(SimmRiskClass.INTEREST_RATE).curvature(), 1e-9);
    }

    @Test
    void curvatureScalingAndFormula() {
        assertEquals(0.5, SimmCalculator.scaling(SimmParameters.tenorYears("2w")), 1e-12);
        assertEquals(0.5 * 14 / 365.0, SimmCalculator.scaling(1.0), 1e-12);
        // two buckets of CVR +10 and -4, K = |CVR|; theta = min(6/14, 0) = 0
        double z = SimmCalculator.Z995;
        double root = Math.sqrt(100 + 16 + 2 * 0.0225 * 10 * -4);
        assertEquals(6 + (z * z - 1) * root, SimmCalculator.curvatureFormula(6, 14, root), 1e-12);
        // net short: theta = -4/14
        double th = -4.0 / 14;
        double lam = (z * z - 1) * (1 + th) - th;
        assertEquals(Math.max(-4 + lam * 5, 0), SimmCalculator.curvatureFormula(-4, 14, 5), 1e-12);
    }

    @Test
    void curvatureAcrossBucketsUsesGammaSquared() {
        CrifSensitivity a = row(SimmProductClass.EQUITY, "Risk_EquityVol", "A", "1", "2w", "", 20);
        CrifSensitivity b = row(SimmProductClass.EQUITY, "Risk_EquityVol", "B", "2", "2w", "", -8);
        SimmParameters p = SimmParameters.builder().from(ILL).set("Equity.vega.amountIsVolWeighted", 1).build();
        SimmResult.RiskClassMargin m = new SimmCalculator(p).calculate(List.of(a, b))
                .productClasses().get(SimmProductClass.EQUITY).riskClasses().get(SimmRiskClass.EQUITY);
        // CVR 10 and -4 (SF = 0.5); gamma^2 = 0.0225
        double root = Math.sqrt(100 + 16 + 2 * 0.0225 * 10 * -4);
        assertEquals(SimmCalculator.curvatureFormula(6, 14, root), m.curvature(), 1e-9);
        // vega: WS 6, -2.4 across buckets with gamma 0.15
        assertEquals(Math.sqrt(36 + 5.76 + 2 * 0.15 * 6 * -2.4), m.vega(), 1e-9);
    }

    @Test
    void baseCorrelationAggregatesAcrossQualifiers() {
        CrifSensitivity a = row(SimmProductClass.CREDIT, "Risk_BaseCorr", "CDX.IG", "", "", "", 3);
        CrifSensitivity b = row(SimmProductClass.CREDIT, "Risk_BaseCorr", "ITRAXX", "", "", "", -2);
        SimmResult.RiskClassMargin m = new SimmCalculator(ILL).calculate(List.of(a, b))
                .productClasses().get(SimmProductClass.CREDIT).riskClasses().get(SimmRiskClass.CREDIT_QUALIFYING);
        assertEquals(Math.sqrt(900 + 400 - 2 * 0.3 * 600), m.baseCorrelation(), 1e-9);
        assertEquals(m.baseCorrelation(), m.total(), 0);
    }

    @Test
    void productClassUsesPsiAndTotalSumsProductClasses() {
        CrifSensitivity irRow = ir("5y", "OIS", 10);                     // IM_IR = 500
        CrifSensitivity fx = row(SimmProductClass.RATES_FX, "Risk_FX", "EUR", "", "", "", 25); // IM_FX = 200
        CrifSensitivity e = eq("A", "1", 10);                              // Equity = 300
        SimmResult r = new SimmCalculator(ILL).calculate(List.of(irRow, fx, e));
        double ratesFx = Math.sqrt(500.0 * 500 + 200.0 * 200 + 2 * 0.3 * 500 * 200);
        assertEquals(ratesFx, r.productClasses().get(SimmProductClass.RATES_FX).margin(), 1e-9);
        assertEquals(ratesFx + 300, r.total(), 1e-9);
        // IR sensitivity booked under the Credit product class stays there
        CrifSensitivity irInCredit = row(SimmProductClass.CREDIT, "Risk_IRCurve", "USD", "USD", "5y", "OIS", 10);
        assertEquals(1000, simm(ILL, irRow, irInCredit), 1e-9);
    }

    @Test
    void positiveHomogeneityWithoutConcentration() {
        List<CrifSensitivity> base = mixedPortfolio(1.0);
        double m1 = new SimmCalculator(ILL).calculate(base).total();
        double m3 = new SimmCalculator(ILL).calculate(mixedPortfolio(3.0)).total();
        assertTrue(m1 > 0);
        assertEquals(3 * m1, m3, 1e-9 * m3);
        // sign flip changes curvature only (it is asymmetric); delta+vega margins are even
        SimmResult neg = new SimmCalculator(ILL).calculate(mixedPortfolio(-1.0));
        SimmResult pos = new SimmCalculator(ILL).calculate(base);
        for (SimmProductClass p : pos.productClasses().keySet()) {
            pos.productClasses().get(p).riskClasses().forEach((rc, m) -> {
                SimmResult.RiskClassMargin n = neg.productClasses().get(p).riskClasses().get(rc);
                assertEquals(m.delta(), n.delta(), 1e-9);
                assertEquals(m.vega(), n.vega(), 1e-9);
            });
        }
    }

    @Test
    void subadditiveForOffsettingPortfolios() {
        List<CrifSensitivity> both = new ArrayList<>(mixedPortfolio(1.0));
        both.addAll(mixedPortfolio(-1.0));
        assertEquals(0, new SimmCalculator(ILL).calculate(both).total(), 1e-9);
    }

    private static List<CrifSensitivity> mixedPortfolio(double k) {
        return List.of(
                ir("1y", "OIS", 120 * k), ir("10y", "Libor3m", -80 * k),
                row(SimmProductClass.RATES_FX, "Risk_IRCurve", "EUR", "EUR", "2y", "OIS", 50 * k),
                row(SimmProductClass.RATES_FX, "Risk_IRVol", "USD", "USD", "5y", "", 400 * k),
                row(SimmProductClass.RATES_FX, "Risk_FX", "JPY", "", "", "", 1000 * k),
                row(SimmProductClass.CREDIT, "Risk_CreditQ", "ISS1", "2", "5y", "", 30 * k),
                row(SimmProductClass.CREDIT, "Risk_CreditQ", "ISS2", "3", "3y", "", -10 * k),
                row(SimmProductClass.CREDIT, "Risk_BaseCorr", "CDX.IG", "", "", "", 4 * k),
                row(SimmProductClass.CREDIT, "Risk_CreditNonQ", "RMBS", "1", "5y", "", 2 * k),
                eq("A", "1", 100 * k), eq("B", "5", -60 * k),
                row(SimmProductClass.EQUITY, "Risk_EquityVol", "A", "1", "6m", "", 200 * k),
                row(SimmProductClass.COMMODITY, "Risk_Commodity", "WTI", "2", "", "", 70 * k));
    }

    @Test
    void crifCsvAndParameterFileRoundTrip() throws IOException {
        String csv = "TradeID,ProductClass,RiskType,Qualifier,Bucket,Label1,Label2,AmountUSD\n"
                + "T1,RatesFX,Risk_IRCurve,USD,,5y,OIS,1000\n"
                + "\n"
                + "T2,Equity,Risk_Equity,ABC,1,,,7\n";
        List<CrifSensitivity> rows = CrifSensitivity.parseCsv(new StringReader(csv));
        assertEquals(2, rows.size());
        assertEquals("USD", rows.get(0).effectiveBucket());
        assertEquals(50000 + 210, new SimmCalculator(ILL).calculate(rows).total(), 1e-9);

        SimmParameters back = SimmParameters.parse(new StringReader(ILL.format()));
        assertEquals(ILL.values(), back.values());
        assertEquals(ILL.label(), back.label());
        assertTrue(ILL.label().startsWith("ILLUSTRATIVE"));
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> row(SimmProductClass.EQUITY, "Risk_Nope", "A", "1", "", "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> simm(SimmParameters.builder().build(), eq("A", "1", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> SimmParameters.parse(new StringReader("IR.phi = abc")));
        assertThrows(IllegalArgumentException.class,
                () -> CrifSensitivity.parseCsv(new StringReader("RiskType,Amount\nRisk_FX,1")));
    }
}

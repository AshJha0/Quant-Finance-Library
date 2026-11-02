package com.quantfinlib.risk.simm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Structural checks against the formulas of the public ISDA SIMM
 * v2.9+2606 methodology (sections B, D, I, L), using the ILLUSTRATIVE
 * calibration plus round numbers set in each test. No ISDA calibration
 * numbers are used. Evidence class: own test, hand computation.
 */
class SimmStructureTest {

    private static final SimmParameters ILL = SimmParameters.illustrative();
    private static final double Z = SimmCalculator.Z995;
    private static final double VOL = Math.sqrt(365.0 / 14.0) / SimmCalculator.Z99;

    private static CrifSensitivity row(SimmProductClass p, String type, String q, String b,
                                       String l1, String l2, double amt) {
        return new CrifSensitivity(p, type, q, b, l1, l2, amt);
    }

    private static SimmResult.RiskClassMargin margin(SimmParameters p, SimmProductClass pc,
                                                     SimmRiskClass rc, CrifSensitivity... rows) {
        return new SimmCalculator(p).calculate(List.of(rows)).productClasses().get(pc).riskClasses().get(rc);
    }

    private static SimmParameters with(Object... kv) {
        SimmParameters.Builder b = SimmParameters.builder().from(ILL);
        for (int i = 0; i < kv.length; i += 2) {
            b.set((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return b.build();
    }

    @Test
    void irCrossCurrencyBasisIsOutsideConcentrationAndFklIsOne() {
        // B.7(b): CR from yields only (1000 / 250 = 4, CR = 2); XCcy not scaled; D.2 xccy rho
        SimmParameters p = with("IR.delta.threshold", 250);
        double ws = 50.0 * 1000 * 2;
        double wx = 20.0 * 100;
        double expected = Math.sqrt(ws * ws + wx * wx + 2 * -0.1 * ws * wx);
        assertEquals(expected, margin(p, SimmProductClass.RATES_FX, SimmRiskClass.INTEREST_RATE,
                row(SimmProductClass.RATES_FX, "Risk_IRCurve", "USD", "USD", "5y", "OIS", 1000),
                row(SimmProductClass.RATES_FX, "Risk_XCcyBasis", "USD", "USD", "", "", 100)).delta(), 1e-6);
    }

    @Test
    void inflationVolCorrelatesWithIrVolThroughInflationRho() {
        double v = 0.2 * Math.sqrt(2e6 + 2 * 0.3 * 1e6);
        assertEquals(v, margin(ILL, SimmProductClass.RATES_FX, SimmRiskClass.INTEREST_RATE,
                row(SimmProductClass.RATES_FX, "Risk_IRVol", "USD", "USD", "1y", "", 1000),
                row(SimmProductClass.RATES_FX, "Risk_InflationVol", "USD", "USD", "1y", "", 1000)).vega(), 1e-6);
    }

    @Test
    void equityVegaUsesSigmaFromDeltaWeightAndHvrButCurvatureDoesNotUseHvr() {
        // B.10(b,c): sigma = RW sqrt(365/14) / Phi^-1(0.99); VR = VRW * HVR * sigma * vega
        SimmParameters p = with("Equity.hvr", 0.5);
        SimmResult.RiskClassMargin m = margin(p, SimmProductClass.EQUITY, SimmRiskClass.EQUITY,
                row(SimmProductClass.EQUITY, "Risk_EquityVol", "A", "1", "1y", "", 10));
        double sigma = 30 * VOL;
        assertEquals(0.3 * 0.5 * sigma * 10, m.vega(), 1e-9);
        // B.11(a): CVR = SF * sigma * vega, single positive CVR gives z^2 CVR
        assertEquals(Z * Z * SimmCalculator.scaling(1.0) * sigma * 10, m.curvature(), 1e-9);
    }

    @Test
    void equityVegaRiskFactorSumsAcrossExpiries() {
        SimmResult.RiskClassMargin m = margin(ILL, SimmProductClass.EQUITY, SimmRiskClass.EQUITY,
                row(SimmProductClass.EQUITY, "Risk_EquityVol", "A", "1", "1y", "", 10),
                row(SimmProductClass.EQUITY, "Risk_EquityVol", "A", "1", "2y", "", -10));
        assertEquals(0, m.vega(), 1e-9);
        // curvature nets with SF weights, not before (B.11(a))
        double cvr = 30 * VOL * 10 * (SimmCalculator.scaling(1.0) - SimmCalculator.scaling(2.0));
        assertEquals(Z * Z * cvr, m.curvature(), 1e-9);
    }

    @Test
    void irCurvatureIsScaledByInverseSquaredHvr() {
        SimmParameters p = with("IR.hvr", 0.5);
        double cvr = SimmCalculator.scaling(1.0) * 1000;
        assertEquals(4 * Z * Z * cvr, margin(p, SimmProductClass.RATES_FX, SimmRiskClass.INTEREST_RATE,
                row(SimmProductClass.RATES_FX, "Risk_IRVol", "USD", "USD", "1y", "", 1000)).curvature(), 1e-9);
    }

    @Test
    void zeroCurvatureBucketIsExcluded() {
        SimmParameters p = with("Equity.curvature.zeroBucket.12", 1);
        SimmResult.RiskClassMargin m = margin(p, SimmProductClass.EQUITY, SimmRiskClass.EQUITY,
                row(SimmProductClass.EQUITY, "Risk_EquityVol", "VIX", "12", "1m", "", 10));
        assertEquals(0, m.curvature(), 0);
        assertEquals(0.3 * 30 * VOL * 10, m.vega(), 1e-9);
    }

    @Test
    void fxDeltaUsesVolGroupsCalculationCurrencyAndCategories() {
        SimmParameters p = with("FX.calculationCurrency.USD", 1, "FX.highVolGroup.KRW", 1,
                "FX.delta.rw.Regular.Regular", 7, "FX.delta.rw.High.Regular", 11,
                "FX.rho.Regular.Regular.High", 0.3, "FX.category.EUR", 1, "FX.delta.threshold.cat1", 2.5);
        // EUR: CR = sqrt(10 / 2.5) = 2; KRW: category 3, falls back to FX.delta.threshold; USD weight 0
        double we = 7 * 10 * 2;
        double wk = 11 * 10;
        double expected = Math.sqrt(we * we + wk * wk + 2 * 0.3 * 0.5 * we * wk);
        assertEquals(expected, margin(p, SimmProductClass.RATES_FX, SimmRiskClass.FX,
                row(SimmProductClass.RATES_FX, "Risk_FX", "EUR", "", "", "", 10),
                row(SimmProductClass.RATES_FX, "Risk_FX", "KRW", "", "", "", 10),
                row(SimmProductClass.RATES_FX, "Risk_FX", "USD", "", "", "", 10)).delta(), 1e-9);
    }

    @Test
    void fxVegaUsesPairGroupWeightAndFlatVegaCorrelation() {
        SimmParameters p = with("FX.highVolGroup.KRW", 1, "FX.delta.rw.Regular.Regular", 7,
                "FX.delta.rw.Regular.High", 11, "FX.rho.vega", 0.5);
        double a = 0.3 * 7 * VOL * 10;
        double b = 0.3 * 11 * VOL * 10;
        assertEquals(Math.sqrt(a * a + b * b + 2 * 0.5 * a * b), margin(p, SimmProductClass.RATES_FX, SimmRiskClass.FX,
                row(SimmProductClass.RATES_FX, "Risk_FXVol", "EURUSD", "", "1y", "", 10),
                row(SimmProductClass.RATES_FX, "Risk_FXVol", "USDKRW", "", "1y", "", 10)).vega(), 1e-9);
    }

    @Test
    void baseCorrelationWeightCanDependOnFamily() {
        SimmParameters p = with("CreditQ.baseCorr.rw.CDX.IG", 20);
        assertEquals(Math.sqrt(40000 + 10000 + 2 * 0.3 * 200 * 100), margin(p, SimmProductClass.CREDIT,
                SimmRiskClass.CREDIT_QUALIFYING,
                row(SimmProductClass.CREDIT, "Risk_BaseCorr", "CDX.IG", "", "", "", 10),
                row(SimmProductClass.CREDIT, "Risk_BaseCorr", "ITRAXX", "", "", "", 10)).baseCorrelation(), 1e-9);
    }

    @Test
    void addOnsFollowSectionL() {
        SimmParameters p = with("addon.fixed", 100, "addon.factor.Swaption", 0.01, "multiplier.RatesFX", 1.5);
        List<CrifSensitivity> crif = List.of(
                row(SimmProductClass.RATES_FX, "Risk_IRCurve", "USD", "USD", "5y", "OIS", 1000));
        SimmAddOnResult r = new SimmCalculator(p).calculateWithAddOns(crif, Map.of("Swaption", -1e5));
        assertEquals(50000, r.simm().total(), 1e-9);
        assertEquals(100 + 1000 + 0.5 * 50000, r.additionalInitialMargin(), 1e-9);
        assertEquals(50000 + 26100, r.total(), 1e-9);
        assertEquals(50000, new SimmCalculator(ILL).calculateWithAddOns(crif, Map.of()).total(), 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> new SimmCalculator(ILL).calculateWithAddOns(crif, Map.of("Swaption", 1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> new SimmCalculator(with("multiplier.RatesFX", 0.9)).calculateWithAddOns(crif, Map.of()));
    }
}

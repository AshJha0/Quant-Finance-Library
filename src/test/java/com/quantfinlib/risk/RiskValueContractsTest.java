package com.quantfinlib.risk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Value semantics of the hand-written result classes in this package
 * (equals, hashCode, toString, defensive copies), the stylised stress
 * scenarios, the closed-form reverse stress worked by hand, and the
 * {@link Portfolio} book-keeping. Hashes are pinned to the fold
 * {@code h = 31 h + field} from 0.
 */
class RiskValueContractsTest {

    static int fold(int... parts) {
        int h = 0;
        for (int p : parts) {
            h = 31 * h + p;
        }
        return h;
    }

    private static void assertValueContract(Object a, Object same, Object... different) {
        assertEquals(a, a);
        assertEquals(a, same);
        assertEquals(same, a);
        assertEquals(a.hashCode(), same.hashCode());
        assertEquals(a.toString(), same.toString());
        assertFalse(a.equals(null));
        assertFalse(a.equals("not a value"));
        for (Object d : different) {
            assertNotEquals(a, d, d.toString());
            assertNotEquals(a.hashCode(), d.hashCode(), d.toString());
        }
    }

    @Test
    void componentVarAllocation() {
        double[] c = {3, -1};
        double[] m = {0.5, 0.25};
        ComponentVar.Allocation a = new ComponentVar.Allocation(2, c, m);
        assertValueContract(a, new ComponentVar.Allocation(2, c.clone(), m.clone()),
                new ComponentVar.Allocation(3, c, m),
                new ComponentVar.Allocation(2, m, m),
                new ComponentVar.Allocation(2, c, c));
        assertEquals(fold(Double.hashCode(2), Arrays.hashCode(c), Arrays.hashCode(m)), a.hashCode());
        assertEquals("Allocation[portfolioVar=2.0, components=[3.0, -1.0], marginals=[0.5, 0.25]]", a.toString());
        assertEquals(2, a.componentsLength());
        assertEquals(2, a.marginalsLength());
        assertArrayEquals(c, a.componentsCopy());
        assertArrayEquals(m, a.marginalsCopy());
        a.componentsCopy()[0] = 99;
        a.marginalsCopy()[0] = 99;
        assertEquals(3, a.components(0));
        assertEquals(0.5, a.marginals(0));
        ComponentVar.Allocation n = new ComponentVar.Allocation(1, null, null);
        assertNull(n.componentsCopy());
        assertNull(n.marginalsCopy());
    }

    @Test
    void shrinkageResult() {
        double[][] mtx = {{1, 0.2}, {0.2, 2}};
        CovarianceShrinkage.Result a = new CovarianceShrinkage.Result(mtx, 0.3, 1.5);
        assertValueContract(a, new CovarianceShrinkage.Result(new double[][]{{1, 0.2}, {0.2, 2}}, 0.3, 1.5),
                new CovarianceShrinkage.Result(new double[][]{{1, 0.3}, {0.3, 2}}, 0.3, 1.5),
                new CovarianceShrinkage.Result(mtx, 0.4, 1.5),
                new CovarianceShrinkage.Result(mtx, 0.3, 1.6));
        assertEquals(fold(Arrays.deepHashCode(mtx), Double.hashCode(0.3), Double.hashCode(1.5)), a.hashCode());
        assertEquals("Result[matrix=[[1.0, 0.2], [0.2, 2.0]], intensity=0.3, target=1.5]", a.toString());
        assertEquals(2, a.matrixRows());
        assertArrayEquals(mtx[1], a.matrixCopy()[1]);
    }

    @Test
    void eigenValue() {
        double[] v = {3, 1};
        double[][] vec = {{1, 0}, {0, 1}};
        PsdMatrices.Eigen a = new PsdMatrices.Eigen(v, vec);
        assertValueContract(a, new PsdMatrices.Eigen(v.clone(), new double[][]{{1, 0}, {0, 1}}),
                new PsdMatrices.Eigen(new double[]{3, 2}, vec),
                new PsdMatrices.Eigen(v, new double[][]{{0, 1}, {1, 0}}));
        assertEquals(fold(Arrays.hashCode(v), Arrays.deepHashCode(vec)), a.hashCode());
        assertEquals("Eigen[values=[3.0, 1.0], vectors=[[1.0, 0.0], [0.0, 1.0]]]", a.toString());
        assertEquals(2, a.valuesLength());
        assertEquals(2, a.vectorsRows());
        assertArrayEquals(vec[0], a.vectorsCopy()[0]);
    }

    @Test
    void reverseStressValueAndClosedForm() {
        double[] s = {-0.1, 0.02};
        StressTester.ReverseStress a = new StressTester.ReverseStress(s, 2.5);
        assertValueContract(a, new StressTester.ReverseStress(s.clone(), 2.5),
                new StressTester.ReverseStress(new double[]{-0.1, 0.03}, 2.5),
                new StressTester.ReverseStress(s, 2.6));
        assertEquals(fold(Arrays.hashCode(s), Double.hashCode(2.5)), a.hashCode());
        assertEquals("ReverseStress[shocks=[-0.1, 0.02], mahalanobisSigmas=2.5]", a.toString());
        assertEquals(2, a.shocksLength());
        assertArrayEquals(s, a.shocksCopy());

        // Diagonal Σ = diag(4, 1), δ = (1, 2): σ_P² = 4 + 4 = 8, Σδ = (4, 2).
        // Loss 4: shocks = -4/8 · (4, 2) = (-2, -1); Mahalanobis = 4/√8.
        StressTester.ReverseStress r = StressTester.reverseStress(new double[]{1, 2},
                new double[][]{{4, 0}, {0, 1}}, 4);
        assertArrayEquals(new double[]{-2, -1}, r.shocksCopy(), 1e-14);
        assertEquals(4 / Math.sqrt(8), r.mahalanobisSigmas(), 1e-14);
        assertEquals(-4, StressTester.scenarioPnl(new double[]{1, 2}, r.shocksCopy()), 1e-14);
        // Off-diagonal term enters Σδ.
        StressTester.ReverseStress q = StressTester.reverseStress(new double[]{1, 1},
                new double[][]{{1, 0.5}, {0.5, 1}}, 3);
        assertArrayEquals(new double[]{-1.5, -1.5}, q.shocksCopy(), 1e-14);
        assertEquals(3 / Math.sqrt(3), q.mahalanobisSigmas(), 1e-14);
        assertThrows(IllegalArgumentException.class,
                () -> StressTester.reverseStress(new double[]{1}, new double[][]{{1}}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StressTester.reverseStress(new double[]{1}, new double[][]{{1}}, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> StressTester.reverseStress(new double[]{0}, new double[][]{{1}}, 1));
    }

    @Test
    void stylisedScenarios() {
        assertArrayEquals(new double[]{-0.20, -0.0050, 0.02, -0.05, 0.20}, StressTester.blackMonday1987());
        assertArrayEquals(new double[]{-0.09, -0.0040, 0.04, -0.07, 0.16}, StressTester.lehman2008());
        assertArrayEquals(new double[]{-0.12, -0.0030, 0.05, -0.15, 0.25}, StressTester.covidMarch2020());
    }

    @Test
    void riskReportValue() {
        Map<String, Double> e = new LinkedHashMap<>(Map.of("A", 0.6));
        Map<String, Double> v = Map.of("A", 0.2);
        Map<String, Double> var = Map.of("A", 0.03);
        Map<String, Double> rc = Map.of("A", 1.0);
        double[][] corr = {{1}};
        PortfolioRiskAnalyzer.RiskReport a = new PortfolioRiskAnalyzer.RiskReport(
                0.2, 0.03, 0.04, 0.035, 1.1, e, v, var, rc, corr);
        Map<String, Double> other = Map.of("B", 9.0);
        assertValueContract(a,
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.1, e, v, var, rc, new double[][]{{1}}),
                new PortfolioRiskAnalyzer.RiskReport(0.3, 0.03, 0.04, 0.035, 1.1, e, v, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.05, 0.04, 0.035, 1.1, e, v, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.06, 0.035, 1.1, e, v, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.036, 1.1, e, v, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.2, e, v, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.1, other, v, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.1, e, other, var, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.1, e, v, other, rc, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.1, e, v, var, other, corr),
                new PortfolioRiskAnalyzer.RiskReport(0.2, 0.03, 0.04, 0.035, 1.1, e, v, var, rc, new double[][]{{2}}));
        assertEquals(fold(Double.hashCode(0.2), Double.hashCode(0.03), Double.hashCode(0.04),
                Double.hashCode(0.035), Double.hashCode(1.1), e.hashCode(), v.hashCode(), var.hashCode(),
                rc.hashCode(), Arrays.deepHashCode(corr)), a.hashCode());
        assertEquals("RiskReport[annualizedVolatility=0.2, valueAtRisk=0.03, conditionalValueAtRisk=0.04, "
                + "parametricVaR=0.035, sharpeRatio=1.1, exposures={A=0.6}, assetVolatilities={A=0.2}, "
                + "assetVaR={A=0.03}, riskContributions={A=1.0}, correlationMatrix=[[1.0]]]", a.toString());
        assertEquals(0.035, a.parametricVaR());
        assertEquals(1.1, a.sharpeRatio());
        assertEquals(e, a.exposures());
        assertEquals(v, a.assetVolatilities());
        assertEquals(var, a.assetVaR());
        assertEquals(1, a.correlationMatrixRows());
        assertEquals(1.0, a.correlationMatrixCopy()[0][0]);
        e.put("A", 5.0);
        assertEquals(0.6, a.exposures().get("A"), "constructor copies its maps");
        assertThrows(UnsupportedOperationException.class, () -> a.exposures().put("Z", 1.0));
    }

    @Test
    void portfolioBookKeeping() {
        Portfolio p = new Portfolio();
        assertEquals(0, p.totalValue());
        assertEquals(0, p.size());
        p.addPosition("A", 10, 5).addPosition("B", 20, 2.5);
        assertEquals(2, p.size());
        assertEquals(100, p.totalValue(), 1e-12);
        assertEquals(0.5, p.weights().get("A"), 1e-12);
        assertEquals(0.5, p.weights().get("B"), 1e-12);
        // Adding to an existing symbol sums quantity and takes the new price.
        p.addPosition("A", 5, 6);
        assertEquals(15, p.position("A").quantity());
        assertEquals(6, p.position("A").price());
        assertEquals(90, p.position("A").value(), 1e-12);
        assertEquals(140, p.totalValue(), 1e-12);
        p.updatePrice("B", 3);
        assertEquals(60, p.position("B").value(), 1e-12);
        assertEquals(20, p.position("B").quantity());
        p.updatePrice("ZZZ", 1);
        assertNull(p.position("ZZZ"));
        assertEquals(2, p.positions().size());
        assertEquals(90.0 / 150, p.weights().get("A"), 1e-12);
        // Long-short book netting to zero gives zero weights rather than NaN.
        Portfolio flat = new Portfolio().addPosition("L", 1, 10).addPosition("S", -1, 10);
        assertEquals(0.0, flat.weights().get("L"));
        assertEquals(0.0, flat.weights().get("S"));
    }
}

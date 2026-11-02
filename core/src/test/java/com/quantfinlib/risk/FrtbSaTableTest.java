package com.quantfinlib.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Table-driven checks of the FRTB SA supervisory parameters against tables transcribed by
 * hand from the BCBS consolidated Basel Framework (MAR21 and MAR22). Each CSV under
 * {@code src/test/resources/frtb} names the paragraph, table and page of every number.
 * Evidence class: externally anchored (the printed tables), except where a test says
 * otherwise.
 *
 * <p>MAR21 prints no worked capital calculation, only two worked correlations (footnote 284
 * for GIRR and footnote 292 for commodities); those are pinned in
 * {@link FrtbSaParametersTest}. The bucket-level example on {@link FrtbSa} is therefore an
 * independent recomputation, and is recomputed here from the printed Table 1 and Table 2
 * values rather than from the library's own formula.</p>
 */
class FrtbSaTableTest {

    private static final double[] TENORS = {0.25, 0.5, 1, 2, 3, 5, 10, 15, 20, 30};

    private static List<String[]> rows(String name) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(
                FrtbSaTableTest.class.getResourceAsStream("/frtb/" + name), name);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                out.add(line.split(",", 4));
            }
        }
        return out;
    }

    private static double pct(String s) {
        return Double.parseDouble(s.trim()) / 100.0;
    }

    @Test
    void girrTenorCorrelationMatchesMar21Table2() throws IOException {
        List<String[]> t = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(FrtbSaTableTest.class
                .getResourceAsStream("/frtb/mar21_table2_girr_tenor_correlation.csv"));
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    t.add(line.split(","));
                }
            }
        }
        assertEquals(10, t.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(TENORS[i], Double.parseDouble(t.get(i)[0]), 0);
            for (int j = 0; j < 10; j++) {
                double printed = pct(t.get(i)[j + 1]);
                // printed to 0.1 percentage point, so half of that is the tolerance
                assertEquals(printed, FrtbSa.Girr.tenorCorrelation(TENORS[i], TENORS[j]), 0.0005 + 1e-12,
                        "MAR21.46 Table 2 row " + TENORS[i] + " col " + TENORS[j]);
                // the printed table is symmetric
                assertEquals(printed, pct(t.get(j)[i + 1]), 0);
            }
        }
    }

    @Test
    void riskWeightsMatchMar21AndMar22Tables() throws IOException {
        int n = 0;
        for (String[] r : rows("mar21_mar22_risk_weights.csv")) {
            String table = r[0];
            String key = r[1];
            double printed = pct(r[2]);
            String cite = r[3];
            double actual = switch (table) {
                case "girr" -> FrtbSa.Girr.RISK_WEIGHTS[indexOf(Double.parseDouble(key))];
                case "csr" -> FrtbSa.Csr.RW[Integer.parseInt(key) - 1];
                case "eq_spot" -> FrtbSa.Equity.SPOT_RW[Integer.parseInt(key) - 1];
                case "eq_repo" -> FrtbSa.Equity.REPO_RW[Integer.parseInt(key) - 1];
                case "cmdty" -> FrtbSa.Commodity.RW[Integer.parseInt(key) - 1];
                case "cmdty_rho" -> FrtbSa.Commodity.RHO_CTY[Integer.parseInt(key) - 1];
                case "drc" -> FrtbDrc.CreditQuality.valueOf(key).riskWeight();
                case "lgd" -> switch (key) {
                    case "SENIOR" -> FrtbDrc.LGD_SENIOR;
                    case "NON_SENIOR" -> FrtbDrc.LGD_NON_SENIOR;
                    case "EQUITY" -> FrtbDrc.LGD_EQUITY;
                    case "COVERED_BOND" -> FrtbDrc.LGD_COVERED_BOND;
                    default -> throw new IllegalArgumentException(key);
                };
                default -> throw new IllegalArgumentException(table);
            };
            assertEquals(printed, actual, 1e-12, table + " " + key + " (" + cite + ")");
            n++;
        }
        // 10 + 18 + 13 + 13 + 11 + 11 + 9 + 4 rows transcribed
        assertEquals(89, n);
    }

    @Test
    void csrInterBucketGammaMatchesMar21Table5() throws IOException {
        int n = 0;
        for (String[] r : rows("mar21_table5_csr_sector_gamma.csv")) {
            int b = Integer.parseInt(r[0].trim());
            int c = Integer.parseInt(r[1].trim());
            double sector = pct(r[2]);
            assertEquals(sector, FrtbSa.Csr.gamma(b, c), 1e-12, "Table 5 " + b + "/" + c);
            assertEquals(sector, FrtbSa.Csr.gamma(c, b), 1e-12, "symmetry " + b + "/" + c);
            // "1 / 9" rows: buckets 9-15 share the sector of 1-7; IG against HY/NR takes
            // gamma_rating = 50% on top (MAR21.57(1)); HY against HY does not
            if (b <= 7 && c <= 7) {
                assertEquals(0.5 * sector, FrtbSa.Csr.gamma(b, c + 8), 1e-12, "IG/HY " + b + "/" + (c + 8));
                assertEquals(sector, FrtbSa.Csr.gamma(b + 8, c + 8), 1e-12, "HY/HY " + (b + 8) + "/" + (c + 8));
            }
            n++;
        }
        assertEquals(55, n);
        // same sector, IG against HY: gamma_sector = 1, gamma_rating = 50% (MAR21.57(1)-(2))
        assertEquals(0.5, FrtbSa.Csr.gamma(1, 9), 1e-12);
    }

    /**
     * Independent recomputation (not a BCBS worked number: MAR21 prints none) of the
     * {@link FrtbSa} class example, using the printed, rounded Table 1 risk weights and the
     * printed Table 2 correlation 97.0% between 1y and 2y. The library uses the unrounded
     * formula exp(-3%) = 0.970446; the two agree to the rounding of the table.
     */
    @Test
    void classExampleRecomputedFromPrintedTables() {
        double ws1 = 100 * 0.016;   // MAR21.42 Table 1, 1 year
        double ws2 = -50 * 0.013;   // MAR21.42 Table 1, 2 year
        double rhoPrinted = 0.970;  // MAR21.46 Table 2, 1y x 2y
        double kPrinted = Math.sqrt(ws1 * ws1 + ws2 * ws2 + 2 * rhoPrinted * ws1 * ws2);
        FrtbSa.BucketResult lib = FrtbSa.bucket(FrtbSa.Bucket.of(new double[] {100, -50},
                new double[] {FrtbSa.Girr.RISK_WEIGHTS[2], FrtbSa.Girr.RISK_WEIGHTS[3]},
                FrtbSa.Girr.tenorMatrix(new double[] {1, 2})), FrtbSa.Scenario.MEDIUM);
        assertEquals(0.98182, lib.k(), 5e-6);
        // the 0.05 pp rounding of rho moves K by |ws1*ws2/K|*0.0005 at most
        assertEquals(kPrinted, lib.k(), Math.abs(ws1 * ws2 / kPrinted) * 0.0005 + 1e-12);
        assertTrue(lib.s() <= lib.k());
    }

    private static int indexOf(double tenor) {
        for (int i = 0; i < TENORS.length; i++) {
            if (TENORS[i] == tenor) {
                return i;
            }
        }
        throw new IllegalArgumentException("tenor " + tenor);
    }
}

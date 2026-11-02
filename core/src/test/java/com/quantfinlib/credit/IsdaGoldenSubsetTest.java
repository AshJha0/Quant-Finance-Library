package com.quantfinlib.credit;

import com.quantfinlib.credit.IsdaCdsModel.Contract;
import com.quantfinlib.credit.IsdaCdsModel.Conventions;
import com.quantfinlib.credit.IsdaRateCurve.Swap;
import com.quantfinlib.rates.BusinessCalendar;
import com.quantfinlib.rates.HolidayCalendars;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The ISDA CDS model check that runs on every pull request. The ISDA RFR test
 * grids are copyright and nothing from them is committed: the nightly job
 * fetches them (SHA-256 pinned in {@code scripts/reference-files.sha256}) and
 * {@link IsdaGridVerificationTest} ties the model to ISDA's expected values.
 *
 * <p>This test is a regression set on inputs we made up ourselves: two
 * invented swap curves, invented trade dates, and a sweep of maturities,
 * coupons, quoted spreads and recoveries. The committed file
 * {@code isda-regression-set.csv} holds this library's clean upfront and cash
 * settlement for each contract, computed with the conventions of the grid test
 * (half-day accrual on; weekends-only and New York calendars). Evidence class:
 * self-consistency. A change in the model's numbers fails here on the pull
 * request; the nightly grid test says whether the new numbers are right.</p>
 *
 * <p>Regenerate (no grids needed) only after a deliberate change that the
 * nightly grid test accepts: {@code ./mvnw -pl core test
 * -Dtest=IsdaGoldenSubsetTest#writeGolden -Dqfl.isda.writeGolden=<absolute path
 * of isda-regression-set.csv>}.</p>
 */
class IsdaGoldenSubsetTest {

    static final String RESOURCE = "isda-regression-set.csv";

    /** One invented market: trade date, calendar and swap curve (our rates, not ISDA's). */
    record Scenario(String name, LocalDate trade, BusinessCalendar cal, List<Swap> swaps) {
    }

    static List<Scenario> scenarios() {
        String[] tenors = {"1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y", "30Y"};
        double[] upward = {0.0300, 0.0310, 0.0320, 0.0330, 0.0340, 0.0350, 0.0365, 0.0375, 0.0385, 0.0395,
            0.0400, 0.0405};
        double[] inverted = {0.0520, 0.0515, 0.0505, 0.0490, 0.0460, 0.0440, 0.0420, 0.0410, 0.0405, 0.0405,
            0.0400, 0.0395};
        List<Swap> up = new ArrayList<>();
        List<Swap> inv = new ArrayList<>();
        for (int i = 0; i < tenors.length; i++) {
            up.add(new Swap(tenors[i], upward[i]));
            inv.add(new Swap(tenors[i], inverted[i]));
        }
        return List.of(
                new Scenario("UPWARD_WEEKENDS", LocalDate.of(2025, 3, 12), BusinessCalendar.weekendsOnly(), up),
                new Scenario("INVERTED_NY", LocalDate.of(2024, 7, 3),
                        HolidayCalendars.calendar(HolidayCalendars.Market.NEW_YORK_FED, 2020, 2060), inv));
    }

    record Case(String scenario, LocalDate maturity, double couponBp, double quoteBp, double recovery,
                double upfront, double cashSettle) {
    }

    static double[] compute(Scenario s, LocalDate maturity, double couponBp, double quoteBp, double recovery,
                            IsdaCurve disc) {
        Contract c = IsdaGridVerificationTest.contract(s.trade(), maturity, couponBp * 1e-4, recovery, s.cal());
        Conventions conv = new Conventions(s.cal(), true);
        double u = IsdaCdsModel.quotedSpreadToUpfront(c, quoteBp * 1e-4, disc, conv);
        double accrued = IsdaCdsModel.accruedYearFraction(c) * couponBp * 1e-4;
        return new double[] {u, IsdaGridVerificationTest.NOTIONAL * (u - accrued)};
    }

    static List<Case> load() throws IOException {
        List<Case> out = new ArrayList<>();
        try (InputStream in = IsdaGoldenSubsetTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE);
            for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(",");
                out.add(new Case(f[0], LocalDate.parse(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6])));
            }
        }
        return out;
    }

    @Test
    void reproducesPinnedOutputs() throws IOException {
        List<Case> cases = load();
        assertEquals(48, cases.size());
        for (Scenario s : scenarios()) {
            IsdaCurve disc = IsdaRateCurve.bootstrapSwaps(s.trade(), s.swaps(), s.cal());
            for (Case k : cases) {
                if (k.scenario().equals(s.name())) {
                    double[] out = compute(s, k.maturity(), k.couponBp(), k.quoteBp(), k.recovery(), disc);
                    assertEquals(k.upfront(), out[0], 1e-13, k.toString());
                    assertEquals(k.cashSettle(), out[1], 1e-6, k.toString());
                }
            }
        }
    }

    /** Writes the regression set; runs only with -Dqfl.isda.writeGolden=FILE. */
    @Test
    void writeGolden() throws IOException {
        String target = System.getProperty("qfl.isda.writeGolden");
        Assumptions.assumeTrue(target != null, "qfl.isda.writeGolden not set");
        StringBuilder sb = new StringBuilder();
        sb.append("# Invented contracts and curves (IsdaGoldenSubsetTest.scenarios); nothing from the ISDA grids.\n")
                .append("# upfront and cashSettle (10mm notional) are this library's outputs.\n")
                .append("# scenario,maturity,couponBp,quoteBp,recovery,upfront,cashSettle\n");
        int[] years = {1, 3, 5, 10};
        double[][] couponQuote = {{100, 35}, {100, 240}, {500, 180}};
        double[] recoveries = {0.25, 0.40};
        for (Scenario s : scenarios()) {
            IsdaCurve disc = IsdaRateCurve.bootstrapSwaps(s.trade(), s.swaps(), s.cal());
            for (int y : years) {
                LocalDate maturity = LocalDate.of(s.trade().getYear() + y, 12, 20);
                for (double[] cq : couponQuote) {
                    for (double r : recoveries) {
                        double[] out = compute(s, maturity, cq[0], cq[1], r, disc);
                        sb.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%s,%s%n", s.name(), maturity,
                                cq[0], cq[1], r, out[0], out[1]));
                    }
                }
            }
        }
        Files.writeString(Path.of(target), sb.toString().replace("\r\n", "\n"));
    }
}

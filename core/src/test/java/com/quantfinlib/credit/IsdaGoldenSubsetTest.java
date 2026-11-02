package com.quantfinlib.credit;

import com.quantfinlib.credit.IsdaCdsModel.Contract;
import com.quantfinlib.credit.IsdaCdsModel.Conventions;
import com.quantfinlib.credit.IsdaRateCurve.Swap;
import com.quantfinlib.rates.BusinessCalendar;
import com.quantfinlib.rates.HolidayCalendars;
import com.quantfinlib.rates.HolidayCalendars.Market;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The ISDA grid check that runs on every pull request. The full ISDA RFR grids
 * are copyright and are fetched only by the nightly job; this test reads a small
 * committed subset ({@code isda-golden-subset.csv}): 24 contracts (12 per grid),
 * the 19 swap rates of each grid's curve, and the clean upfront and cash
 * settlement amount that this library computed for them. The SHA-256 of each
 * grid zip is pinned in {@code scripts/reference-files.sha256}.
 *
 * <p>Evidence: on pull requests this is self-consistency (today's code reproduces
 * the committed outputs). {@link #goldenRowsMatchIsdaGrid()} runs nightly against
 * the fetched grid and ties every committed output to the ISDA expected value of
 * the same row, which makes the subset externally anchored.</p>
 *
 * <p>Regenerate (needs the grids, see {@link IsdaGridVerificationTest}):
 * {@code ./mvnw -pl core test -Dtest=IsdaGoldenSubsetTest#writeGolden
 * -Dqfl.isda.writeGolden=<absolute path of isda-golden-subset.csv>}.</p>
 */
class IsdaGoldenSubsetTest {

    static final String RESOURCE = "isda-golden-subset.csv";
    static final String[] STEMS = {"USD_SOFR_20210426", "USD_SOFR_20220621"};
    static final int STRIDE = 597;
    static final int PER_GRID = 12;

    record Golden(String stem, LocalDate trade, LocalDate maturity, double couponBp, double quoteBp,
                  double recovery, double upfront, double cashSettle) {
    }

    record Subset(List<Golden> rows, Map<String, List<Swap>> curves) {
    }

    static BusinessCalendar calendar(String stem) {
        return stem.endsWith("20220621")
                ? HolidayCalendars.calendar(Market.NEW_YORK_FED, 2020, 2045)
                : BusinessCalendar.weekendsOnly();
    }

    /** Our outputs for one contract: {clean upfront, cash settlement on 10mm}. */
    static double[] compute(LocalDate trade, LocalDate maturity, double coupon, double quote, double recovery,
                            IsdaCurve disc, BusinessCalendar cal) {
        Contract c = IsdaGridVerificationTest.contract(trade, maturity, coupon, recovery, cal);
        Conventions conv = new Conventions(cal, true);
        double u = IsdaCdsModel.quotedSpreadToUpfront(c, quote, disc, conv);
        double accrued = IsdaCdsModel.accruedYearFraction(c) * coupon;
        return new double[] {u, IsdaGridVerificationTest.NOTIONAL * (u - accrued)};
    }

    static Subset load() throws IOException {
        List<Golden> rows = new ArrayList<>();
        Map<String, List<Swap>> curves = new LinkedHashMap<>();
        try (InputStream in = IsdaGoldenSubsetTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE);
            for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(",");
                if (f[0].equals("C")) {
                    curves.computeIfAbsent(f[1], k -> new ArrayList<>())
                            .add(new Swap(f[2], Double.parseDouble(f[3])));
                } else if (f[0].equals("G")) {
                    rows.add(new Golden(f[1], LocalDate.parse(f[2]), LocalDate.parse(f[3]),
                            Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                            Double.parseDouble(f[7]), Double.parseDouble(f[8])));
                }
            }
        }
        return new Subset(rows, curves);
    }

    @Test
    void reproducesCommittedOutputs() throws IOException {
        Subset s = load();
        assertEquals(STEMS.length * PER_GRID, s.rows().size());
        Map<String, IsdaCurve> disc = new LinkedHashMap<>();
        for (Golden g : s.rows()) {
            IsdaCurve d = disc.computeIfAbsent(g.stem(),
                    k -> IsdaRateCurve.bootstrapSwaps(g.trade(), s.curves().get(k), calendar(k)));
            double[] out = compute(g.trade(), g.maturity(), g.couponBp() * 1e-4, g.quoteBp() * 1e-4,
                    g.recovery(), d, calendar(g.stem()));
            assertEquals(g.upfront(), out[0], 1e-13, g.toString());
            assertEquals(g.cashSettle(), out[1], 1e-6, g.toString());
        }
    }

    private static IsdaGridVerificationTest.Row find(List<IsdaGridVerificationTest.Row> grid, Golden g) {
        return grid.stream()
                .filter(x -> x.trade().equals(g.trade()) && x.maturity().equals(g.maturity())
                        && Math.abs(x.coupon() - g.couponBp() * 1e-4) < 1e-12
                        && Math.abs(x.quote() - g.quoteBp() * 1e-4) < 1e-12
                        && Math.abs(x.recovery() - g.recovery()) < 1e-12)
                .findFirst().orElseThrow(() -> new AssertionError("not in grid: " + g));
    }

    /** Nightly: every committed output equals the ISDA expected value of the same grid row. */
    @Test
    void goldenRowsMatchIsdaGrid() throws IOException {
        Path dir = IsdaGridVerificationTest.gridDir();
        Subset s = load();
        for (String stem : STEMS) {
            List<IsdaGridVerificationTest.Row> grid =
                    IsdaGridVerificationTest.readGrid(dir.resolve(stem + "_grid.csv"));
            assertEquals(IsdaGridVerificationTest.readCurve(dir.resolve(stem + "_curve.csv")),
                    s.curves().get(stem), stem + " curve");
            List<Golden> mine = s.rows().stream().filter(g -> g.stem().equals(stem)).toList();
            assertFalse(mine.isEmpty(), stem);
            for (Golden g : mine) {
                IsdaGridVerificationTest.Row r = find(grid, g);
                assertEquals(r.cleanUpfront(), g.upfront(), 1e-12, g.toString());
                assertEquals(r.cashSettle(), g.cashSettle(), 1e-5, g.toString());
            }
        }
    }

    /** Writes the subset; runs only with -Dqfl.isda.writeGolden=FILE and the grids present. */
    @Test
    void writeGolden() throws IOException {
        String target = System.getProperty("qfl.isda.writeGolden");
        Assumptions.assumeTrue(target != null, "qfl.isda.writeGolden not set");
        Path dir = IsdaGridVerificationTest.gridDir();
        StringBuilder sb = new StringBuilder();
        sb.append("# ISDA CDS Standard Model RFR test grids (cdsmodel.com), 12-row subset per grid.\n")
                .append("# Contract inputs and curve rates from the grids; upfront and cashSettle are this\n")
                .append("# library's outputs. Grid zips and their SHA-256: scripts/reference-files.sha256.\n")
                .append("# G,stem,trade,maturity,couponBp,quoteBp,recovery,upfront,cashSettle\n")
                .append("# C,stem,tenor,rate\n");
        for (String stem : STEMS) {
            List<IsdaGridVerificationTest.Row> grid =
                    IsdaGridVerificationTest.readGrid(dir.resolve(stem + "_grid.csv"));
            List<Swap> curve = IsdaGridVerificationTest.readCurve(dir.resolve(stem + "_curve.csv"));
            BusinessCalendar cal = calendar(stem);
            IsdaCurve disc = IsdaRateCurve.bootstrapSwaps(grid.get(0).trade(), curve, cal);
            for (Swap w : curve) {
                sb.append("C,").append(stem).append(',').append(w.tenor()).append(',').append(w.rate()).append('\n');
            }
            for (int i = 0; i < PER_GRID; i++) {
                IsdaGridVerificationTest.Row r = grid.get(i * STRIDE);
                double[] out = compute(r.trade(), r.maturity(), r.coupon(), r.quote(), r.recovery(), disc, cal);
                sb.append(String.format(Locale.ROOT, "G,%s,%s,%s,%s,%s,%s,%s,%s%n", stem, r.trade(),
                        r.maturity(), Math.round(r.coupon() * 1e10) / 1e6, Math.round(r.quote() * 1e10) / 1e6,
                        r.recovery(), out[0], out[1]));
            }
        }
        Files.writeString(Path.of(target), sb.toString().replace("\r\n", "\n"));
    }
}

package com.quantfinlib.credit;

import com.quantfinlib.credit.IsdaCdsModel.Contract;
import com.quantfinlib.credit.IsdaCdsModel.Conventions;
import com.quantfinlib.rates.BusinessCalendar;
import com.quantfinlib.rates.HolidayCalendars;
import com.quantfinlib.rates.HolidayCalendars.Market;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link IsdaCdsModel} against the official ISDA CDS Standard Model
 * RFR test grids (cdsmodel.com, USD SOFR 2021-04-26 and 2022-06-21). The
 * grids are copyright and are not in this repository: convert them with
 * {@code scripts/isda-grid-to-csv.py} into a folder outside the repo and
 * set {@code QFL_ISDA_GRID_DIR} to it. Without the variable the test is
 * skipped.
 *
 * <p>The discount curve is bootstrapped here from the grid's swap rates
 * with the ISDA RFR curve conventions (spot T+2 business days, annual
 * fixed leg ACT/360, modified following, single payment below one year,
 * zero rates ACT/365F linear in r t between swap maturities). Both grids
 * match only with half-day accrual on default switched on; the 2021 grid
 * uses no holiday calendar and the 2022 grid the New York calendar (NYM),
 * which moves the 20 Jun 2022 accrual start to 21 Jun for Juneteenth.
 * Measured on 7164 rows each: cash settlement within 1e-6 USD on 10mm
 * notional, clean upfront within 1e-13.</p>
 */
class IsdaGridVerificationTest {

    static final double NOTIONAL = 1e7;

    record Row(LocalDate trade, LocalDate maturity, double coupon, double quote, double recovery,
               double cleanUpfront, double cashSettle, double cleanPrice, double days, double accrued,
               LocalDate cashSettleDate) {
    }

    static List<Row> readGrid(Path p) throws IOException {
        List<Row> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            out.add(new Row(LocalDate.parse(f[0]), LocalDate.parse(f[1]), Double.parseDouble(f[3]) * 1e-4,
                    Double.parseDouble(f[4]) * 1e-4, Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                    Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9]),
                    Double.parseDouble(f[10]), LocalDate.parse(f[11])));
        }
        return out;
    }

    record Swap(String tenor, double rate) {
    }

    static List<Swap> readCurve(Path p) throws IOException {
        List<Swap> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (!f[0].equals("S")) {
                throw new IllegalStateException("only swap instruments expected: " + lines.get(i));
            }
            out.add(new Swap(f[1], Double.parseDouble(f[2])));
        }
        return out;
    }

    static double yrs(LocalDate base, LocalDate d) {
        return ChronoUnit.DAYS.between(base, d) / 365.0;
    }

    /** ISDA RFR swap-only zero curve, base date = trade date. */
    static IsdaCurve bootstrapSwaps(LocalDate trade, List<Swap> swaps, BusinessCalendar cal) {
        LocalDate spot = cal.addBusinessDays(trade, 2);
        int n = swaps.size();
        double[] t = new double[n];
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            String ten = swaps.get(i).tenor();
            int num = Integer.parseInt(ten.substring(0, ten.length() - 1));
            int months = ten.endsWith("M") ? num : 12 * num;
            LocalDate mat = cal.roll(spot.plusMonths(months), BusinessCalendar.Roll.MODIFIED_FOLLOWING);
            List<LocalDate> pays = new ArrayList<>();
            List<Double> yfs = new ArrayList<>();
            LocalDate prev = spot;
            int periods = Math.max(1, months / 12);
            for (int k = 1; k <= periods; k++) {
                LocalDate d = k == periods ? mat
                        : cal.roll(spot.plusMonths(12L * k), BusinessCalendar.Roll.MODIFIED_FOLLOWING);
                pays.add(d);
                yfs.add(ChronoUnit.DAYS.between(prev, d) / 360.0);
                prev = d;
            }
            t[i] = yrs(trade, mat);
            double c = swaps.get(i).rate();
            double lo = -0.5;
            double hi = 1.0;
            for (int it = 0; it < 200; it++) {
                double mid = 0.5 * (lo + hi);
                r[i] = mid;
                IsdaCurve cv = IsdaCurve.ofZeroRates(Arrays.copyOf(t, i + 1), Arrays.copyOf(r, i + 1));
                double pv = cv.value(yrs(trade, mat));
                for (int k = 0; k < pays.size(); k++) {
                    pv += c * yfs.get(k) * cv.value(yrs(trade, pays.get(k)));
                }
                double f = pv - cv.value(yrs(trade, spot));   // decreasing in r
                if (f > 0) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            r[i] = 0.5 * (lo + hi);
        }
        return IsdaCurve.ofZeroRates(t, r);
    }

    static final class Err {
        final String name;
        double maxAbs;
        double maxRel;
        String worst = "";

        Err(String name) {
            this.name = name;
        }

        void add(double got, double exp, String where) {
            double a = Math.abs(got - exp);
            double rel = a / Math.max(Math.abs(exp), 1e-12);
            if (a > maxAbs) {
                maxAbs = a;
                worst = where + " got=" + got + " exp=" + exp;
            }
            maxRel = Math.max(maxRel, rel);
        }

        @Override
        public String toString() {
            return String.format("%-14s maxAbs=%.3e maxRel=%.3e worst: %s", name, maxAbs, maxRel, worst);
        }
    }

    /** Result of one grid under one convention set. */
    record Result(int rows, Err csa, Err upfront, Err price, Err accrued, Err days) {
    }

    static Result run(String stem, Path dir, BusinessCalendar cal, boolean halfDay) throws IOException {
        List<Row> rows = readGrid(dir.resolve(stem + "_grid.csv"));
        List<Swap> swaps = readCurve(dir.resolve(stem + "_curve.csv"));
        Conventions conv = new Conventions(cal, halfDay);
        IsdaCurve disc = bootstrapSwaps(rows.get(0).trade(), swaps, cal);
        Err csa = new Err("cashSettle");
        Err up = new Err("cleanUpfront");
        Err px = new Err("cleanPrice");
        Err acc = new Err("accrued");
        Err days = new Err("daysAccrued");
        for (Row row : rows) {
            LocalDate stepIn = row.trade().plusDays(1);
            Contract c = new Contract(row.trade(), stepIn, cal.addBusinessDays(row.trade(), 3),
                    IsdaCdsModel.accrualStartDate(stepIn, cal), row.maturity(), row.coupon(), row.recovery());
            String where = row.maturity() + " c=" + row.coupon() + " q=" + row.quote() + " R=" + row.recovery();
            double u = IsdaCdsModel.quotedSpreadToUpfront(c, row.quote(), disc, conv);
            double accrued = IsdaCdsModel.accruedYearFraction(c) * row.coupon();
            up.add(u, row.cleanUpfront(), where);
            px.add(100 * (1 - u), row.cleanPrice(), where);
            acc.add(NOTIONAL * accrued, row.accrued(), where);
            days.add((double) ChronoUnit.DAYS.between(c.accrualStartDate(), stepIn), row.days(), where);
            csa.add(NOTIONAL * (u - accrued), row.cashSettle(), where);
            if (!c.cashSettleDate().equals(row.cashSettleDate())) {
                throw new AssertionError("cash settle date " + c.cashSettleDate() + " vs " + row.cashSettleDate());
            }
        }
        return new Result(rows.size(), csa, up, px, acc, days);
    }

    static Path gridDir() {
        String env = System.getenv("QFL_ISDA_GRID_DIR");
        Assumptions.assumeTrue(env != null && !env.isBlank(),
                "QFL_ISDA_GRID_DIR not set: ISDA test grids are copyright and not in the repo; "
                        + "convert them with scripts/isda-grid-to-csv.py and point the variable at the CSVs");
        return Path.of(env);
    }

    static void report(String label, Result r) {
        System.out.println("[isda-grid] " + label + " rows=" + r.rows());
        for (Err e : List.of(r.csa(), r.upfront(), r.price(), r.accrued(), r.days())) {
            System.out.println("[isda-grid]   " + e);
        }
    }

    static void assertMatches(Result r) {
        assertEquals(7164, r.rows());
        assertTrue(r.csa().maxAbs < 1e-5, r.csa().toString());          // USD on 10mm notional
        assertTrue(r.upfront().maxAbs < 1e-12, r.upfront().toString());
        assertTrue(r.price().maxAbs < 1e-10, r.price().toString());
        assertTrue(r.accrued().maxAbs < 1e-8, r.accrued().toString());
        assertTrue(r.days().maxAbs < 1e-10, r.days().toString());
    }

    /** 2021-04-26 grid: weekends-only calendar, half-day accrual on. */
    @Test
    void sofr20210426WeekendsHalfDay() throws IOException {
        Result r = run("USD_SOFR_20210426", gridDir(), BusinessCalendar.weekendsOnly(), true);
        report("USD_SOFR_20210426 weekends halfDay", r);
        assertMatches(r);
    }

    /** 2022-06-21 grid: New York (NYM) holidays, half-day accrual on. */
    @Test
    void sofr20220621NewYorkHalfDay() throws IOException {
        Result r = run("USD_SOFR_20220621", gridDir(),
                HolidayCalendars.calendar(Market.NEW_YORK_FED, 2020, 2045), true);
        report("USD_SOFR_20220621 NY halfDay", r);
        assertMatches(r);
    }
}

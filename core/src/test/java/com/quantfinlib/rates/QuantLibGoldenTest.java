package com.quantfinlib.rates;

import com.quantfinlib.rates.BusinessCalendar.Roll;
import com.quantfinlib.rates.BusinessCalendar.StubType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Externally anchored: every expected number comes from
 * {@code golden/quantlib-sofr.csv}, generated offline by
 * {@code scripts/quantlib-golden-sofr.py} with QuantLib-Python (version in
 * the file header). The library recomputes each from the same inputs.
 */
class QuantLibGoldenTest {

    private static final BusinessCalendar CAL = BusinessCalendar.weekendsOnly();
    private static final Roll MF = Roll.MODIFIED_FOLLOWING;
    private static Map<String, String> g;
    private static DiscountCurve curve;
    private static FixingHistory fixings;

    @BeforeAll
    static void load() throws IOException {
        g = new LinkedHashMap<>();
        try (InputStream in = Objects.requireNonNull(
                QuantLibGoldenTest.class.getResourceAsStream("/golden/quantlib-sofr.csv"));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = r.readLine();
            assertTrue(header.startsWith("# QuantLib-Python "), header);
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                int c = line.indexOf(',');
                g.put(line.substring(0, c), line.substring(c + 1));
            }
        }
        LocalDate val = LocalDate.of(2026, 11, 2);
        LocalDate spot = CAL.addBusinessDays(val, 2);
        List<CurveInstrument> ois = new ArrayList<>();
        ois.add(new Deposit(val, CAL.addBusinessDays(val, 1), num("curve.deposit.on"), DayCount.ACT_360));
        for (int m : new int[] {3, 6, 12, 24, 36, 60, 84, 120, 360}) {
            int perYear = m < 12 ? 12 / m : 1;
            ois.add(new OisSwap(spot, spot.plusMonths(m), num("curve.ois." + m + "m"), perYear,
                    DayCount.ACT_360, CAL, MF));
        }
        curve = MultiCurveBootstrap.bootstrap(val, ois, CurveInterpolation.LOG_LINEAR_DISCOUNT);
        Map<LocalDate, Double> fx = new TreeMap<>();
        g.forEach((k, v) -> {
            if (k.startsWith("fixing.")) {
                fx.put(LocalDate.parse(k.substring(7)), Double.parseDouble(v));
            }
        });
        fixings = FixingHistory.of(fx);
    }

    private static double num(String key) {
        return Double.parseDouble(Objects.requireNonNull(g.get(key), key));
    }

    private static LocalDate date(String key) {
        return LocalDate.parse(Objects.requireNonNull(g.get(key), key));
    }

    @Test
    void curveNodesAndProbesMatchQuantLib() {
        for (int i = 1; g.containsKey("curve.node." + i + ".date"); i++) {
            LocalDate d = date("curve.node." + i + ".date");
            assertEquals(curve.time(d), curve.pillarTime(i - 1), 1e-15, "pillar date " + d);
            assertEquals(num("curve.node." + i + ".df"), curve.discountFactor(d), 1e-12, "node " + d);
        }
        int probes = 0;
        for (var e : g.entrySet()) {
            if (e.getKey().startsWith("curve.df.")) {
                LocalDate d = LocalDate.parse(e.getKey().substring(9));
                assertEquals(Double.parseDouble(e.getValue()), curve.discountFactor(d), 1e-12, "probe " + d);
                probes++;
            }
        }
        assertEquals(5, probes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"seasoned_lookback", "seasoned_shift", "seasoned_lockout", "forward_stub",
            "spot_5y_plain"})
    void sofrSwapsMatchQuantLib(String name) {
        String p = "swap." + name + ".";
        LocalDate s = date(p + "start");
        LocalDate e = date(p + "end");
        LegConvention conv = new LegConvention(1, DayCount.ACT_360, CAL, MF, StubType.SHORT_FRONT, false,
                (int) num(p + "paymentLag"));
        VanillaSwap swap = new VanillaSwap(new FixedLeg(s, e, num(p + "fixed"), conv),
                new OvernightLeg(s, e, 0, conv, new OvernightConvention((int) num(p + "lookback"),
                        Boolean.parseBoolean(g.get(p + "shift").toLowerCase(java.util.Locale.ROOT)),
                        (int) num(p + "lockout")), fixings));
        assertEquals(num(p + "floatLegNpv"), swap.floatLegPv(curve, curve), 2e-12, "float leg");
        assertEquals(-num(p + "fixedLegNpv"), swap.fixed().pv(curve), 2e-12, "fixed leg");
        assertEquals(num(p + "npv"), swap.payerPv(curve, curve), 3e-12, "npv");
        assertEquals(num(p + "fairRate"), swap.parRate(curve, curve), 1e-11, "fair rate");
    }

    @Test
    void bondStreetConventionMatchesQuantLib() {
        LocalDate settle = date("bond.settlement");
        LocalDate mat = date("bond.maturity");
        double c = num("bond.coupon");
        double y = num("bond.yield");
        assertEquals(num("bond.clean"), BondPricer.cleanPrice(100, c, 2, settle, mat, y, DayCount.ACT_ACT_ICMA),
                1e-10);
        assertEquals(num("bond.dirty"), BondPricer.dirtyPrice(100, c, 2, settle, mat, y, DayCount.ACT_ACT_ICMA),
                1e-10);
        assertEquals(num("bond.accrued"),
                BondPricer.accruedInterest(100, c, 2, settle, mat, DayCount.ACT_ACT_ICMA), 1e-10);
        assertEquals(num("bond.yieldFromClean.yield"), BondPricer.yieldFromCleanPrice(
                num("bond.yieldFromClean.price"), 100, c, 2, settle, mat, DayCount.ACT_ACT_ICMA, false), 1e-12);
    }

    @Test
    void bondExactGridDurationAndConvexityMatchQuantLib() {
        // Under ACT/ACT ICMA the time to each flow is (k - 1 + w)/f, w = 1 - accrued/C:
        // the exact (non-integral) grid of BondPricer.priceFromYieldExact.
        double c = num("bond.coupon");
        double y = num("bond.yield");
        LocalDate settle = date("bond.settlement");
        LocalDate mat = date("bond.maturity");
        int remaining = 0;
        for (LocalDate d = mat; d.isAfter(settle); d = mat.minusMonths(6L * remaining)) {
            remaining++;
        }
        double w = 1 - num("bond.accrued") / (100 * c / 2);
        double years = (remaining - 1 + w) / 2;
        assertTrue(!BondPricer.isWholePeriods(2, years));
        assertEquals(num("bond.dirty"), BondPricer.priceFromYieldExact(100, c, 2, years, y), 1e-10);
        assertEquals(num("bond.accrued"), BondPricer.accruedInterestExact(100, c, 2, years), 1e-10);
        assertEquals(num("bond.macaulay"), BondPricer.macaulayDurationExact(100, c, 2, years, y), 1e-10);
        assertEquals(num("bond.modified"), BondPricer.modifiedDurationExact(100, c, 2, years, y), 1e-10);
        assertEquals(num("bond.convexity"), BondPricer.convexityExact(100, c, 2, years, y), 1e-9);
    }
}

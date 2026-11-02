package com.quantfinlib.rates;

import com.quantfinlib.rates.BusinessCalendar.Roll;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-curve bootstrap pinned by: repricing of every input to 1e-10, an
 * independent closed-form recursion for an OIS strip, the single-curve
 * telescoping identity, exact monotone-convex properties (G(1) = 0, node
 * continuity, monotonicity preservation), the Ho-Lee limit of the
 * Hull-White convexity adjustment, and exact bucket structure of the DV01
 * of a par input swap.
 */
class MultiCurveTest {

    private static final LocalDate VAL = LocalDate.of(2026, 11, 2);
    private static final BusinessCalendar CAL = BusinessCalendar.weekendsOnly();
    private static final Roll MF = Roll.MODIFIED_FOLLOWING;
    private static final LocalDate SPOT = CAL.addBusinessDays(VAL, 2);

    private static LocalDate spotPlus(int months) {
        return CAL.roll(SPOT.plusMonths(months), MF);
    }

    static List<CurveInstrument> oisInstruments() {
        List<CurveInstrument> l = new ArrayList<>();
        l.add(new Deposit(VAL, CAL.addBusinessDays(VAL, 1), 0.0380, DayCount.ACT_360));
        double[] q = {0.0378, 0.0372, 0.0360, 0.0352, 0.0349, 0.0350, 0.0355, 0.0362, 0.0371};
        int[] m = {3, 6, 12, 24, 36, 60, 84, 120, 360};
        for (int i = 0; i < q.length; i++) {
            int perYear = m[i] < 12 ? 12 / m[i] : 1;
            l.add(new OisSwap(SPOT, SPOT.plusMonths(m[i]), q[i], perYear, DayCount.ACT_360, CAL, MF));
        }
        return l;
    }

    static InterestRateSwap irs(int years, double rate) {
        return new InterestRateSwap(SPOT, SPOT.plusYears(years), rate, 1, DayCount.THIRTY_360, 4,
                DayCount.ACT_360, CAL, MF);
    }

    static List<CurveInstrument> projectionInstruments() {
        List<CurveInstrument> l = new ArrayList<>();
        l.add(new Deposit(SPOT, spotPlus(3), 0.0395, DayCount.ACT_360));
        l.add(new Fra(spotPlus(3), spotPlus(6), 0.0388, DayCount.ACT_360));
        l.add(new Fra(spotPlus(6), spotPlus(9), 0.0380, DayCount.ACT_360));
        l.add(RateFuture.ofPrice(spotPlus(9), spotPlus(12), 96.25, DayCount.ACT_360, 0.03, 0.01));
        l.add(RateFuture.ofPrice(spotPlus(12), spotPlus(15), 96.31, DayCount.ACT_360, 0.03, 0.01));
        l.add(irs(2, 0.0372));
        l.add(irs(3, 0.0368));
        l.add(irs(5, 0.0369));
        l.add(irs(7, 0.0374));
        l.add(irs(10, 0.0381));
        l.add(irs(30, 0.0390));
        return l;
    }

    private static CurvePair curves(CurveInterpolation interp) {
        return MultiCurveRisk.buildCurves(VAL, oisInstruments(), projectionInstruments(), interp);
    }

    @ParameterizedTest
    @EnumSource(CurveInterpolation.class)
    void everyInputInstrumentRepricesTo1e10(CurveInterpolation interp) {
        CurvePair c = curves(interp);
        for (CurveInstrument i : oisInstruments()) {
            assertEquals(i.quote(), i.impliedQuote(c.discount(), c.discount()), 1e-10, i.toString());
        }
        for (CurveInstrument i : projectionInstruments()) {
            assertEquals(i.quote(), i.impliedQuote(c.discount(), c.projection()), 1e-10, i.toString());
        }
        assertEquals(oisInstruments().size(), c.discount().pillarCount());
        // The 3M index trades above OIS: projection discount factors are lower.
        assertTrue(c.projection().discountFactor(5.0) < c.discount().discountFactor(5.0));
    }

    @ParameterizedTest
    @EnumSource(CurveInterpolation.class)
    void annualOisStripMatchesClosedFormRecursion(CurveInterpolation interp) {
        // Independent reference: with payment dates on the pillars, start at
        // the valuation date and tau = ACT/365F = the curve's own time step,
        // the par condition K sum tau_i P_i = 1 - P_n solves forward as
        // P_n = (1 - K sum_{i<n} tau_i P_i) / (1 + K tau_n).
        double[] k = {0.031, 0.033, 0.034, 0.0345, 0.035, 0.036, 0.0362};
        List<CurveInstrument> l = new ArrayList<>();
        for (int n = 1; n <= k.length; n++) {
            l.add(new OisSwap(VAL, VAL.plusYears(n), k[n - 1], 1, DayCount.ACT_365, CAL, Roll.NONE));
        }
        DiscountCurve c = MultiCurveBootstrap.bootstrap(VAL, l, interp);
        List<Double> p = new ArrayList<>();
        for (int n = 1; n <= k.length; n++) {
            double sum = 0;
            for (int i = 1; i < n; i++) {
                sum += DayCount.ACT_365.yearFraction(VAL.plusYears(i - 1), VAL.plusYears(i)) * p.get(i - 1);
            }
            double tauN = DayCount.ACT_365.yearFraction(VAL.plusYears(n - 1), VAL.plusYears(n));
            p.add((1 - k[n - 1] * sum) / (1 + k[n - 1] * tauN));
            assertEquals(p.get(n - 1), c.pillarDiscountFactor(n - 1), 1e-13, "pillar " + n);
            assertEquals(p.get(n - 1), c.discountFactor(VAL.plusYears(n)), 1e-13);
        }
    }

    @Test
    void singleCurveSwapReducesToTelescopedOisPar() {
        DiscountCurve c = curves(CurveInterpolation.MONOTONE_CONVEX).discount();
        InterestRateSwap s = new InterestRateSwap(SPOT, SPOT.plusYears(6), 0.03, 4, DayCount.ACT_360, 4,
                DayCount.ACT_360, CAL, MF);
        OisSwap o = new OisSwap(SPOT, SPOT.plusYears(6), 0.03, 4, DayCount.ACT_360, CAL, MF);
        assertEquals(o.parRate(c), s.parRate(c, c), 1e-14);
        assertEquals(o.payerPv(c), s.payerPv(c, c), 1e-14);
        assertEquals(o.parRate(c), o.impliedQuote(c, null), 0);
    }

    @Test
    void fraParRateIgnoresDiscountCurve() {
        CurvePair c = curves(CurveInterpolation.LOG_LINEAR_DISCOUNT);
        Fra f = new Fra(spotPlus(18), spotPlus(21), 0.03, DayCount.ACT_360);
        assertEquals(f.impliedQuote(c.discount(), c.projection()),
                f.impliedQuote(c.projection(), c.projection()), 0);
        assertEquals(0.05, f.withQuote(0.05).quote(), 0);
    }

    // ---- monotone convex -------------------------------------------------

    private static DiscountCurve curveFromForwards(double[] times, double[] fwd, CurveInterpolation i) {
        double[] df = new double[times.length];
        double y = 0;
        double prev = 0;
        for (int k = 0; k < times.length; k++) {
            y += fwd[k] * (times[k] - prev);
            prev = times[k];
            df[k] = Math.exp(-y);
        }
        return DiscountCurve.of(VAL, times, df, i);
    }

    private static final double[] T = {0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30};
    private static final double[] F_HUMP = {0.030, 0.034, 0.031, 0.036, 0.045, 0.041, 0.039, 0.043,
        0.040, 0.036, 0.035};

    @Test
    void monotoneConvexForwardIsContinuousWhereLogLinearJumps() {
        DiscountCurve mc = curveFromForwards(T, F_HUMP, CurveInterpolation.MONOTONE_CONVEX);
        DiscountCurve ll = curveFromForwards(T, F_HUMP, CurveInterpolation.LOG_LINEAR_DISCOUNT);
        double eps = 1e-9;
        for (int i = 0; i < T.length - 1; i++) {
            double t = T[i];
            assertEquals(mc.instantaneousForward(t - eps), mc.instantaneousForward(t + eps), 1e-6,
                    "continuous at " + t);
            assertEquals(mc.instantaneousForward(t), mc.instantaneousForward(t + eps), 1e-6);
            assertTrue(Math.abs(ll.instantaneousForward(t - eps) - ll.instantaneousForward(t + eps)) > 1e-3);
            // both reproduce the pillar discount factors
            assertEquals(ll.pillarDiscountFactor(i), mc.pillarDiscountFactor(i), 1e-15);
            assertEquals(ll.discountFactor(t), mc.discountFactor(t), 1e-14);
        }
    }

    @Test
    void monotoneConvexForwardIsTheDerivativeOfMinusLogDiscount() {
        DiscountCurve mc = curveFromForwards(T, F_HUMP, CurveInterpolation.MONOTONE_CONVEX);
        double h = 1e-5;
        for (double t = 0.01; t < 35; t += 0.137) {
            double fd = (mc.minusLogDf(t + h) - mc.minusLogDf(t - h)) / (2 * h);
            assertEquals(mc.instantaneousForward(t), fd, 1e-7, "t=" + t);
        }
        // Average of the forward over each interval equals the discrete forward.
        double prev = 0;
        for (int i = 0; i < T.length; i++) {
            int n = 2000;
            double sum = 0;
            double h2 = (T[i] - prev) / n;
            for (int k = 0; k < n; k++) {
                sum += mc.instantaneousForward(prev + (k + 0.5) * h2) * h2;
            }
            assertEquals(F_HUMP[i], sum / (T[i] - prev), 1e-7, "interval " + i);
            prev = T[i];
        }
    }

    @Test
    void monotoneConvexPreservesMonotoneForwardsAndIsSmootherThanLogLinear() {
        double[] up = {0.010, 0.012, 0.015, 0.020, 0.024, 0.030, 0.033, 0.035, 0.036, 0.0365, 0.037};
        DiscountCurve mc = curveFromForwards(T, up, CurveInterpolation.MONOTONE_CONVEX);
        double last = mc.instantaneousForward(0);
        for (double t = 0.001; t < 32; t += 0.001) {
            double f = mc.instantaneousForward(t);
            assertTrue(f >= last - 1e-14, "monotone at " + t);
            last = f;
        }
        // Smoothness: total variation of the forward equals its range for a
        // monotone curve, and the largest step on a fine grid is tiny.
        DiscountCurve hump = curveFromForwards(T, F_HUMP, CurveInterpolation.MONOTONE_CONVEX);
        double maxStep = 0;
        double prev = hump.instantaneousForward(0);
        for (double t = 1e-3; t < 30; t += 1e-3) {
            double f = hump.instantaneousForward(t);
            maxStep = Math.max(maxStep, Math.abs(f - prev));
            prev = f;
        }
        assertTrue(maxStep < 1e-4, "no jumps on a 1e-3 grid, max step " + maxStep);
    }

    @Test
    void shapeFunctionZonesKeepEndpointsAndZeroIntegral() {
        double[][] g = {
            {-0.01, 0.01}, {0.01, -0.01}, // zone (i)
            {-0.01, 0.03}, {0.01, -0.03}, // zone (ii)
            {0.01, -0.002}, {-0.01, 0.002}, // zone (iii)
            {0.01, 0.02}, {-0.01, -0.02}, // zone (iv)
            {0, 0}, {0.01, 0}, {0, 0.01}, // degenerate
        };
        for (double[] p : g) {
            assertEquals(0, DiscountCurve.shape(p[0], p[1], 1, true), 1e-17, "G(1) " + p[0] + "," + p[1]);
            assertEquals(p[0], DiscountCurve.shape(p[0], p[1], 0, false), 0);
            assertEquals(0, DiscountCurve.shape(p[0], p[1], 0, true), 0);
            if (p[0] != 0 && p[1] != 0) {
                assertEquals(p[1], DiscountCurve.shape(p[0], p[1], 1, false), 1e-15);
            }
            int n = 4000;
            double sum = 0;
            for (int k = 0; k < n; k++) {
                sum += DiscountCurve.shape(p[0], p[1], (k + 0.5) / n, false) / n;
                double x = (k + 1.0) / n;
                if (k % 400 == 0) {
                    assertEquals(sum, DiscountCurve.shape(p[0], p[1], x, true), 1e-8);
                }
            }
        }
    }

    // ---- futures convexity -----------------------------------------------

    @Test
    void hullWhiteConvexityTendsToHoLeeAndFallsWithMeanReversion() {
        // Ho-Lee closed form sigma^2 t1 t2 / 2 (Hull 2018, section 6.3):
        // sigma = 1.2%, t1 = 8, t2 = 8.25 gives 0.004752.
        assertEquals(0.004752, RateFuture.hullWhiteConvexity(8, 8.25, 0, 0.012), 1e-15);
        assertEquals(0.004752, RateFuture.hullWhiteConvexity(8, 8.25, 1e-7, 0.012), 1e-8);
        double prev = Double.POSITIVE_INFINITY;
        for (double a : new double[] {0.001, 0.01, 0.05, 0.1, 0.3}) {
            double ca = RateFuture.hullWhiteConvexity(8, 8.25, a, 0.012);
            assertTrue(ca > 0 && ca < prev);
            prev = ca;
        }
        assertEquals(0, RateFuture.hullWhiteConvexity(1, 1.25, 0.05, 0), 0);
        assertThrows(IllegalArgumentException.class, () -> RateFuture.hullWhiteConvexity(2, 1, 0.1, 0.01));
    }

    @Test
    void futureWithoutVolatilityEqualsFraAndVolatilityRaisesTheRate() {
        CurvePair c = curves(CurveInterpolation.MONOTONE_CONVEX);
        LocalDate s = spotPlus(24);
        LocalDate e = spotPlus(27);
        Fra fra = new Fra(s, e, 0, DayCount.ACT_360);
        RateFuture noVol = new RateFuture(s, e, 0, DayCount.ACT_360, 0.03, 0);
        RateFuture vol = new RateFuture(s, e, 0, DayCount.ACT_360, 0.03, 0.01);
        double fwd = fra.impliedQuote(c.discount(), c.projection());
        assertEquals(fwd, noVol.impliedQuote(c.discount(), c.projection()), 1e-15);
        double fut = vol.impliedQuote(c.discount(), c.projection());
        double tau = DayCount.ACT_360.yearFraction(s, e);
        double dt = c.projection().time(e) - c.projection().time(s);
        double expected = (Math.exp(Math.log1p(fwd * tau) + vol.convexityAdjustment(c.projection()) * dt) - 1) / tau;
        assertEquals(expected, fut, 1e-15);
        assertTrue(fut > fwd);
        assertEquals(0.0375, RateFuture.ofPrice(s, e, 96.25, DayCount.ACT_360, 0, 0).quote(), 1e-15);
        assertEquals(0.05, vol.withQuote(0.05).rate(), 0);
    }

    // ---- risk --------------------------------------------------------------

    @Test
    void bucketedDv01OfParInputSwapLivesOnlyInItsOwnQuote() {
        // A payer swap identical to the 5Y input is at par after every
        // rebuild except when its own quote moves, so every other bucket is
        // zero and its own bucket is +A_d * 1bp (its fixed rate stays put
        // while the par rate moves 1bp: PV = A (par - K)).
        InterestRateSwap swap = irs(5, 0.0369);
        List<CurveInstrument> ois = oisInstruments();
        List<CurveInstrument> proj = projectionInstruments();
        double[] dv01 = MultiCurveRisk.bucketedDv01(VAL, ois, proj, CurveInterpolation.LOG_LINEAR_DISCOUNT,
                swap::payerPv);
        int own = ois.size() + proj.indexOf(swap);
        CurvePair c = curves(CurveInterpolation.LOG_LINEAR_DISCOUNT);
        double annuity = swap.annuity(c.discount());
        for (int k = 0; k < dv01.length; k++) {
            if (k == own) {
                assertEquals(annuity * 1e-4, dv01[k], 1e-12);
            } else {
                assertEquals(0, dv01[k], 1e-12, "bucket " + k);
            }
        }
    }

    @Test
    void keyRateDv01SumsToParallelDv01() {
        InterestRateSwap off = new InterestRateSwap(SPOT.plusMonths(7), SPOT.plusMonths(7).plusYears(8),
                0.04, 2, DayCount.ACT_365, 4, DayCount.ACT_360, CAL, MF);
        for (CurveInterpolation interp : CurveInterpolation.values()) {
            double par = MultiCurveRisk.parallelDv01(VAL, oisInstruments(), projectionInstruments(), interp,
                    off::payerPv);
            double[] kr = MultiCurveRisk.keyRateDv01(VAL, oisInstruments(), projectionInstruments(), interp,
                    off::payerPv, new double[] {0.5, 2, 5, 10, 30});
            double sum = 0;
            for (double v : kr) {
                sum += v;
            }
            // Additive to first order: the central difference leaves an
            // h^3 f'''[w,w,w] term that is not linear in the weights, of
            // order 1e-12 * T^3 * annuity here, about 1e-7 relative.
            // Monotone-convex is continuous but not differentiable in its
            // inputs where a pillar interval changes zone, so a 1bp bump
            // crosses kinks: additivity there holds to about 2% at 1bp
            // (and to 1e-7 at a 0.01bp bump, checked while developing).
            double tol = interp == CurveInterpolation.LOG_LINEAR_DISCOUNT ? 1e-6 : 0.03;
            assertEquals(par, sum, tol * Math.abs(par), interp.name());
            assertTrue(par > 0, "payer gains when rates rise");
            double[] b = MultiCurveRisk.bucketedDv01(VAL, oisInstruments(), projectionInstruments(), interp,
                    off::payerPv);
            double bs = 0;
            for (double v : b) {
                bs += v;
            }
            assertEquals(par, bs, tol * Math.abs(par));
        }
        assertThrows(IllegalArgumentException.class, () -> MultiCurveRisk.keyRateDv01(VAL, oisInstruments(),
                List.of(), CurveInterpolation.LOG_LINEAR_DISCOUNT, off::payerPv, new double[] {2, 1}));
        assertThrows(IllegalArgumentException.class, () -> MultiCurveRisk.keyRateDv01(VAL, oisInstruments(),
                List.of(), CurveInterpolation.LOG_LINEAR_DISCOUNT, off::payerPv, new double[0]));
    }

    @Test
    void hatWeightsPartitionUnity() {
        double[] keys = {1, 2, 5};
        for (double t = 0; t < 7; t += 0.1) {
            double s = 0;
            for (int j = 0; j < keys.length; j++) {
                double w = MultiCurveRisk.hat(keys, j, t);
                assertTrue(w >= 0 && w <= 1);
                s += w;
            }
            assertEquals(1, s, 1e-15);
        }
    }

    // ---- pricer overloads ------------------------------------------------

    @Test
    void swaptionAndCapParityOnCurvePair() {
        CurvePair c = curves(CurveInterpolation.MONOTONE_CONVEX);
        DiscountCurve d = c.discount();
        DiscountCurve p = c.projection();
        LocalDate start = spotPlus(24);
        InterestRateSwap u = new InterestRateSwap(start, start.plusYears(5), 0, 1, DayCount.THIRTY_360, 4,
                DayCount.ACT_360, CAL, MF);
        double k = 0.036;
        RatesOptions.VolModel normal = RatesOptions.VolModel.normal();
        double payer = RatesOptions.swaption(d, p, u, k, 0.009, true, normal);
        double receiver = RatesOptions.swaption(d, p, u, k, 0.009, false, normal);
        assertEquals(u.annuity(d) * (u.parRate(d, p) - k), payer - receiver, 1e-14);
        double atm = RatesOptions.swaption(d, p, u, u.parRate(d, p), 0.009, true, normal);
        double expiry = d.time(start);
        assertEquals(u.annuity(d) * 0.009 * Math.sqrt(expiry / (2 * Math.PI)), atm, 1e-14);

        LocalDate cs = SPOT;
        LocalDate ce = SPOT.plusYears(5);
        double cap = RatesOptions.capFloor(d, p, cs, ce, 4, DayCount.ACT_360, CAL, MF, k, 0.009, true, normal);
        double floor = RatesOptions.capFloor(d, p, cs, ce, 4, DayCount.ACT_360, CAL, MF, k, 0.009, false, normal);
        InterestRateSwap leg = new InterestRateSwap(cs, ce, k, 4, DayCount.ACT_360, 4, DayCount.ACT_360, CAL, MF);
        assertEquals(leg.payerPv(d, p), cap - floor, 1e-14);
        assertThrows(IllegalArgumentException.class,
                () -> RatesOptions.capFloor(d, p, cs, ce, 4, DayCount.ACT_360, CAL, MF, Double.NaN, 0.01, true, normal));
        assertThrows(IllegalArgumentException.class,
                () -> RatesOptions.swaption(d, p, u, Double.NaN, 0.01, true, normal));
    }

    // ---- curve object and validation --------------------------------------

    @Test
    void discountCurveAccessorsAndExtrapolation() {
        DiscountCurve ll = curveFromForwards(T, F_HUMP, CurveInterpolation.LOG_LINEAR_DISCOUNT);
        DiscountCurve mc = curveFromForwards(T, F_HUMP, CurveInterpolation.MONOTONE_CONVEX);
        assertEquals(VAL, ll.valuationDate());
        assertEquals(CurveInterpolation.MONOTONE_CONVEX, mc.interpolation());
        assertEquals(T.length, ll.pillarCount());
        assertEquals(30, ll.pillarTime(T.length - 1), 0);
        assertEquals(1, ll.discountFactor(0), 0);
        assertEquals(F_HUMP[0], ll.zeroRate(0), 1e-14);
        assertEquals(F_HUMP[0], ll.zeroRate(0.1), 1e-15);
        assertEquals(F_HUMP[1], ll.instantaneousForward(0.25), 1e-14); // right-continuous
        assertEquals(F_HUMP[10], ll.instantaneousForward(40), 1e-14);
        double y30 = -Math.log(ll.discountFactor(30));
        assertEquals(y30 + F_HUMP[10] * 10, -Math.log(ll.discountFactor(40)), 1e-12);
        double fN = mc.instantaneousForward(30);
        assertEquals(-Math.log(mc.discountFactor(30)) + fN * 5, -Math.log(mc.discountFactor(35)), 1e-12);
        DiscountCurve shifted = ll.parallelShift(0.001);
        assertEquals(ll.zeroRate(3) + 0.001, shifted.zeroRate(3), 1e-14);
        DiscountCurve one = DiscountCurve.of(VAL, new double[] {2}, new double[] {Math.exp(-0.06)},
                CurveInterpolation.MONOTONE_CONVEX);
        assertEquals(0.03, one.instantaneousForward(1), 1e-14);
        assertThrows(IndexOutOfBoundsException.class, () -> ll.pillarTime(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> ll.pillarDiscountFactor(T.length));
        assertThrows(IllegalArgumentException.class, () -> ll.discountFactor(-1.0));
        assertThrows(IllegalArgumentException.class, () -> ll.discountFactor(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ll.parallelShift(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ll.forwardRate(VAL.plusDays(5), VAL.plusDays(5), DayCount.ACT_360));
        assertThrows(IllegalArgumentException.class, () -> DiscountCurve.of(VAL, new double[0], new double[0],
                CurveInterpolation.LOG_LINEAR_DISCOUNT));
        assertThrows(IllegalArgumentException.class, () -> DiscountCurve.of(VAL, new double[] {1, 1},
                new double[] {0.9, 0.8}, CurveInterpolation.LOG_LINEAR_DISCOUNT));
        assertThrows(IllegalArgumentException.class, () -> DiscountCurve.of(VAL, new double[] {1},
                new double[] {0}, CurveInterpolation.LOG_LINEAR_DISCOUNT));
        assertThrows(IllegalArgumentException.class, () -> DiscountCurve.of(VAL, new double[] {1},
                new double[] {0.9, 0.8}, CurveInterpolation.LOG_LINEAR_DISCOUNT));
    }

    @Test
    void bootstrapAndInstrumentValidation() {
        CurveInterpolation ll = CurveInterpolation.LOG_LINEAR_DISCOUNT;
        assertThrows(IllegalArgumentException.class, () -> MultiCurveBootstrap.bootstrap(VAL, List.of(), ll));
        Deposit a = new Deposit(VAL, VAL.plusMonths(1), 0.03, DayCount.ACT_360);
        Fra b = new Fra(VAL.plusDays(5), VAL.plusMonths(1), 0.03, DayCount.ACT_360);
        assertThrows(IllegalArgumentException.class, () -> MultiCurveBootstrap.bootstrap(VAL, List.of(a, b), ll));
        Deposit past = new Deposit(VAL.minusMonths(2), VAL.minusMonths(1), 0.03, DayCount.ACT_360);
        assertThrows(IllegalArgumentException.class, () -> MultiCurveBootstrap.bootstrap(VAL, List.of(past), ll));
        DiscountCurve ois = MultiCurveBootstrap.bootstrap(VAL, oisInstruments(), ll);
        List<CurveInstrument> withOis = List.of(oisInstruments().get(3));
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurveBootstrap.bootstrapProjection(VAL, withOis, ll, ois));
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurveBootstrap.bootstrapProjection(VAL.plusDays(1), List.of(a), ll, ois));
        assertThrows(IllegalArgumentException.class, () -> new Deposit(VAL, VAL, 0.01, DayCount.ACT_360));
        assertThrows(IllegalArgumentException.class,
                () -> new Fra(VAL, VAL.plusDays(3), Double.POSITIVE_INFINITY, DayCount.ACT_360));
        assertThrows(IllegalArgumentException.class,
                () -> new RateFuture(VAL, VAL.plusMonths(3), 0.03, DayCount.ACT_360, -1, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new RateFuture(VAL, VAL.plusMonths(3), 0.03, DayCount.ACT_360, 0.1, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new OisSwap(VAL, VAL.plusYears(1), 0.03, 5, DayCount.ACT_360, CAL, MF));
        assertThrows(IllegalArgumentException.class, () -> new InterestRateSwap(VAL, VAL.plusYears(1), 0.03,
                1, DayCount.ACT_360, 7, DayCount.ACT_360, CAL, MF));
        assertThrows(IllegalArgumentException.class,
                () -> new CurvePair(ois, DiscountCurve.of(VAL.plusDays(1), new double[] {1},
                        new double[] {0.97}, ll)));
        assertThrows(IllegalStateException.class, () -> MultiCurveBootstrap.solveLinear(
                new double[][] {{1, 2}, {2, 4}}, new double[] {1, 1}));
        // An instrument whose quote cannot be met on a positive curve still
        // solves (negative rates are allowed) and reprices.
        Deposit neg = new Deposit(VAL, VAL.plusMonths(6), -0.005, DayCount.ACT_360);
        DiscountCurve n = MultiCurveBootstrap.bootstrap(VAL, List.of(neg), CurveInterpolation.MONOTONE_CONVEX);
        assertEquals(-0.005, neg.impliedQuote(n, n), 1e-12);
        assertNotEquals(1.0, n.discountFactor(0.5));
        assertEquals(0.02, a.withQuote(0.02).rate(), 0);
        OisSwap o = (OisSwap) oisInstruments().get(4);
        assertEquals(0.01, o.withQuote(0.01).fixedRate(), 0);
        InterestRateSwap s = irs(3, 0.03);
        assertEquals(s.fixedPaymentDates().getLast(), s.pillarDate());
    }
}

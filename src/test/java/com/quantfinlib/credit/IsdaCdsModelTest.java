package com.quantfinlib.credit;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISDA standard model pinned three ways: the contract conventions against
 * dates worked out by hand from the calendar, the closed-form segment
 * integrals against an independent Gauss-Legendre quadrature of the leg
 * integrands built from raw hazards and zero rates (1e-10), and the quote
 * conversions against their own inverses and the credit triangle.
 */
class IsdaCdsModelTest {

    private static final LocalDate TRADE = LocalDate.of(2026, 11, 2);   // a Monday

    private static final double[] DISC_T = {0.25, 1, 2, 5, 10};
    private static final double[] DISC_R = {0.031, 0.033, 0.034, 0.036, 0.038};
    private static final double[] HAZ_T = {1, 3, 5, 7};
    private static final double[] HAZ_H = {0.010, 0.018, 0.025, 0.030};

    private static IsdaCurve disc() {
        return IsdaCurve.ofZeroRates(DISC_T, DISC_R);
    }

    private static IsdaCurve surv() {
        return IsdaCurve.ofHazardRates(HAZ_T, HAZ_H);
    }

    @Test
    void immDatesAndRollRules() {
        assertEquals(LocalDate.of(2026, 12, 20), IsdaCdsModel.nextImmDate(TRADE));
        assertEquals(LocalDate.of(2027, 3, 20), IsdaCdsModel.nextImmDate(LocalDate.of(2026, 12, 20)));
        assertEquals(LocalDate.of(2026, 9, 20), IsdaCdsModel.previousImmDate(TRADE));
        assertEquals(LocalDate.of(2026, 9, 20), IsdaCdsModel.previousImmDate(LocalDate.of(2026, 9, 20)));
        assertEquals(LocalDate.of(2025, 12, 20), IsdaCdsModel.previousImmDate(LocalDate.of(2026, 3, 19)));
        // Semi-annual roll: before 20 Mar -> Dec of y-1+N; [20 Mar, 20 Sep) -> Jun; else Dec.
        assertEquals(LocalDate.of(2030, 12, 20), IsdaCdsModel.standardMaturity(LocalDate.of(2026, 3, 19), 5));
        assertEquals(LocalDate.of(2031, 6, 20), IsdaCdsModel.standardMaturity(LocalDate.of(2026, 3, 20), 5));
        assertEquals(LocalDate.of(2031, 6, 20), IsdaCdsModel.standardMaturity(LocalDate.of(2026, 9, 19), 5));
        assertEquals(LocalDate.of(2031, 12, 20), IsdaCdsModel.standardMaturity(TRADE, 5));
    }

    @Test
    void standardContractDates() {
        IsdaCdsModel.Contract c = IsdaCdsModel.standardContract(TRADE, 5, 0.01, 0.4);
        assertEquals(LocalDate.of(2026, 11, 3), c.stepInDate());
        assertEquals(LocalDate.of(2026, 11, 5), c.cashSettleDate());
        // 20 Sep 2026 is a Sunday: accrual starts Monday 21 Sep.
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 9, 20).getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 21), c.accrualStartDate());
        assertEquals(43 / 360.0, IsdaCdsModel.accruedYearFraction(c), 1e-15);
        // Friday trade settles the following Wednesday.
        assertEquals(LocalDate.of(2026, 11, 11),
                IsdaCdsModel.addBusinessDays(LocalDate.of(2026, 11, 6), 3));

        List<IsdaCdsModel.Period> s = IsdaCdsModel.schedule(c);
        assertEquals(21, s.size());
        assertEquals(LocalDate.of(2026, 9, 21), s.get(0).accrualStart());
        assertEquals(LocalDate.of(2026, 12, 21), s.get(0).accrualEnd());   // 20 Dec 2026 is a Sunday
        assertEquals(91 / 360.0, s.get(0).yearFraction(), 1e-15);
        IsdaCdsModel.Period last = s.get(20);
        assertEquals(LocalDate.of(2031, 12, 21), last.accrualEnd());       // maturity + 1
        assertEquals(DayOfWeek.SATURDAY, LocalDate.of(2031, 12, 20).getDayOfWeek());
        assertEquals(LocalDate.of(2031, 12, 22), last.paymentDate());
        for (int i = 1; i < s.size(); i++) {
            assertEquals(s.get(i - 1).accrualEnd(), s.get(i).accrualStart());
            DayOfWeek d = s.get(i).paymentDate().getDayOfWeek();
            assertTrue(d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY);
        }
    }

    // ---------- independent quadrature ----------

    private static double rtDisc(double t) {
        int n = DISC_T.length;
        if (t <= DISC_T[0]) {
            return DISC_R[0] * t;
        }
        if (t >= DISC_T[n - 1]) {
            return DISC_R[n - 1] * t;
        }
        int i = 1;
        while (DISC_T[i] < t) {
            i++;
        }
        double w = (t - DISC_T[i - 1]) / (DISC_T[i] - DISC_T[i - 1]);
        return (1 - w) * DISC_R[i - 1] * DISC_T[i - 1] + w * DISC_R[i] * DISC_T[i];
    }

    private static double hazardAt(double t) {
        for (int i = 0; i < HAZ_T.length; i++) {
            if (t <= HAZ_T[i]) {
                return HAZ_H[i];
            }
        }
        return HAZ_H[HAZ_H.length - 1];
    }

    private static double cumHazard(double t) {
        double acc = 0, prev = 0;
        for (int i = 0; i < HAZ_T.length && prev < t; i++) {
            double to = Math.min(t, HAZ_T[i]);
            acc += HAZ_H[i] * (to - prev);
            prev = to;
        }
        if (t > prev) {
            acc += HAZ_H[HAZ_H.length - 1] * (t - prev);
        }
        return acc;
    }

    private static final double[] GL_X;
    private static final double[] GL_W;

    static {
        int n = 20;
        GL_X = new double[n];
        GL_W = new double[n];
        for (int i = 0; i < n; i++) {
            double x = Math.cos(Math.PI * (i + 0.75) / (n + 0.5));
            double dp = 0;
            for (int it = 0; it < 100; it++) {
                double p0 = 1, p1 = x;
                for (int k = 2; k <= n; k++) {
                    double p2 = ((2 * k - 1) * x * p1 - (k - 1) * p0) / k;
                    p0 = p1;
                    p1 = p2;
                }
                dp = n * (x * p1 - p0) / (x * x - 1);
                double dx = p1 / dp;
                x -= dx;
                if (Math.abs(dx) < 1e-16) {
                    break;
                }
            }
            GL_X[i] = x;
            GL_W[i] = 2 / ((1 - x * x) * dp * dp);
        }
    }

    /** Integral of f on [a, b], split at every curve node, 20-point Gauss-Legendre on each piece. */
    private static double integrate(java.util.function.DoubleUnaryOperator f, double a, double b) {
        java.util.TreeSet<Double> cuts = new java.util.TreeSet<>();
        cuts.add(a);
        cuts.add(b);
        for (double t : DISC_T) {
            if (t > a && t < b) {
                cuts.add(t);
            }
        }
        for (double t : HAZ_T) {
            if (t > a && t < b) {
                cuts.add(t);
            }
        }
        double sum = 0;
        Double prev = null;
        for (double c : cuts) {
            if (prev != null) {
                double lo = prev, hi = c, mid = 0.5 * (lo + hi), half = 0.5 * (hi - lo);
                for (int i = 0; i < GL_X.length; i++) {
                    sum += half * GL_W[i] * f.applyAsDouble(mid + half * GL_X[i]);
                }
            }
            prev = c;
        }
        return sum;
    }

    private static double yrs(LocalDate d) {
        return ChronoUnit.DAYS.between(TRADE, d) / 365.0;
    }

    @Test
    void legsMatchQuadratureTo1e10() {
        IsdaCdsModel.Contract c = IsdaCdsModel.standardContract(TRADE, 5, 0.01, 0.4);
        double dfCs = Math.exp(-rtDisc(yrs(c.cashSettleDate())));
        double tM = yrs(c.maturityDate());
        double prot = 0.6 * integrate(t -> hazardAt(t) * Math.exp(-cumHazard(t) - rtDisc(t)), 0, tM)
                / dfCs;
        assertEquals(prot, IsdaCdsModel.protectionLeg(c, disc(), surv()), 1e-10);

        double rpv01 = 0;
        for (IsdaCdsModel.Period p : IsdaCdsModel.schedule(c)) {
            double yf = ChronoUnit.DAYS.between(p.accrualStart(), p.accrualEnd()) / 360.0;
            double tS = yrs(p.accrualStart().minusDays(1));
            double tE = yrs(p.accrualEnd().minusDays(1));
            rpv01 += yf * Math.exp(-cumHazard(tE) - rtDisc(yrs(p.paymentDate())));
            double a = yf / (tE - tS);
            rpv01 += integrate(t -> a * (t - tS) * hazardAt(t) * Math.exp(-cumHazard(t) - rtDisc(t)),
                    Math.max(tS, 0), tE);
        }
        rpv01 /= dfCs;
        assertEquals(rpv01, IsdaCdsModel.riskyAnnuityDirty(c, disc(), surv()), 1e-10);
        assertTrue(rpv01 > 4 && rpv01 < 5, "5y risky annuity " + rpv01);
    }

    @Test
    void segmentIntegralsAreStableNearZero() {
        for (double k : new double[]{-1e-9, 0, 1e-9, 1e-7, 1e-4, 2e-3, 0.5}) {
            double dt = 0.7;
            double ref1 = integrateExp(k, dt, false);
            double ref2 = integrateExp(k, dt, true);
            assertEquals(ref1, IsdaCdsModel.e1(k, dt), 1e-14, "e1 k=" + k);
            assertEquals(ref2, IsdaCdsModel.e2(k, dt), 1e-14, "e2 k=" + k);
        }
    }

    private static double integrateExp(double k, double dt, boolean weighted) {
        double sum = 0, half = dt / 2;
        for (int i = 0; i < GL_X.length; i++) {
            double t = half + half * GL_X[i];
            sum += half * GL_W[i] * (weighted ? t : 1) * Math.exp(-k * t);
        }
        return sum;
    }

    @Test
    void upfrontQuoteConversionsRoundTrip() {
        IsdaCurve d = disc();
        for (double coupon : new double[]{IsdaCdsModel.COUPON_100BP, IsdaCdsModel.COUPON_500BP}) {
            IsdaCdsModel.Contract c = IsdaCdsModel.standardContract(TRADE, 5, coupon,
                    IsdaCdsModel.STANDARD_RECOVERY);
            for (double quoted : new double[]{0.0025, 0.01, 0.03, 0.08, 0.20}) {
                double up = IsdaCdsModel.quotedSpreadToUpfront(c, quoted, d);
                assertEquals(quoted, IsdaCdsModel.upfrontToQuotedSpread(c, up, d), 1e-12);
                // Sign: quote above coupon means the buyer pays points.
                if (quoted > coupon) {
                    assertTrue(up > 0);
                } else if (quoted < coupon) {
                    assertTrue(up < 0);
                }
            }
            assertEquals(0, IsdaCdsModel.quotedSpreadToUpfront(c, coupon, d), 1e-13);
        }
    }

    @Test
    void creditTriangleAndCleanDirty() {
        IsdaCdsModel.Contract c = IsdaCdsModel.standardContract(TRADE, 5, 0.01, 0.4);
        IsdaCurve flat = IsdaCurve.flat(0.02);
        double par = IsdaCdsModel.parSpread(c, disc(), flat);
        // spread ~ h (1 - R) = 120bp; the 365/360 accrual pushes it a little lower.
        assertEquals(0.012, par, 0.0003);
        double clean = IsdaCdsModel.cleanUpfront(c, disc(), flat);
        double dirty = IsdaCdsModel.dirtyUpfront(c, disc(), flat);
        assertEquals(clean - 0.01 * IsdaCdsModel.accruedYearFraction(c), dirty, 1e-15);
        // Upfront ~ (par - coupon) * clean RPV01 exactly by construction.
        assertEquals((par - 0.01) * IsdaCdsModel.riskyAnnuityClean(c, disc(), flat), clean, 1e-14);
    }

    @Test
    void bootstrapRepricesEveryQuote() {
        int[] tenors = {1, 3, 5, 7, 10};
        double[] spreads = {0.004, 0.007, 0.010, 0.012, 0.013};
        IsdaCurve s = IsdaCdsModel.bootstrap(TRADE, tenors, spreads, 0.4, disc());
        for (int i = 0; i < tenors.length; i++) {
            IsdaCdsModel.Contract c = IsdaCdsModel.standardContract(TRADE, tenors[i], 0.01, 0.4);
            assertEquals(spreads[i], IsdaCdsModel.parSpread(c, disc(), s), 1e-12);
        }
        assertThrows(IllegalArgumentException.class,
                () -> IsdaCdsModel.bootstrap(TRADE, new int[]{5, 3}, new double[]{0.01, 0.01}, 0.4, disc()));
    }

    @Test
    void curveBasicsAndValidation() {
        IsdaCurve h = IsdaCurve.ofHazardRates(HAZ_T, HAZ_H);
        assertEquals(Math.exp(-cumHazard(4.2)), h.value(4.2), 1e-15);
        assertEquals(Math.exp(-cumHazard(12)), h.value(12), 1e-14);
        assertEquals(DISC_R[2], disc().zeroRate(2), 1e-15);
        assertEquals(DISC_R[0], disc().zeroRate(0.1), 1e-15);
        assertEquals(DISC_T.length, disc().timesLength());
        assertEquals(DISC_T[1], disc().timesCopy()[1]);
        assertNotEquals(0, disc().time(0));
        assertThrows(IllegalArgumentException.class, () -> IsdaCurve.ofZeroRates(new double[]{2, 1}, new double[]{0, 0}));
        assertThrows(IllegalArgumentException.class, () -> IsdaCurve.ofHazardRates(new double[]{1}, new double[]{-1}));
        assertThrows(IllegalArgumentException.class, () -> disc().zeroRate(0));
        assertThrows(IllegalArgumentException.class,
                () -> IsdaCdsModel.standardContract(TRADE, 0, 0.01, 0.4));
        assertThrows(IllegalArgumentException.class,
                () -> IsdaCdsModel.standardContract(TRADE, 5, 0.01, 1.0));
        IsdaCdsModel.Contract c = IsdaCdsModel.standardContract(TRADE, 5, 0.01, 0.4);
        assertThrows(IllegalArgumentException.class,
                () -> IsdaCdsModel.upfrontToQuotedSpread(c, 0.9, disc()));
        assertThrows(IllegalArgumentException.class,
                () -> IsdaCdsModel.flatHazardForParSpread(c, -0.01, disc()));
    }
}

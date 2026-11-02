package com.quantfinlib.credit;

import com.quantfinlib.credit.IsdaCdsModel.Contract;
import com.quantfinlib.credit.IsdaCdsModel.Conventions;
import com.quantfinlib.credit.IsdaCdsModel.Period;
import com.quantfinlib.rates.BusinessCalendar;
import com.quantfinlib.rates.HolidayCalendars;
import com.quantfinlib.rates.HolidayCalendars.Market;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holiday calendars and the half-day accrual option in the ISDA model.
 * The premium leg is checked against an independent re-implementation in
 * this test: flat curves, the schedule rebuilt by hand from the calendar,
 * and the accrual-on-default integral done by composite Simpson rule on
 * the raw integrand rather than by the model's closed form.
 */
class IsdaConventionsTest {

    private static final double R = 0.035;
    private static final double H = 0.02;
    private static final BusinessCalendar TOKYO = HolidayCalendars.calendar(Market.TOKYO, 2025, 2032);
    private static final BusinessCalendar NY = HolidayCalendars.calendar(Market.NEW_YORK_FED, 2025, 2032);

    @Test
    void defaultConventionsReproduceWeekendsOnlyResults() {
        LocalDate t = LocalDate.of(2026, 11, 24);
        Contract c = IsdaCdsModel.standardContract(t, 5, 0.01, 0.4);
        IsdaCurve d = IsdaCurve.flat(R);
        IsdaCurve s = IsdaCurve.flat(H);
        assertEquals(IsdaCdsModel.riskyAnnuityDirty(c, d, s),
                IsdaCdsModel.riskyAnnuityDirty(c, d, s,
                        Conventions.of(BusinessCalendar.weekendsOnly())), 0.0);
        assertEquals(c, IsdaCdsModel.standardContract(t, 5, 0.01, 0.4, BusinessCalendar.weekendsOnly()));
        assertEquals(LocalDate.of(2026, 11, 27), c.cashSettleDate());
    }

    @Test
    void holidayCalendarMovesSettlementAccrualStartAndPayments() {
        Contract ny = IsdaCdsModel.standardContract(LocalDate.of(2026, 11, 24), 5, 0.01, 0.4, NY);
        assertEquals(LocalDate.of(2026, 11, 30), ny.cashSettleDate());

        // Trade 20 Mar 2026 in Tokyo: the IMM date is the vernal equinox holiday,
        // so it rolls to Mon 23 Mar, after step-in; accrual starts on the
        // previous IMM date, Sat 20 Dec 2025, adjusted to Mon 22 Dec.
        Contract tk = IsdaCdsModel.standardContract(LocalDate.of(2026, 3, 20), 1, 0.01, 0.4, TOKYO);
        assertEquals(LocalDate.of(2025, 12, 22), tk.accrualStartDate());
        List<Period> sched = IsdaCdsModel.schedule(tk, TOKYO);
        assertEquals(LocalDate.of(2026, 3, 23), sched.get(0).paymentDate());
        assertEquals(LocalDate.of(2026, 3, 23), sched.get(1).accrualStart());
        Contract tkWeekend = IsdaCdsModel.standardContract(LocalDate.of(2026, 3, 20), 1, 0.01, 0.4);
        assertEquals(LocalDate.of(2026, 3, 20), tkWeekend.accrualStartDate());
    }

    @Test
    void premiumLegMatchesIndependentReimplementation() {
        for (boolean half : new boolean[]{false, true}) {
            Contract c = IsdaCdsModel.standardContract(LocalDate.of(2026, 1, 15), 3, 0.01, 0.4, TOKYO);
            Conventions conv = Conventions.of(TOKYO).withHalfDayAccrual(half);
            double model = IsdaCdsModel.riskyAnnuityDirty(c, IsdaCurve.flat(R), IsdaCurve.flat(H), conv);
            double ref = referenceRpv01(c, TOKYO, half);
            assertEquals(ref, model, 1e-11, "half=" + half);
        }
    }

    @Test
    void halfDayAddsExpectedAmountAndLowersUpfront() {
        Contract c = IsdaCdsModel.standardContract(LocalDate.of(2026, 11, 2), 5, 0.01, 0.4, NY);
        IsdaCurve d = IsdaCurve.flat(R);
        IsdaCurve s = IsdaCurve.flat(H);
        Conventions off = Conventions.of(NY);
        Conventions on = off.withHalfDayAccrual(true);
        double diff = IsdaCdsModel.riskyAnnuityDirty(c, d, s, on) - IsdaCdsModel.riskyAnnuityDirty(c, d, s, off);
        // Expected: sum over periods of a / 730 * integral h Q P dt (flat curves, closed form here).
        LocalDate t0 = c.tradeDate();
        double k = H + R;
        double exp = 0;
        for (Period p : IsdaCdsModel.schedule(c, NY)) {
            double tS = yrs(t0, p.accrualStart().minusDays(1));
            double tE = yrs(t0, p.accrualEnd().minusDays(1));
            double from = Math.max(tS, yrs(t0, c.stepInDate().minusDays(1)));
            double a = p.yearFraction() / (tE - tS);
            exp += a / 730.0 * H / k * (Math.exp(-k * from) - Math.exp(-k * tE));
        }
        exp /= Math.exp(-R * yrs(t0, c.cashSettleDate()));
        assertEquals(exp, diff, 1e-14);
        assertTrue(diff > 0 && diff < 1e-3);
        assertTrue(IsdaCdsModel.cleanUpfront(c, d, s, on) < IsdaCdsModel.cleanUpfront(c, d, s, off));
        // Round trips stay consistent under the option.
        double up = IsdaCdsModel.quotedSpreadToUpfront(c, 0.015, d, on);
        assertEquals(0.015, IsdaCdsModel.upfrontToQuotedSpread(c, up, d, on), 1e-10);
    }

    @Test
    void bootstrapRepricesUnderHolidayConventions() {
        LocalDate t = LocalDate.of(2026, 11, 24);
        int[] tenors = {1, 3, 5};
        double[] spreads = {0.006, 0.009, 0.012};
        Conventions conv = Conventions.of(NY).withHalfDayAccrual(true);
        IsdaCurve d = IsdaCurve.flat(R);
        IsdaCurve s = IsdaCdsModel.bootstrap(t, tenors, spreads, 0.4, d, conv);
        for (int i = 0; i < tenors.length; i++) {
            Contract c = IsdaCdsModel.standardContract(t, tenors[i], spreads[i], 0.4, NY);
            assertEquals(spreads[i], IsdaCdsModel.parSpread(c, d, s, conv), 1e-10);
        }
    }

    private static double yrs(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b) / 365.0;
    }

    /** Independent dirty RPV01: hand-built schedule, Simpson quadrature of the accrual integrand. */
    private static double referenceRpv01(Contract c, BusinessCalendar cal, boolean half) {
        LocalDate t0 = c.tradeDate();
        // Schedule: quarterly back from maturity, adjusted following on the calendar.
        java.util.ArrayList<LocalDate> raw = new java.util.ArrayList<>();
        for (LocalDate d = c.maturityDate(); d.isAfter(c.accrualStartDate()); d = d.minusMonths(3)) {
            raw.add(0, d);
        }
        double sum = 0;
        LocalDate start = c.accrualStartDate();
        for (int i = 0; i < raw.size(); i++) {
            LocalDate pay = raw.get(i);
            while (!cal.isBusinessDay(pay)) {
                pay = pay.plusDays(1);
            }
            LocalDate end = i == raw.size() - 1 ? c.maturityDate().plusDays(1) : pay;
            if (!end.isAfter(c.stepInDate())) {
                start = end;
                continue;
            }
            double yf = ChronoUnit.DAYS.between(start, end) / 360.0;
            double tS = yrs(t0, start.minusDays(1));
            double tE = yrs(t0, end.minusDays(1));
            sum += yf * Math.exp(-H * tE) * Math.exp(-R * yrs(t0, pay));
            double a = yf / (tE - tS);
            double from = Math.max(tS, yrs(t0, c.stepInDate().minusDays(1)));
            double off = half ? 0.5 / 365 : 0;
            int n = 2000;
            double hgrid = (tE - from) / n;
            double acc = 0;
            for (int j = 0; j <= n; j++) {
                double t = from + j * hgrid;
                double f = a * (t - tS + off) * H * Math.exp(-(H + R) * t);
                acc += (j == 0 || j == n ? 1 : (j % 2 == 1 ? 4 : 2)) * f;
            }
            sum += acc * hgrid / 3;
            start = end;
        }
        return sum / Math.exp(-R * yrs(t0, c.cashSettleDate()));
    }
}

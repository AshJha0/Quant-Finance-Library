package com.quantfinlib.alpha;

import com.quantfinlib.backtest.validation.BlockBootstrap;
import com.quantfinlib.backtest.validation.SharpeValidation;
import com.quantfinlib.risk.RiskMetrics;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fama-MacBeth premia, calendar anomalies, block bootstrap, min track record. */
class AlphaResearchRoundTest {

    // ------------------------------------------------------------------
    // Fama-MacBeth — a priced factor earns its premium, a dud earns zero
    // ------------------------------------------------------------------

    @Test
    void famaMacBethPricesThePlantedFactorAndNotTheDud() {
        Random rnd = new Random(17);
        int periods = 60;
        int assets = 50;
        double[][][] x = new double[periods][assets][2];
        double[][] r = new double[periods][assets];
        for (int t = 0; t < periods; t++) {
            for (int a = 0; a < assets; a++) {
                x[t][a][0] = rnd.nextGaussian();          // the priced factor
                x[t][a][1] = rnd.nextGaussian();          // the dud
                r[t][a] = 0.01 * x[t][a][0] + 0.005 * rnd.nextGaussian();
            }
        }
        // A NaN asset (out of the cross-section that period) must be
        // skipped, not poison the regression.
        r[7][3] = Double.NaN;

        FamaMacBeth.Result fm = FamaMacBeth.fit(x, r);
        assertEquals(60, fm.periodsUsed());
        assertEquals(0.01, fm.premia()[0], 0.001,
                "the planted premium, in return units: " + fm.premia()[0]);
        assertTrue(fm.tStats()[0] > 5, "and it is unambiguous: t = " + fm.tStats()[0]);
        assertEquals(0.0, fm.premia()[1], 0.001, "the dud earns nothing");
        assertTrue(Math.abs(fm.tStats()[1]) < 2.5, "and knows it: " + fm.tStats()[1]);
        assertTrue(Math.abs(fm.interceptTStat()) < 2.5,
                "no unexplained returns left: intercept t = " + fm.interceptTStat());

        assertThrows(IllegalArgumentException.class,
                () -> FamaMacBeth.fit(new double[5][10][2], new double[5][10]),
                "fewer than 12 periods supplied");

        // A SINGULAR cross-section (a factor constant across assets that
        // period) is skipped and counted, like a thin one — one bad
        // period must not abort 59 good ones.
        for (int a = 0; a < assets; a++) {
            x[30][a][1] = 1.0;                      // collinear with the intercept
        }
        FamaMacBeth.Result skipped = FamaMacBeth.fit(x, r);
        assertEquals(59, skipped.periodsUsed(), "the singular period is skipped");
        assertEquals(0.01, skipped.premia()[0], 0.001, "and the premium survives");

        // Infinity is a data error, not a missing name: fail fast.
        double[][] rBad = new double[periods][assets];
        for (int t = 0; t < periods; t++) {
            rBad[t] = r[t].clone();
        }
        rBad[5][5] = Double.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> FamaMacBeth.fit(x, rBad),
                "infinite return = broken adjustment upstream");

        // The USABLE-cross-sections gate (distinct from the length gate):
        // enough periods supplied, but too many are too thin to price.
        double[][][] thinX = new double[20][assets][2];
        double[][] thinR = new double[20][assets];
        for (int t = 0; t < 20; t++) {
            for (int a = 0; a < assets; a++) {
                thinX[t][a][0] = rnd.nextGaussian();
                thinX[t][a][1] = rnd.nextGaussian();
                thinR[t][a] = t < 10 ? Double.NaN : 0.01 * thinX[t][a][0];
            }
        }
        assertThrows(IllegalArgumentException.class, () -> FamaMacBeth.fit(thinX, thinR),
                "only 10 usable cross-sections — premia need a time series");
    }

    // ------------------------------------------------------------------
    // Calendar anomalies — the profile AND the significance
    // ------------------------------------------------------------------

    @Test
    void calendarProfilesRecoverPlantedSeasonalityExactly() {
        // 400 weekdays from Monday 2024-01-01: Mondays planted at -0.2%,
        // the rest at +0.05%, with an alternating ±1bp wiggle so group
        // stdevs are nonzero and group means stay EXACT (even counts).
        int n = 400;
        double[] returns = new double[n];
        long[] stamps = new long[n];
        LocalDate d = LocalDate.of(2024, 1, 1);
        int[] perDay = new int[7];
        for (int i = 0; i < n; ) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                int dow = d.getDayOfWeek().getValue() - 1;
                double base = dow == 0 ? -0.002 : 0.0005;
                returns[i] = base + (perDay[dow]++ % 2 == 0 ? 1e-4 : -1e-4);
                stamps[i] = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                i++;
            }
            d = d.plusDays(1);
        }
        CalendarAnomalies.DayOfWeekProfile profile =
                CalendarAnomalies.dayOfWeek(returns, stamps);
        assertEquals(80, profile.count()[0], "80 full weeks of Mondays");
        assertEquals(-0.002, profile.meanReturn()[0], 1e-12, "the planted Monday effect");
        assertTrue(profile.tStat()[0] < -5, "and it is significant: " + profile.tStat()[0]);
        assertEquals(0.0005, profile.meanReturn()[2], 1e-12, "Wednesdays are ordinary");
        assertEquals(0, profile.count()[5], "no Saturdays in the data");
        assertTrue(Double.isNaN(profile.meanReturn()[6]), "empty day reports NaN, not 0");

        // Turn of month: plant +0.3% inside the (1 before, 3 after)
        // window, +0.01% outside — the split and the Welch t find it.
        double[] tom = new double[n];
        int[] parity = new int[2];
        for (int i = 0; i < n; i++) {
            LocalDate date = java.time.Instant.ofEpochMilli(stamps[i])
                    .atZone(ZoneOffset.UTC).toLocalDate();
            boolean inside = date.getDayOfMonth() <= 3
                    || date.getDayOfMonth() > date.lengthOfMonth() - 1;
            double base = inside ? 0.003 : 0.0001;
            tom[i] = base + (parity[inside ? 0 : 1]++ % 2 == 0 ? 1e-4 : -1e-4);
        }
        CalendarAnomalies.TurnOfMonth split =
                CalendarAnomalies.turnOfMonth(tom, stamps, 1, 3);
        assertEquals(0.003, split.insideMean(), 1.1e-4, "the planted window premium");
        assertEquals(0.0001, split.outsideMean(), 1.1e-4);
        assertTrue(split.tStat() > 10, "unambiguous: " + split.tStat());
        assertEquals(n, split.insideCount() + split.outsideCount(), "every day lands somewhere");

        assertThrows(IllegalArgumentException.class,
                () -> CalendarAnomalies.turnOfMonth(tom, stamps, 0, 0));
    }

    // ------------------------------------------------------------------
    // Block bootstrap — the Sharpe's sampling distribution, honestly
    // ------------------------------------------------------------------

    @Test
    void blockBootstrapWidensWhereAutocorrelationDemandsIt() {
        Random rnd = new Random(23);
        int n = 1_000;
        double[] iid = new double[n];
        for (int i = 0; i < n; i++) {
            iid[i] = 5e-4 + 0.01 * rnd.nextGaussian();
        }
        double sampleSharpe = RiskMetrics.sharpeRatio(iid, 0, 252);
        double[] samples = BlockBootstrap.sharpeSamples(iid, 10, 500, 252, 7);
        assertEquals(500, samples.length);
        assertTrue(samples[0] <= samples[499], "sorted ascending, ready for percentiles");
        assertEquals(sampleSharpe, MathUtils.percentileSorted(samples, 0.5), 0.35,
                "the distribution centers on the sample estimate");
        assertArrayEquals(samples, BlockBootstrap.sharpeSamples(iid, 10, 500, 252, 7),
                "deterministic per seed: replayable");

        // THE point of blocks: on autocorrelated returns the honest
        // uncertainty is wider, and an iid resample (L = 1) understates
        // it — the classic route to false confidence.
        double[] ar = new double[n];
        double prev = 0;
        for (int i = 0; i < n; i++) {
            ar[i] = 0.9 * prev + 0.01 * rnd.nextGaussian();
            prev = ar[i];
        }
        double stdBlocked = MathUtils.stdDev(
                BlockBootstrap.sharpeSamples(ar, 25, 400, 252, 3));
        double stdIid = MathUtils.stdDev(
                BlockBootstrap.sharpeSamples(ar, 1, 400, 252, 3));
        assertTrue(stdBlocked > 1.2 * stdIid,
                "blocks preserve the dependence the iid resample destroys: "
                        + stdBlocked + " vs " + stdIid);

        assertThrows(IllegalArgumentException.class,
                () -> BlockBootstrap.sharpeSamples(iid, 0, 500, 252, 7));
        assertThrows(IllegalArgumentException.class,
                () -> BlockBootstrap.sharpeSamples(iid, 10, 50, 252, 7));
    }

    // ------------------------------------------------------------------
    // Minimum track record — PSR's closed-form inverse, round-tripped
    // ------------------------------------------------------------------

    @Test
    void minTrackRecordLengthRoundTripsThroughProbabilisticSharpe() {
        double sr = 0.1;                      // per period
        double n = SharpeValidation.minTrackRecordLength(sr, 0, -0.5, 4, 0.95);
        assertTrue(n > 100 && n < 1_000, "a plausible track record: " + n);
        // The round trip: AT the computed length, PSR clears the bar...
        assertTrue(SharpeValidation.probabilisticSharpe(sr, 0,
                        (int) Math.ceil(n), -0.5, 4) >= 0.95 - 1e-3,
                "PSR at n* clears the confidence");
        // ...and meaningfully short of it, it does not.
        assertTrue(SharpeValidation.probabilisticSharpe(sr, 0,
                (int) (n * 0.7), -0.5, 4) < 0.95);
        // More confidence demands a longer record, always.
        assertTrue(SharpeValidation.minTrackRecordLength(sr, 0, -0.5, 4, 0.99) > n);
        // No record length proves an edge the record does not show.
        assertEquals(Double.POSITIVE_INFINITY,
                SharpeValidation.minTrackRecordLength(0.0, 0.1, 0, 3, 0.95));
        assertThrows(IllegalArgumentException.class,
                () -> SharpeValidation.minTrackRecordLength(0.1, 0, 0, 3, 1.0));
    }
}

package com.quantfinlib.crb;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CRB overnight persistence + the hedge-universe builder. */
class CrbPersistenceAndUniverseTest {

    // ------------------------------------------------------------------
    // Overnight checkpoint
    // ------------------------------------------------------------------

    @Test
    void bookAndEngineSurviveTheOvernightCheckpoint(@TempDir Path dir) throws IOException {
        CentralRiskBook day1 = new CentralRiskBook();
        day1.bookCashEquity("cash-desk", "SPY", 20_000, 500);
        day1.bookEquityOption("vol-desk", "SPY", OptionType.CALL,
                -50, 100, 500, 520, 0.03, 0.015, 0.2, 0.25);
        day1.bookFxSpot("fx-desk", "EURUSD", 10_000_000, 1.10);
        day1.bookNdf("em-desk", "USDINR", 5_000_000, 84.0);
        InternalizationEngine engine1 = new InternalizationEngine(20_000_000, 0.5);
        engine1.decide(day1.exposure("CCY:EUR"), -2_000_000, 4);
        engine1.decide(day1.exposure("CCY:EUR"), 30_000_000, 4);   // partially routed

        Path file = dir.resolve("crb.qfl");
        try (Checkpoint.Writer w = Checkpoint.writer(file)) {
            w.section("crb.book", day1::writeState);
            w.section("crb.internalization", engine1::writeState);
        }

        // The morning after: FRESH instances, restored state.
        CentralRiskBook day2 = new CentralRiskBook();
        InternalizationEngine engine2 = new InternalizationEngine(20_000_000, 0.5);
        Checkpoint.Reader r = Checkpoint.reader(file);
        assertTrue(r.section("crb.book", day2::readState));
        assertTrue(r.section("crb.internalization", engine2::readState));

        assertEquals(day1.exposure("EQ:SPY"), day2.exposure("EQ:SPY"), 1e-9);
        assertEquals(day1.exposure("EQVEGA:SPY"), day2.exposure("EQVEGA:SPY"), 1e-9);
        assertEquals(day1.exposure("CCY:EUR"), day2.exposure("CCY:EUR"), 1e-9);
        assertEquals(day1.exposure("CCY:USD"), day2.exposure("CCY:USD"), 1e-9);
        assertEquals(day1.deskExposure("vol-desk", "EQ:SPY"),
                day2.deskExposure("vol-desk", "EQ:SPY"), 1e-9,
                "per-desk attribution survives the night");
        assertEquals(day1.pendingFixing("USDINR"), day2.pendingFixing("USDINR"), 1e-9);
        assertEquals(day1.nettingEfficiency(), day2.nettingEfficiency(), 1e-12);
        assertEquals(day1.flowsBooked(), day2.flowsBooked());
        assertEquals(day1.factors().size(), day2.factors().size());
        assertEquals(day1.factors().name(0), day2.factors().name(0),
                "factor ids keep registration order across the restore");
        assertEquals(engine1.internalizationRate(), engine2.internalizationRate(), 1e-12,
                "restored trust is earned trust — the rate carries its history");

        // Day 2 keeps booking on the restored book, seamlessly.
        day2.bookFxSpot("fx-desk", "EURUSD", -4_000_000, 1.10);
        assertEquals(day1.exposure("CCY:EUR") - 4_000_000, day2.exposure("CCY:EUR"), 1e-6);

        // Config mismatch: an internalization rate earned under one
        // warehouse limit says NOTHING about another — refuse, loudly.
        InternalizationEngine wrongConfig = new InternalizationEngine(10_000_000, 0.5);
        Checkpoint.Reader r2 = Checkpoint.reader(file);
        assertThrows(IllegalStateException.class,
                () -> r2.section("crb.internalization", wrongConfig::readState));

        // Restoring into a book that already holds positions is refused.
        Checkpoint.Reader r3 = Checkpoint.reader(file);
        assertThrows(IllegalStateException.class,
                () -> r3.section("crb.book", day2::readState));
    }

    @Test
    void ledgerRoundTripsEveryFieldAndIgnoresFullyRoutedFlow(@TempDir Path dir)
            throws IOException {
        CrbPnlLedger ledger = new CrbPnlLedger();
        ledger.onInternalized(1_000_000, 4, 1);        // captured 300, paid 100
        ledger.onHedge(-500_000, 2);                   // cost 100 (abs notional)
        ledger.onRoute(200_000, new CrbRouter.Allocation(0, new double[0], 200_000, 3));
        assertEquals(300, ledger.spreadCaptured(), 1e-9);
        assertEquals(100, ledger.improvementPaid(), 1e-9);
        assertEquals(100, ledger.hedgeCost(), 1e-9);
        assertEquals(60, ledger.routerCost(), 1e-9);
        assertEquals(1, ledger.internalizations());
        assertEquals(1, ledger.hedges());
        assertEquals(300 - 100 - 60, ledger.netEconomics(), 1e-9);

        // A fully-routed decision earns NOTHING and counts as nothing —
        // the internalization stat must not inflate on flow we passed on.
        ledger.onDecision(new InternalizationEngine.Decision(0, 5_000_000, 0), 4);
        assertEquals(1, ledger.internalizations(), "routed flow is not internalization");
        assertEquals(300, ledger.spreadCaptured(), 1e-9);

        // The gate: improvement beyond the half spread means the desk
        // would be PAYING clients to trade.
        assertThrows(IllegalArgumentException.class,
                () -> ledger.onInternalized(1_000_000, 4, 5));

        // EVERY field survives the round-trip — not just netEconomics,
        // which a swapped hedgeCost/routerCost would leave unchanged.
        Path file = dir.resolve("ledger.qfl");
        try (Checkpoint.Writer w = Checkpoint.writer(file)) {
            w.section("crb.pnl", ledger::writeState);
        }
        CrbPnlLedger restored = new CrbPnlLedger();
        assertTrue(Checkpoint.reader(file).section("crb.pnl", restored::readState));
        assertEquals(ledger.spreadCaptured(), restored.spreadCaptured(), 0.0);
        assertEquals(ledger.improvementPaid(), restored.improvementPaid(), 0.0);
        assertEquals(ledger.hedgeCost(), restored.hedgeCost(), 0.0);
        assertEquals(ledger.routerCost(), restored.routerCost(), 0.0);
        assertEquals(ledger.internalizations(), restored.internalizations());
        assertEquals(ledger.hedges(), restored.hedges());
    }

    // ------------------------------------------------------------------
    // Hedge universe
    // ------------------------------------------------------------------

    @Test
    void universeBuildsTheMatrixAndTheOptimizerFlattensWhatItSpans() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookFxSpot("fx-desk", "EURUSD", 10_000_000, 1.10);
        book.bookNdf("em-desk", "USDINR", 5_000_000, 84.0);
        // CCY:EUR +10M, CCY:USD -6M (spot -11M + NDF +5M), CCY:INR -420M.

        CrbHedgeUniverse universe = new CrbHedgeUniverse(book.factors())
                .addFxForward("EURUSD-1W", "EURUSD", 1.10, 1.0)
                .addSingleFactor("INR-OUTRIGHT", "CCY:INR", 2.0);
        assertEquals(2, universe.size());
        assertEquals("EURUSD-1W", universe.name(0));

        int n = book.factors().size();
        double[][] cov = new double[n][n];
        for (int f = 0; f < n; f++) {
            cov[f][f] = 1e-4;
        }
        double[] e = book.netExposures();
        double[][] loadings = universe.loadings();
        double[] h = HedgeOptimizer.hedge(e, cov, loadings, universe.costs(), 0);

        double[] residual = HedgeOptimizer.residual(e, loadings, h);
        int inr = book.factors().idIfPresent("CCY:INR");
        assertEquals(420_000_000, h[1], 1, "the outright flattens INR exactly");
        assertEquals(0, residual[inr], 1e-3);
        double before = HedgeOptimizer.risk(e, cov);
        double after = HedgeOptimizer.risk(residual, cov);
        assertTrue(after < 0.05 * before,
                "the universe-built hedge removed the book's risk: "
                        + after + " vs " + before);
    }

    @Test
    void indexFutureHedgesSingleNamesThroughTheCovarianceNotABetaTable() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookCashEquity("cash-desk", "AAPL", 50_000, 200);       // +10M
        CrbHedgeUniverse universe = new CrbHedgeUniverse(book.factors())
                .addSingleFactor("ES-FUTURE", "EQ:SPX", 0.5);        // hedge-only factor

        // The hedge-only factor registered cleanly: zero book exposure,
        // arrays stay coherent at the grown registry size.
        assertEquals(0, book.exposure("EQ:SPX"), 1e-12);
        int n = book.factors().size();
        assertEquals(2, n);
        double[] e = book.netExposures();
        assertEquals(n, e.length);

        double sa2 = 4e-4;                        // AAPL variance
        double sb2 = 2.25e-4;                     // index variance
        double rho = 0.8;
        double cab = rho * Math.sqrt(sa2 * sb2);
        double[][] cov = {{sa2, cab}, {cab, sb2}};
        double[] h = HedgeOptimizer.hedge(e, cov, universe.loadings(),
                universe.costs(), 0);
        assertEquals(-10_000_000 * cab / sb2, h[0], 1e-3,
                "the regression hedge falls out of the covariance");

        // The router then works the hedge: the CRB's own inventory first.
        CrbRouter.Allocation alloc = CrbRouter.route(Math.abs(h[0]), 2_000_000,
                new CrbRouter.DarkVenue[0], 4, 1);
        assertEquals(2_000_000, alloc.internal(), 1e-9);
        assertEquals(Math.abs(h[0]) - 2_000_000, alloc.lit(), 1e-6);

        assertThrows(IllegalArgumentException.class,
                () -> universe.add("BAD", -1, new String[]{"EQ:SPX"}, new double[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> universe.add("BAD", 1, new String[]{"EQ:SPX"}, new double[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> universe.addFxForward("BAD", "EUR", 1.1, 1));
    }
}

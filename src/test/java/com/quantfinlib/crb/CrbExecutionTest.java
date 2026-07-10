package com.quantfinlib.crb;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CRB flow economics and execution: skewed quotes, internalization, hedging, routing. */
class CrbExecutionTest {

    // ------------------------------------------------------------------
    // Skewed quoting
    // ------------------------------------------------------------------

    @Test
    void skewShadesBothQuotesTowardReducingInventory() {
        // Half-limit long, half the spread available as skew: -2.5 bps.
        SkewedQuoter.Quote q = SkewedQuoter.quote(100, 10, 50, 100, 0.5);
        assertEquals(-2.5, q.skewBps(), 1e-12, "long book shades DOWN");
        assertEquals(100 * (1 - 12.5e-4), q.bid(), 1e-12);
        assertEquals(100 * (1 + 7.5e-4), q.ask(), 1e-12,
                "the ask is the attractive side: sell what we hold");

        SkewedQuoter.Quote flat = SkewedQuoter.quote(100, 10, 0, 100, 0.5);
        assertEquals(0, flat.skewBps(), 1e-12);
        assertEquals(flat.ask() - 100, 100 - flat.bid(), 1e-12, "flat book, symmetric");

        // Beyond-limit inventory clamps: the skew never exceeds
        // skewFraction of the half spread, and the quote NEVER crosses.
        SkewedQuoter.Quote capped = SkewedQuoter.quote(100, 10, 500, 100, 0.9);
        assertEquals(-9, capped.skewBps(), 1e-12);
        assertTrue(capped.bid() < capped.ask(), "never self-crossing");

        SkewedQuoter.Quote shortBook = SkewedQuoter.quote(100, 10, -50, 100, 0.5);
        assertEquals(2.5, shortBook.skewBps(), 1e-12, "short book shades UP to buy");

        assertThrows(IllegalArgumentException.class,
                () -> SkewedQuoter.quote(100, 10, 0, 100, 1.0), "fraction 1 could cross");
        assertThrows(IllegalArgumentException.class,
                () -> SkewedQuoter.quote(100, 10, Double.NaN, 100, 0.5));
        // 100%+ half spreads would quote a zero or NEGATIVE bid.
        assertThrows(IllegalArgumentException.class,
                () -> SkewedQuoter.quote(100, 12_000, 0, 100, 0),
                "a negative bid must never be emitted as a live quote");
    }

    // ------------------------------------------------------------------
    // Internalization
    // ------------------------------------------------------------------

    @Test
    void riskReducingFlowIsInternalizedWithImprovement() {
        InternalizationEngine engine = new InternalizationEngine(10_000_000, 0.5);
        // Book long 5M; client flow shorts the book 3M: pure risk reduction.
        InternalizationEngine.Decision d = engine.decide(5_000_000, -3_000_000, 10);
        assertEquals(-3_000_000, d.internalized(), 1e-9, "fully crossed");
        assertEquals(0, d.routed(), 1e-9);
        assertEquals(5, d.improvementBps(), 1e-12, "half of the saved 10bps half-spread");
        assertEquals(1.0, engine.internalizationRate(), 1e-12);
    }

    @Test
    void flowThroughZeroBlendsImprovementAndWarehousesTheFlip() {
        InternalizationEngine engine = new InternalizationEngine(10_000_000, 0.5);
        // Book long 5M; flow -8M: 5M reduces, 3M flips the book short —
        // that excess is warehoused (inside the 10M limit), no improvement
        // earned on it.
        InternalizationEngine.Decision d = engine.decide(5_000_000, -8_000_000, 10);
        assertEquals(-8_000_000, d.internalized(), 1e-9);
        assertEquals(0, d.routed(), 1e-9);
        assertEquals(5.0 * 5 / 8, d.improvementBps(), 1e-12,
                "only the reducing 5M of 8M earned improvement");
    }

    @Test
    void riskAddingFlowIsWarehousedOnlyInsideTheLimit() {
        InternalizationEngine engine = new InternalizationEngine(10_000_000, 0.5);
        // Book already long 9M; another 5M same-way: 1M of headroom.
        InternalizationEngine.Decision d = engine.decide(9_000_000, 5_000_000, 10);
        assertEquals(1_000_000, d.internalized(), 1e-9, "warehouse to the limit");
        assertEquals(4_000_000, d.routed(), 1e-9, "the rest goes to the street");
        assertEquals(0, d.improvementBps(), 1e-12, "risk-adding flow earns nothing");
        assertEquals(0.2, engine.internalizationRate(), 1e-12, "1M of 5M kept");

        // At (or beyond) the limit: everything routes.
        InternalizationEngine.Decision full = engine.decide(10_000_000, 2_000_000, 10);
        assertEquals(0, full.internalized(), 1e-9);
        assertEquals(2_000_000, full.routed(), 1e-9);

        assertThrows(IllegalArgumentException.class, () -> engine.decide(0, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalizationEngine(1e6, 1.5));
    }

    // ------------------------------------------------------------------
    // Hedge optimizer
    // ------------------------------------------------------------------

    @Test
    void zeroCostRecoversTheClosedFormMinimumVarianceHedge() {
        // Factor A exposure hedged with an instrument that loads factor B:
        // the classic regression hedge h = -E·cov(A,B)/var(B).
        double sa2 = 4e-4;
        double sb2 = 2.25e-4;
        double cab = 0.6 * Math.sqrt(sa2 * sb2);
        double[][] cov = {{sa2, cab}, {cab, sb2}};
        double[] e = {10_000_000, 0};
        double[][] loadings = {{0}, {1}};                 // 1 unit -> 1 unit of B
        double[] h = HedgeOptimizer.hedge(e, cov, loadings, new double[]{1}, 0);
        assertEquals(-10_000_000 * cab / sb2, h[0], 1e-3,
                "lambda = 0 IS the closed-form minimum-variance hedge");

        double before = HedgeOptimizer.risk(e, cov);
        double after = HedgeOptimizer.risk(HedgeOptimizer.residual(e, loadings, h), cov);
        assertTrue(after < before, "the hedge cut risk: " + after + " < " + before);
        // Residual variance = var_A(1 - rho^2) under the optimal proxy hedge.
        assertEquals(before * Math.sqrt(1 - 0.36), after, before * 1e-6,
                "exactly the correlation-limited floor");
    }

    @Test
    void costTermZeroesHedgesNotWorthTheirPrice() {
        double[][] cov = {{4e-4}};
        double[] e = {1_000_000};
        double[][] loadings = {{1}};
        // The marginal risk saving at h=0 is |2*L'Se| = 2*4e-4*1e6 = 800;
        // price the instrument above that and the optimizer holds ZERO.
        double[] none = HedgeOptimizer.hedge(e, cov, loadings, new double[]{1}, 1_000);
        assertEquals(0, none[0], "an uneconomic hedge is exactly zero, not dust");
        // Moderate cost: hedge less than fully.
        double[] partial = HedgeOptimizer.hedge(e, cov, loadings, new double[]{1}, 400);
        assertTrue(partial[0] < 0 && partial[0] > -1_000_000,
                "cost-aware = partially hedged: " + partial[0]);
        // Free: fully flat.
        double[] full = HedgeOptimizer.hedge(e, cov, loadings, new double[]{1}, 0);
        assertEquals(-1_000_000, full[0], 1e-3);
    }

    @Test
    void optimizerPicksTheCheaperOfTwoIdenticalInstruments() {
        double[][] cov = {{4e-4}};
        double[] e = {5_000_000};
        double[][] loadings = {{1, 1}};                  // twins on the same factor
        double[] h = HedgeOptimizer.hedge(e, cov, loadings, new double[]{1, 5}, 100);
        assertTrue(h[0] < 0, "the cheap twin does the hedging: " + h[0]);
        assertEquals(0, h[1], "the expensive twin is exactly zero");
        assertThrows(IllegalArgumentException.class,
                () -> HedgeOptimizer.hedge(e, cov, loadings, new double[]{1, 5}, -1));
        assertThrows(IllegalArgumentException.class,
                () -> HedgeOptimizer.hedge(new double[]{Double.NaN}, cov,
                        new double[][]{{1}}, new double[]{1}, 0));
        // A NaN covariance cell must throw, never return a silent
        // all-zero "hedge" for a live breach.
        assertThrows(IllegalArgumentException.class,
                () -> HedgeOptimizer.hedge(new double[]{1e7},
                        new double[][]{{Double.NaN}}, new double[][]{{1}},
                        new double[]{1}, 0));
        // Non-PSD covariance is a data error, not a skippable instrument.
        assertThrows(IllegalArgumentException.class,
                () -> HedgeOptimizer.hedge(new double[]{1e7},
                        new double[][]{{-1e-4}}, new double[][]{{1}},
                        new double[]{1}, 0));
    }

    // ------------------------------------------------------------------
    // Auto-hedger
    // ------------------------------------------------------------------

    @Test
    void autoHedgerHedgesTheExcessBackToTheBandAndCoolsDown() {
        CrbAutoHedger hedger = new CrbAutoHedger(new double[]{10_000_000}, 0.5, 2);
        double[][] cov = {{4e-4}};
        double[][] loadings = {{1}};
        double[] costs = {1};

        // Inside the band: warehouse, do nothing.
        assertEquals(0, hedger.check(new double[]{8_000_000}, cov, loadings,
                costs, 0, 0).length, "inside the band the CRB warehouses");

        // Breach at 12M: hedge the EXCESS beyond 0.5*limit = 5M, i.e. -7M.
        CrbAutoHedger.HedgeOrder[] orders = hedger.check(new double[]{12_000_000},
                cov, loadings, costs, 0, 10);
        assertEquals(1, orders.length);
        assertEquals(-7_000_000, orders[0].notional(), 1e-3,
                "hedge to the reset band, not to flat — inventory is the edge");
        assertEquals(0, orders[0].instrument());
        assertEquals(1, hedger.hedgesEmitted());

        // Cooldown: a breach one interval later is suppressed...
        assertEquals(0, hedger.check(new double[]{12_000_000}, cov, loadings,
                costs, 0, 11).length, "cooling down");
        // ...and fires again once the cooldown elapses.
        assertEquals(1, hedger.check(new double[]{12_000_000}, cov, loadings,
                costs, 0, 12).length);

        assertThrows(IllegalArgumentException.class,
                () -> new CrbAutoHedger(new double[]{0}, 0.5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> hedger.check(new double[]{1, 2}, cov, loadings, costs, 0, 0));
        // Math.abs(NaN) > limit is false — an unguarded NaN exposure
        // would silently disable the auto-hedger forever.
        assertThrows(IllegalArgumentException.class,
                () -> hedger.check(new double[]{Double.NaN}, cov, loadings, costs, 0, 20));
    }

    @Test
    void hardLimitOutranksCostThrift() {
        CrbAutoHedger hedger = new CrbAutoHedger(new double[]{10_000_000}, 0.5, 0);
        double[][] cov = {{4e-4}};
        double[][] loadings = {{1}};
        // Cost so punitive the cost-aware solve would hold zero — the
        // hedger must escalate to cost-blind rather than stay breached.
        CrbAutoHedger.HedgeOrder[] orders = hedger.check(new double[]{12_000_000},
                cov, loadings, new double[]{1}, 1e9, 0);
        assertEquals(1, orders.length, "the limit is hard; the cost preference is not");
        assertEquals(-7_000_000, orders[0].notional(), 1e-3);
    }

    // ------------------------------------------------------------------
    // Router — internal cross, then dark by adverse selection, then lit
    // ------------------------------------------------------------------

    @Test
    void routerCrossesInternallyFirstThenPricesDarkAgainstLit() {
        // Deliberately pass the venues in the WRONG order to prove the
        // router ranks by adverse selection, not array position.
        CrbRouter.DarkVenue toxic = new CrbRouter.DarkVenue("TOXIC", 10_000_000, 1.0, 20);
        CrbRouter.DarkVenue clean = new CrbRouter.DarkVenue("CLEAN", 4_000_000, 0.5, 2);
        CrbRouter.Allocation a = CrbRouter.route(10_000_000, 3_000_000,
                new CrbRouter.DarkVenue[]{toxic, clean}, 5, 3);

        assertEquals(3_000_000, a.internal(), 1e-9, "the book itself fills first, free");
        assertEquals(0, a.dark()[0], 1e-9,
                "20bps adverse selection >= 8bps lit cost: the toxic pool gets NOTHING");
        assertEquals(2_000_000, a.dark()[1], 1e-9,
                "clean pool: 4M liquidity x 0.5 fill probability");
        assertEquals(5_000_000, a.lit(), 1e-9, "the remainder pays the spread but fills");
        // Blended: (3M*0 + 2M*2 + 5M*8) / 10M = 4.4 bps.
        assertEquals(4.4, a.expectedCostBps(), 1e-12);
    }

    @Test
    void fullyInternalizedOrderCostsNothing() {
        CrbRouter.Allocation a = CrbRouter.route(2_000_000, 5_000_000,
                new CrbRouter.DarkVenue[0], 5, 3);
        assertEquals(2_000_000, a.internal(), 1e-9);
        assertEquals(0, a.lit(), 1e-9);
        assertEquals(0, a.expectedCostBps(), 1e-12, "crossing inventory is free");
        assertThrows(IllegalArgumentException.class,
                () -> CrbRouter.route(0, 0, new CrbRouter.DarkVenue[0], 5, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new CrbRouter.DarkVenue("X", 1e6, 1.2, 5));
    }

    // ------------------------------------------------------------------
    // The full loop, all six instrument types
    // ------------------------------------------------------------------

    @Test
    void fullLoopBooksNetsQuotesInternalizesHedgesAndRoutes() {
        CentralRiskBook book = new CentralRiskBook();
        // Three desks, six products, one netted book.
        book.bookCashEquity("cash-desk", "SPY", 20_000, 500);
        book.bookEquityOption("vol-desk", "SPY", OptionType.PUT,
                -100, 100, 500, 480, 0.03, 0.015, 0.2, 0.25);   // short puts: long delta
        book.bookFxSpot("fx-desk", "EURUSD", 10_000_000, 1.10);
        book.bookFxSwap("fx-desk", "EURUSD", 20_000_000, 1.1000, 1.1040);
        book.bookNdf("em-desk", "USDINR", 5_000_000, 84.0);
        book.bookFxOption("fx-desk", "EURUSD", OptionType.CALL,
                -8_000_000, 1.10, 1.12, 0.05, 0.03, 0.10, 0.25); // short calls: short delta

        assertEquals(6, book.flowsBooked());
        assertTrue(book.exposure("EQ:SPY") > 10_000_000, "cash + short-put delta stack");
        assertTrue(book.exposure("CCY:EUR") < 10_000_000,
                "the short option delta netted part of the spot leg");
        assertTrue(book.nettingEfficiency() > 0, "something netted");

        // A client sell of EUR risk-reduces the long CCY:EUR book.
        InternalizationEngine engine = new InternalizationEngine(20_000_000, 0.5);
        double eurNet = book.exposure("CCY:EUR");
        InternalizationEngine.Decision d = engine.decide(eurNet, -2_000_000, 4);
        assertEquals(-2_000_000, d.internalized(), 1e-6, "risk-reducing: kept");
        assertTrue(d.improvementBps() > 0, "and the client shares the saving");

        // Auto-hedge the equity factor: limit 8M, book is ~10M+ long.
        int n = book.factors().size();
        double[] exposures = book.netExposures();
        double[] limits = new double[n];
        int eqId = book.factors().idIfPresent("EQ:SPY");
        for (int f = 0; f < n; f++) {
            limits[f] = Math.max(1, Math.abs(exposures[f]) * 2);  // roomy everywhere...
        }
        limits[eqId] = 8_000_000;                                  // ...except equities
        double[][] cov = new double[n][n];
        for (int f = 0; f < n; f++) {
            cov[f][f] = 1e-4;
        }
        double[][] loadings = new double[n][1];
        loadings[eqId][0] = 1;                                     // an index-futures proxy
        CrbAutoHedger hedger = new CrbAutoHedger(limits, 0.75, 0);
        CrbAutoHedger.HedgeOrder[] orders = hedger.check(exposures, cov, loadings,
                new double[]{0.5}, 1, 0);
        assertEquals(1, orders.length, "the breach got a hedge");
        double[] residual = HedgeOptimizer.residual(exposures, loadings,
                new double[]{orders[0].notional()});
        assertTrue(Math.abs(residual[eqId]) <= 8_000_000 + 1e-3,
                "post-hedge equity inside the hard limit: " + residual[eqId]);

        // The hedge itself routes: internal first, clean dark, lit rest.
        CrbRouter.Allocation alloc = CrbRouter.route(Math.abs(orders[0].notional()),
                1_000_000, new CrbRouter.DarkVenue[]{
                        new CrbRouter.DarkVenue("MID", 2_000_000, 0.8, 1.5)}, 4, 2);
        assertEquals(1_000_000, alloc.internal(), 1e-9);
        assertTrue(alloc.dark()[0] > 0, "cheap dark used before lit");
        assertEquals(Math.abs(orders[0].notional()) - 1_000_000 - alloc.dark()[0],
                alloc.lit(), 1e-6, "conservation: every unit lands somewhere");

        // And the report prices the whole arrangement coherently.
        CentralRiskBook.CrbReport report = book.report(cov, 0.99);
        assertTrue(report.var() > 0 && report.es() > report.var());
        assertTrue(report.diversificationBenefit() >= -1e-9,
                "netting can only help: " + report.diversificationBenefit());
        assertTrue(report.nettingEfficiency() > 0 && report.nettingEfficiency() < 1);
    }
}

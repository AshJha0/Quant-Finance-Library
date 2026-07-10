package com.quantfinlib.crb;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.risk.StressTester;
import com.quantfinlib.risk.VarEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CRB run the way a desk actually runs it — realistic sizes, vols,
 * spreads and costs, over the days that define the business: a quiet
 * two-way day (internalization IS the P&amp;L), a one-way institutional
 * day (the hedge-escalation path earns its keep), a vol-spike stress
 * day (the numbers a risk committee asks for), and an NDF fixing day
 * with overnight continuity through the checkpoint.
 */
class CrbRealWorldScenarioTest {

    // ------------------------------------------------------------------
    // Day 1 — quiet two-way flow: the netting engine pays for itself
    // ------------------------------------------------------------------

    @Test
    void quietTwoWayDayInternalizationIsThePnl() {
        CentralRiskBook book = new CentralRiskBook();
        // Realistic knobs: a liquid single-name book, 2 bps street half
        // spread, 40% of the saved spread returned to clients, a 5M
        // warehouse the desk is comfortable carrying overnight.
        double halfSpreadBps = 2.0;
        InternalizationEngine engine = new InternalizationEngine(5_000_000, 0.4);
        CrbPnlLedger ledger = new CrbPnlLedger();

        Random flow = new Random(7);
        double price = 500;
        for (int t = 0; t < 24; t++) {
            // Client tickets 0.5–3M, both directions all day.
            double notional = (0.5 + 2.5 * flow.nextDouble()) * 1_000_000
                    * (flow.nextBoolean() ? 1 : -1);
            InternalizationEngine.Decision d =
                    engine.decide(book.exposure("EQ:SPY"), notional, halfSpreadBps);
            if (d.internalized() != 0) {
                book.bookCashEquity("client-flow", "SPY",
                        d.internalized() / price, price);
            }
            ledger.onDecision(d, halfSpreadBps);
        }

        // The warehouse limit is a real invariant, not advice.
        assertTrue(Math.abs(book.exposure("EQ:SPY")) <= 5_000_000 + 1e-6,
                "inventory never exceeds the warehouse: " + book.exposure("EQ:SPY"));
        // Two-way flow mostly crosses itself — that is the CRB thesis.
        assertTrue(engine.internalizationRate() > 0.5,
                "most flow crossed internally: " + engine.internalizationRate());
        assertTrue(ledger.spreadCaptured() > 0, "the day made money");
        assertTrue(ledger.improvementPaid() > 0,
                "and clients were paid to bring the offsetting flow");
        assertEquals(ledger.spreadCaptured(), ledger.netEconomics(), 1e-9,
                "no hedges on a quiet day: economics = captured spread");
    }

    // ------------------------------------------------------------------
    // Day 2 — one-way institutional flow: hedge, escalate, still profit
    // ------------------------------------------------------------------

    @Test
    void oneWayInstitutionalDayHedgesAndStaysProfitable() {
        CentralRiskBook book = new CentralRiskBook();
        double halfSpreadBps = 2.5;
        InternalizationEngine engine = new InternalizationEngine(20_000_000, 0.3);
        CrbPnlLedger ledger = new CrbPnlLedger();

        // A pension unwinds 18M of SPY through the desk in six tickets —
        // all one way; the warehouse absorbs it (that is the service).
        double price = 500;
        for (int t = 0; t < 6; t++) {
            InternalizationEngine.Decision d =
                    engine.decide(book.exposure("EQ:SPY"), 3_000_000, halfSpreadBps);
            if (d.internalized() != 0) {
                book.bookCashEquity("pension-flow", "SPY", d.internalized() / price, price);
            }
            ledger.onDecision(d, halfSpreadBps);
        }
        assertEquals(18_000_000, book.exposure("EQ:SPY"), 1e-6);

        // Hedge instruments with real cost structure: the ES-proxy is
        // cheap (0.4 bps all-in) but lives on ANOTHER factor; the direct
        // SPY program trade is pricier (2 bps) but hits the band.
        CrbHedgeUniverse universe = new CrbHedgeUniverse(book.factors())
                .addSingleFactor("ES-PROXY", "EQ:SPX", 0.4)
                .addSingleFactor("SPY-PROGRAM", "EQ:SPY", 2.0);
        double spyVar = 1.44e-4;                       // 1.2% daily vol
        double spxVar = 1.21e-4;                       // 1.1% daily vol
        double cross = 0.97 * Math.sqrt(spyVar * spxVar);
        int spy = book.factors().idIfPresent("EQ:SPY");
        int spx = book.factors().idIfPresent("EQ:SPX");
        double[][] cov = new double[2][2];
        cov[spy][spy] = spyVar;
        cov[spx][spx] = spxVar;
        cov[spy][spx] = cross;
        cov[spx][spy] = cross;

        // Band: 10M hard limit on SPY, hedge back to 60%. A punitive
        // cost weight makes the cost-aware pass hold ZERO — the hedger
        // must escalate to cost-blind, and cost-blind must pick the
        // DIRECT instrument (the proxy cannot satisfy a per-factor band).
        CrbAutoHedger hedger = new CrbAutoHedger(
                new double[]{10_000_000, 25_000_000}, 0.6, 1);
        CrbAutoHedger.HedgeOrder[] orders = hedger.check(book.netExposures(),
                cov, universe.loadings(), universe.costs(), 6_000, 0);
        assertEquals(1, orders.length, "one decisive hedge");
        assertEquals("SPY-PROGRAM", universe.name(orders[0].instrument()),
                "the hard limit demanded the direct hedge, price notwithstanding");
        assertEquals(-12_000_000, orders[0].notional(), 1.0,
                "excess beyond 0.6 x 10M: 18M - 6M (to the dollar — the "
                        + "correlated solve converges to relative tolerance)");
        ledger.onHedge(orders[0].notional(), 2.0);

        // The hedge routes like any order: dark midpoint at 1.2 bps
        // undercuts lit (0.5 spread + 0.8 impact); the 15 bps printing
        // pool gets nothing.
        CrbRouter.Allocation alloc = CrbRouter.route(Math.abs(orders[0].notional()), 0,
                new CrbRouter.DarkVenue[]{
                        new CrbRouter.DarkVenue("PRINT-POOL", 10_000_000, 1.0, 15),
                        new CrbRouter.DarkVenue("MIDPOINT", 6_000_000, 0.7, 1.2)},
                0.5, 0.8);
        assertEquals(4_200_000, alloc.dark()[1], 1e-6, "clean dark first");
        assertEquals(0, alloc.dark()[0], 1e-9, "toxic pool priced out");
        assertEquals(7_800_000, alloc.lit(), 1.0);
        ledger.onRoute(Math.abs(orders[0].notional()), alloc);

        // The commercial argument, in one assertion: captured spread
        // paid for the hedge AND its execution, with margin.
        // 18M x (2.5 - 0.75) bps = 3,150 captured; 12M x 2 bps = 2,400
        // hedge; ~1.24 bps blended on 12M routed.
        assertTrue(ledger.netEconomics() > 0,
                "the netting engine paid for its own risk management: "
                        + ledger.netEconomics());
        assertEquals(ledger.spreadCaptured() - ledger.hedgeCost() - ledger.routerCost(),
                ledger.netEconomics(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Day 3 — the vol spike: what the risk committee asks for
    // ------------------------------------------------------------------

    @Test
    void volSpikeDayStressesTheResidualBook() {
        // The residual book into the spike: long 8M equities, short 5M
        // USD, short vega (sold calls into the calm) — a realistic
        // pre-crisis book shape, mapped onto the stress template's
        // [equity, rates, FX-USD, commodity, vol] factor order.
        double vegaPerPoint = -40_000;                 // $ per vol point, short
        double[] exposures = {8_000_000, 0, -5_000_000, 0, vegaPerPoint * 100};
        double pnl = StressTester.scenarioPnl(exposures, StressTester.covidMarch2020());
        // Hand arithmetic: 8M x -12% - 5M x +5% - 40k x 25 points
        //                = -960k - 250k - 1,000k = -2.21M.
        assertEquals(-2_210_000, pnl, 1e-6,
                "the March-2020 replay on this book, to the dollar");
        assertTrue(pnl < 0, "long equity, short USD, short vega — the spike hurts");

        // The regulator's inverted question on the liquid slice: what
        // move loses 2M, and how implausible is it?
        double[] liquid = {8_000_000, -5_000_000};
        double[][] cov = {{1.44e-4, 0.3 * Math.sqrt(1.44e-4 * 3.6e-5)},
                {0.3 * Math.sqrt(1.44e-4 * 3.6e-5), 3.6e-5}};
        StressTester.ReverseStress reverse = StressTester.reverseStress(liquid, cov, 2_000_000);
        assertEquals(-2_000_000, StressTester.scenarioPnl(liquid, reverse.shocks()), 1e-6);
        assertEquals(2_000_000 / VarEngine.portfolioStdev(liquid, cov),
                reverse.mahalanobisSigmas(), 1e-9);
        assertTrue(reverse.mahalanobisSigmas() > 10,
                "a 2M loss on this small netted book is a many-sigma event — "
                        + "the CRB's netting bought exactly this comfort: "
                        + reverse.mahalanobisSigmas());

        // Quoting through the spike: spread 4x wider, skew working
        // harder, and the quote still never crosses or goes negative.
        SkewedQuoter.Quote crisis = SkewedQuoter.quote(500, 8, 4_000_000, 5_000_000, 0.6);
        assertTrue(crisis.bid() > 0 && crisis.bid() < crisis.ask());
        assertTrue(crisis.skewBps() < -3.5,
                "a nearly-full warehouse shades hard: " + crisis.skewBps());
    }

    // ------------------------------------------------------------------
    // Day 4 — NDF fixing day, and the book survives the night
    // ------------------------------------------------------------------

    @Test
    void ndfFixingDayRollsThroughTheOvernightCheckpoint(@TempDir Path dir) throws IOException {
        CentralRiskBook today = new CentralRiskBook();
        // An EM desk's NDF book: USDINR fixes tomorrow, USDBRL next week.
        today.bookNdf("em-desk", "USDINR", 8_000_000, 84.0);
        today.bookNdf("em-desk", "USDBRL", 4_000_000, 5.60);
        CrbPnlLedger ledger = new CrbPnlLedger();
        ledger.onInternalized(8_000_000, 3.0, 1.0);    // the INR flow was internalized

        Path file = dir.resolve("crb-eod.qfl");
        try (Checkpoint.Writer w = Checkpoint.writer(file)) {
            w.section("crb.book", today::writeState);
            w.section("crb.pnl", ledger::writeState);
        }

        // Tomorrow: fresh process, restored book, the INR fixing occurs.
        CentralRiskBook tomorrow = new CentralRiskBook();
        CrbPnlLedger ledger2 = new CrbPnlLedger();
        Checkpoint.Reader r = Checkpoint.reader(file);
        assertTrue(r.section("crb.book", tomorrow::readState));
        assertTrue(r.section("crb.pnl", ledger2::readState));
        assertEquals(ledger.netEconomics(), ledger2.netEconomics(), 1e-9,
                "yesterday's economics are not relearned, they are remembered");

        // The fixing: release the pending notional, re-book the fixed
        // NDF's delta as the offsetting flow (it cash-settles in USD).
        assertEquals(8_000_000, tomorrow.pendingFixing("USDINR"), 1e-9);
        tomorrow.settleFixing("USDINR", 8_000_000);
        tomorrow.bookNdf("em-desk", "USDINR", -8_000_000, 84.0);
        tomorrow.settleFixing("USDINR", 8_000_000);    // the offset itself fixes too
        assertEquals(0, tomorrow.pendingFixing("USDINR"), 1e-9, "INR is done");
        assertEquals(0, tomorrow.exposure("CCY:INR"), 1e-6, "and its delta is flat");
        assertEquals(4_000_000, tomorrow.pendingFixing("USDBRL"), 1e-9,
                "BRL still awaits next week's fixing");
        assertEquals(4_000_000, tomorrow.exposure("CCY:USD") - 0, 1e-6,
                "only the live BRL forward's USD leg remains");
    }
}

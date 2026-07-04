package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsBookTest {

    private static OptionsBook straddleBook() {
        // Short 100 ATM straddles, partially delta-hedged with the underlying.
        return new OptionsBook(100, 0.03, 0)
                .addOption("shortCall", OptionType.CALL, 100, 0.5, -100, 0.20)
                .addOption("shortPut", OptionType.PUT, 100, 0.5, -100, 0.20)
                .addUnderlying(10);
    }

    @Test
    void greeksAggregateAcrossPositionsAndUnderlying() {
        OptionsBook book = straddleBook();
        OptionsBook.BookGreeks g = book.greeks();

        BlackScholes.Greeks call = BlackScholes.greeks(OptionType.CALL, 100, 100, 0.03, 0, 0.20, 0.5);
        BlackScholes.Greeks put = BlackScholes.greeks(OptionType.PUT, 100, 100, 0.03, 0, 0.20, 0.5);

        assertEquals(-100 * (call.delta() + put.delta()) + 10, g.delta(), 1e-9);
        assertEquals(-100 * (call.gamma() + put.gamma()), g.gamma(), 1e-9);
        assertEquals(-100 * (call.vega() + put.vega()), g.vega(), 1e-9);
        assertEquals(-100 * (call.price() + put.price()) + 10 * 100, g.value(), 1e-9);
        // Short straddle: short gamma/vega, long theta.
        assertTrue(g.gamma() < 0 && g.vega() < 0 && g.theta() > 0);
        assertEquals(g.value(), book.value(), 1e-9);
    }

    @Test
    void scenarioGridShowsShortStraddleRiskProfile() {
        OptionsBook book = straddleBook();
        double[] spotShifts = {-0.10, -0.05, 0, 0.05, 0.10};
        double[] volShifts = {-0.05, 0, 0.05};
        double[][] grid = book.scenarioGrid(spotShifts, volShifts);

        assertEquals(spotShifts.length, grid.length);
        assertEquals(volShifts.length, grid[0].length);
        // Unchanged market: zero P&L by construction.
        assertEquals(0, grid[2][1], 1e-9);
        // Short straddle loses on big moves either way, and on vol up.
        assertTrue(grid[0][1] < 0 && grid[4][1] < 0);
        assertTrue(grid[2][2] < 0);
        // ...and gains when vol collapses.
        assertTrue(grid[2][0] > 0);
    }

    @Test
    void pnlExplainAttributesSmallMovesAccurately() {
        OptionsBook book = straddleBook();
        // Small move: 0.5% spot, +50bp vol, one day of decay.
        OptionsBook.PnlExplain explain = book.pnlExplain(100.5, 0.005, 1.0 / 252);

        double explained = explain.deltaPnl() + explain.gammaPnl()
                + explain.vegaPnl() + explain.thetaPnl();
        assertEquals(explain.actualPnl(), explained + explain.unexplained(), 1e-9);
        // Greeks capture the vast majority of a small move.
        assertTrue(Math.abs(explain.unexplained()) < 0.05 * Math.abs(explain.actualPnl()) + 1e-6,
                "unexplained " + explain.unexplained() + " of " + explain.actualPnl());
        // Short vega book: vol up hurts; long theta: decay helps.
        assertTrue(explain.vegaPnl() < 0);
        assertTrue(explain.thetaPnl() > 0);
    }

    @Test
    void largeMovesLeaveHigherOrderResidual() {
        OptionsBook book = straddleBook();
        // 15% crash: gamma approximation breaks down measurably.
        OptionsBook.PnlExplain explain = book.pnlExplain(85, 0, 0);
        assertTrue(Math.abs(explain.unexplained()) > 0,
                "large move should have higher-order residual");
        assertTrue(explain.actualPnl() < 0);   // short straddle loses on the crash
    }
}

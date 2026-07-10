package com.quantfinlib.crb;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Central risk book: booking, cross-product netting, greeks rollup, risk report. */
class CrbBookTest {

    // ------------------------------------------------------------------
    // Cash equities
    // ------------------------------------------------------------------

    @Test
    void cashEquityNetsAcrossDesks() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookCashEquity("cash-desk", "AAPL", 10_000, 150);      // +1.5M
        book.bookCashEquity("etf-desk", "AAPL", -8_000, 150);       // -1.2M
        assertEquals(300_000, book.exposure("EQ:AAPL"), 1e-9,
                "two desks' opposite flows net before anyone pays the street");
        assertEquals(2_700_000, book.grossExposure("EQ:AAPL"), 1e-9);
        assertEquals(1_500_000, book.deskExposure("cash-desk", "EQ:AAPL"), 1e-9);
        assertEquals(-1_200_000, book.deskExposure("etf-desk", "EQ:AAPL"), 1e-9);
        // 1 - 0.3M/2.7M: most of the gross flow annihilated internally.
        assertEquals(1 - 300_000.0 / 2_700_000, book.nettingEfficiency(), 1e-12);
        assertEquals(2, book.flowsBooked());
        assertEquals(0, book.exposure("EQ:MSFT"), "never-booked factor is flat");
    }

    // ------------------------------------------------------------------
    // Equity options — greeks decompose onto the SAME factors as cash
    // ------------------------------------------------------------------

    @Test
    void equityOptionDeltaNetsAgainstCashShares() {
        double spot = 100;
        double strike = 105;
        double r = 0.03;
        double q = 0.01;
        double vol = 0.25;
        double t = 0.5;
        CentralRiskBook book = new CentralRiskBook();
        book.bookCashEquity("cash-desk", "XYZ", -5_000, spot);              // -500k
        book.bookEquityOption("vol-desk", "XYZ", OptionType.CALL,
                100, 100, spot, strike, r, q, vol, t);                       // 10k units

        double delta = BlackScholes.delta(OptionType.CALL, spot, strike, r, q, vol, t);
        double gamma = BlackScholes.gamma(spot, strike, r, q, vol, t);
        double vega = BlackScholes.vega(spot, strike, r, q, vol, t);
        assertEquals(-500_000 + 10_000 * delta * spot, book.exposure("EQ:XYZ"), 1e-6,
                "option delta and cash shares share ONE factor — that is the netting");
        assertEquals(10_000 * gamma * spot * spot / 100, book.exposure("EQGAMMA:XYZ"),
                1e-9, "dollar gamma per 1%");
        assertEquals(10_000 * vega / 100, book.exposure("EQVEGA:XYZ"), 1e-9,
                "vega per vol point");

        // A put's delta is negative: booking long puts REDUCES a long book.
        CentralRiskBook putBook = new CentralRiskBook();
        putBook.bookEquityOption("vol-desk", "XYZ", OptionType.PUT,
                50, 100, spot, strike, r, q, vol, t);
        assertTrue(putBook.exposure("EQ:XYZ") < 0, "long puts are short delta");
        assertTrue(putBook.exposure("EQGAMMA:XYZ") > 0, "long options are long gamma");
    }

    // ------------------------------------------------------------------
    // FX spot — currency-level decomposition is what nets across pairs
    // ------------------------------------------------------------------

    @Test
    void fxSpotDecomposesToCurrencyLegsAndNetsAcrossPairs() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookFxSpot("fx-desk", "EURUSD", 10_000_000, 1.10);
        assertEquals(10_000_000, book.exposure("CCY:EUR"), 1e-6);
        assertEquals(-11_000_000, book.exposure("CCY:USD"), 1e-6);

        // A USDJPY buy from ANOTHER desk nets the USD leg — pair-keyed
        // exposure could never see this.
        book.bookFxSpot("spot-desk-2", "USDJPY", 5_000_000, 150);
        assertEquals(-6_000_000, book.exposure("CCY:USD"), 1e-6,
                "USD exposure nets across EURUSD and USDJPY");
        assertEquals(-750_000_000, book.exposure("CCY:JPY"), 1e-6);
    }

    // ------------------------------------------------------------------
    // FX swaps — points risk, not spot risk
    // ------------------------------------------------------------------

    @Test
    void fxSwapCarriesPointsRiskWithZeroBaseDelta() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookFxSwap("fwd-desk", "EURUSD", 20_000_000, 1.1000, 1.1050);
        assertEquals(0, book.exposure("CCY:EUR"), 1e-9,
                "a swap's base legs cancel exactly — that is what a swap IS");
        assertEquals(20_000_000 * 0.005, book.exposure("CCY:USD"), 1e-6,
                "the quote-currency cash-flow imbalance");
        assertEquals(-20_000_000, book.exposure("FXPOINTS:EURUSD"), 1e-9,
                "a buy-sell LOSES when far-minus-near widens: sensitivity is -N");
        // The swap's points risk does NOT touch the spot-delta factors a
        // spot trade would: hedging spot with swaps needs the loadings to
        // say so explicitly.
        book.bookFxSpot("spot-desk", "EURUSD", 1_000_000, 1.10);
        assertEquals(1_000_000, book.exposure("CCY:EUR"), 1e-9);
    }

    // ------------------------------------------------------------------
    // NDFs — a forward until the fixing, plus the fixing-notional flag
    // ------------------------------------------------------------------

    @Test
    void ndfCarriesFullDeltaUntilFixingAndTracksPendingNotional() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookNdf("em-desk", "USDINR", 5_000_000, 84.0);
        assertEquals(5_000_000, book.exposure("CCY:USD"), 1e-6);
        assertEquals(-420_000_000, book.exposure("CCY:INR"), 1e-6);
        assertEquals(5_000_000, book.pendingFixing("USDINR"), 1e-9);
        book.bookNdf("em-desk", "USDINR", -2_000_000, 84.0);
        assertEquals(3_000_000, book.exposure("CCY:USD"), 1e-6, "delta nets");
        assertEquals(7_000_000, book.pendingFixing("USDINR"), 1e-9,
                "fixing exposure is GROSS — offsetting NDFs still both fix");
        assertEquals(0, book.pendingFixing("USDBRL"), 1e-12);

        // Fixings RELEASE once they occur — a pending number that only
        // ever grows is stale the day after the first fixing.
        book.settleFixing("USDINR", 3_000_000);
        assertEquals(4_000_000, book.pendingFixing("USDINR"), 1e-9);
        book.settleFixing("USDINR", 4_000_000);
        assertEquals(0, book.pendingFixing("USDINR"), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> book.settleFixing("USDINR", 1),
                "over-settling is a reconciliation break, not a rounding issue");
    }

    // ------------------------------------------------------------------
    // FX options — Garman-Kohlhagen delta nets against spot
    // ------------------------------------------------------------------

    @Test
    void fxOptionDeltaNetsAgainstSpotOnTheCurrencyLegs() {
        double s = 1.10;
        double k = 1.12;
        double rd = 0.05;
        double rf = 0.03;
        double vol = 0.10;
        double t = 0.25;
        CentralRiskBook book = new CentralRiskBook();
        book.bookFxSpot("fx-desk", "EURUSD", -6_000_000, s);
        book.bookFxOption("fx-opt-desk", "EURUSD", OptionType.CALL,
                10_000_000, s, k, rd, rf, vol, t);

        double delta = BlackScholes.delta(OptionType.CALL, s, k, rd, rf, vol, t);
        double vega = BlackScholes.vega(s, k, rd, rf, vol, t);
        assertEquals(-6_000_000 + 10_000_000 * delta, book.exposure("CCY:EUR"), 1e-4,
                "option delta and spot share the currency factors");
        assertEquals(10_000_000 * vega / 100, book.exposure("FXVEGA:EURUSD"), 1e-9);
        assertTrue(book.exposure("FXGAMMA:EURUSD") > 0, "long option, long gamma");
    }

    // ------------------------------------------------------------------
    // The risk report — pricing the reason the CRB exists
    // ------------------------------------------------------------------

    @Test
    void reportPricesTheDiversificationBenefitOfNetting() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookCashEquity("desk-a", "SPY", 20_000, 500);        // +10M
        book.bookCashEquity("desk-b", "SPY", -16_000, 500);       // -8M
        double dailyVar = 4e-4;                                   // 2% daily vol
        double[][] cov = {{dailyVar}};
        CentralRiskBook.CrbReport report = book.report(cov, 0.99);

        double z = com.quantfinlib.util.MathUtils.normInv(0.99);
        double sigma = Math.sqrt(dailyVar);
        assertEquals(z * 2_000_000 * sigma, report.var(), 1e-6,
                "the netted book's VaR runs on 2M, not 18M");
        assertEquals(z * 18_000_000 * sigma, report.standaloneDeskVar(), 1e-6,
                "what the desks would each carry standalone");
        assertEquals(z * 16_000_000 * sigma, report.diversificationBenefit(), 1e-6,
                "central netting is worth exactly the annihilated 16M");
        assertTrue(report.es() > report.var(), "ES beyond VaR, always");
        assertEquals(book.nettingEfficiency(), report.nettingEfficiency(), 1e-12);
        assertEquals(18_000_000, report.grossExposure(), 1e-6);
        assertEquals(2_000_000, report.netExposure(), 1e-6);
    }

    // ------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------

    @Test
    void gatesRejectGarbageLoudly() {
        CentralRiskBook book = new CentralRiskBook();
        assertThrows(IllegalArgumentException.class,
                () -> book.bookCashEquity("d", "X", Double.NaN, 100));
        assertThrows(IllegalArgumentException.class,
                () -> book.bookCashEquity("d", "X", 100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> book.bookCashEquity(" ", "X", 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> book.bookFxSpot("d", "EUR", 1e6, 1.1), "pair must be 6 chars");
        assertThrows(IllegalArgumentException.class,
                () -> book.bookFxSwap("d", "EURUSD", 1e6, 1.1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> book.bookEquityOption("d", "X", OptionType.CALL,
                        1, 100, 100, 100, 0.02, 0, -0.2, 1));
        book.bookCashEquity("d", "X", 100, 100);
        assertThrows(IllegalArgumentException.class,
                () -> book.report(new double[2][2], 0.99), "cov must match factor count");

        // Compute-validate-COMMIT: a rejected multi-leg booking must
        // leave the book COMPLETELY untouched, not half-booked.
        long flows = book.flowsBooked();
        double eq = book.exposure("EQ:Y");
        assertThrows(IllegalArgumentException.class,
                () -> book.bookEquityOption("d", "Y", OptionType.CALL,
                        1, 100, 100, 100, 0.03, Double.POSITIVE_INFINITY, 0.2, 1));
        assertEquals(flows, book.flowsBooked(), "nothing booked");
        assertEquals(eq, book.exposure("EQ:Y"), 0.0, "no half-booked delta leg");
    }

    @Test
    void hedgeOnlyFactorsNeverBreakTheBookLevelViews() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookFxSpot("fx-desk", "EURUSD", 10_000_000, 1.10);
        double efficiencyBefore = book.nettingEfficiency();
        // Register MANY hedge-only factors past the booked arrays'
        // capacity — the book-level views must stay coherent.
        CrbHedgeUniverse universe = new CrbHedgeUniverse(book.factors());
        for (int i = 0; i < 20; i++) {
            universe.addSingleFactor("HEDGE-" + i, "HEDGEFACTOR:" + i, 1);
        }
        assertEquals(efficiencyBefore, book.nettingEfficiency(), 1e-12,
                "hedge-only factors carry zero exposure, efficiency unchanged");
        int n = book.factors().size();
        double[][] cov = new double[n][n];
        for (int f = 0; f < n; f++) {
            cov[f][f] = 1e-4;
        }
        CentralRiskBook.CrbReport report = book.report(cov, 0.99);
        assertTrue(report.var() > 0, "report survives the grown registry");
        assertEquals(n, book.netExposures().length);
    }
}

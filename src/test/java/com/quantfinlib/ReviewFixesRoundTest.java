package com.quantfinlib;

import com.quantfinlib.execution.BenchmarkExecutor;
import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import com.quantfinlib.fx.CurrencyPair;
import com.quantfinlib.fx.Ndf;
import com.quantfinlib.fx.SwapPointsCurve;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.microstructure.TransactionCostAnalyzer;
import com.quantfinlib.ml.RegimeDetector;
import com.quantfinlib.optimization.RiskParityOptimizer;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.rates.BondPricer;
import com.quantfinlib.risk.PreTradeLimitChecker;
import com.quantfinlib.risk.SettlementRiskAnalyzer;
import com.quantfinlib.trading.HftRiskGate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression pins for the whole-project review round: every test here
 * fails on the pre-review code and encodes the corrected behavior.
 */
class ReviewFixesRoundTest {

    // ------------------------------------------------ risk gates: reducing orders

    @Test
    void overLimitPositionStillAcceptsRiskReducingOrders() {
        // Two 800-lot fills against a 1000 cap (each passed pre-fill);
        // the gate must then accept a 500-lot reducing SELL even though
        // |1600-500| is still above the cap — and keep rejecting adds.
        // The gate does not reserve in-flight quantity: both orders are
        // checked BEFORE either fills (each projects 800 <= 1000), then
        // both fill — the classic route to an over-limit book.
        HftRiskGate gate = new HftRiskGate(4).maxPositionQuantity(1000);
        assertEquals(HftRiskGate.OK, gate.check(0, Side.BUY, 800, 100));
        assertEquals(HftRiskGate.OK, gate.check(0, Side.BUY, 800, 100));
        gate.onFill(0, Side.BUY, 800);
        gate.onFill(0, Side.BUY, 800);
        assertEquals(1600, gate.position(0));

        assertEquals(HftRiskGate.OK, gate.check(0, Side.SELL, 500, 100),
                "the de-risking trade must pass");
        assertEquals(HftRiskGate.REJECT_POSITION, gate.check(0, Side.BUY, 1, 100),
                "adding to the breach stays rejected");
        // The flip trap: SELL 3000 from +1600 lands at -1400 — smaller in
        // magnitude, but a brand-new over-cap SHORT. Not a hedge; reject.
        assertEquals(HftRiskGate.REJECT_POSITION, gate.check(0, Side.SELL, 3000, 100),
                "flipping through zero past the cap is a new breach");
        // Flattening exactly to zero is always fine.
        assertEquals(HftRiskGate.OK, gate.check(0, Side.SELL, 1600, 100));
    }

    @Test
    void preTradeCheckerAgreesOnTheReducingRule() {
        PreTradeLimitChecker checker = new PreTradeLimitChecker().maxPositionQuantity(1000);
        PreTradeLimitChecker.CheckResult reduce = checker.check(
                new PreTradeLimitChecker.OrderRequest("AAPL", Side.SELL, 500, 100, "CP1"),
                100, 1600, 0);
        assertTrue(reduce.approved(), "reducing from 1600 to 1100 must pass: "
                + reduce.violations());
        PreTradeLimitChecker.CheckResult add = checker.check(
                new PreTradeLimitChecker.OrderRequest("AAPL", Side.BUY, 10, 100, "CP1"),
                100, 1600, 0);
        assertFalse(add.approved(), "growing the breach must still fail");
        PreTradeLimitChecker.CheckResult flip = checker.check(
                new PreTradeLimitChecker.OrderRequest("AAPL", Side.SELL, 3000, 100, "CP1"),
                100, 1600, 0);
        assertFalse(flip.approved(), "flipping to an over-cap short is a new breach");
    }

    @Test
    void unattainablePriceCannotPoisonAVolSurface() {
        // impliedVol returns NaN for a price above the no-arb bound; the
        // surface builder must REFUSE the pillar, not interpolate NaN
        // into every neighboring strike.
        assertThrows(IllegalArgumentException.class, () ->
                com.quantfinlib.pricing.VolSurface.builder()
                        .addFromPrice(OptionType.CALL, 150, 100, 100, 0.0, 0.0, 1.0));
    }

    // ------------------------------------------------------ POV participation cap

    @Test
    void povNeverExecutesAboveItsParticipationRateEvenWithStrongAlpha() {
        // participation 10%, market volume 10_000 -> target 1_000. A
        // maximal alpha signal used to multiply the due quantity 4x; the
        // rate is a promise, so urgency may only damp POV, never push it.
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000,
                Benchmark.PARTICIPATION, 0.10, 1.0, 1.0);
        e.onMarketVolume(10_000);
        MarketState maxAlpha = new MarketState(100, 0, 0,
                Double.POSITIVE_INFINITY, 0.5, 1.0, 0);
        long due = e.dueQuantity(0.5, maxAlpha);
        assertTrue(due <= 1_000, "POV must respect its rate, got " + due);
        e.onFill(due);
        assertTrue(e.executed() <= 1_000);
    }

    // ------------------------------------------- settlement tie-break conservatism

    @Test
    void coincidentPayAndReceiveCountsThePaymentFirst() {
        // Overlapping Herstatt windows are simply additive: 100 + 80.
        List<SettlementRiskAnalyzer.SettlementLeg> legs = List.of(
                new SettlementRiskAnalyzer.SettlementLeg("CP", "USD", 100, 1000, "EUR", 100, 5000),
                new SettlementRiskAnalyzer.SettlementLeg("CP", "USD", 80, 2000, "EUR", 80, 6000));
        assertEquals(180, SettlementRiskAnalyzer.peakExposure(legs, "CP"), 1e-9);

        // The discriminating case: T1's receive and T2's payment land on
        // the SAME millisecond (t=3000). Worst case, our money left before
        // theirs arrived: the peak must touch 100 + 80 = 180 — a
        // receipts-first tie-break would report only 100.
        List<SettlementRiskAnalyzer.SettlementLeg> tied = List.of(
                new SettlementRiskAnalyzer.SettlementLeg("CP", "USD", 100, 1000, "EUR", 100, 3000),
                new SettlementRiskAnalyzer.SettlementLeg("CP", "USD", 80, 3000, "EUR", 80, 6000));
        assertEquals(180, SettlementRiskAnalyzer.peakExposure(tied, "CP"), 1e-9);
    }

    // ---------------------------------------------------- Black-Scholes edge math

    @Test
    void zeroVolPricesDiscountedForwardIntrinsicNotNan() {
        // ATM-forward, zero vol: worth exactly 0 — the old code returned NaN.
        double atm = BlackScholes.price(OptionType.CALL, 100, 100, 0.0, 0.0, 0.0, 1.0);
        assertEquals(0.0, atm, 0.0);
        assertFalse(Double.isNaN(atm));
        // ITM call, zero vol: S - K e^{-rT} exactly.
        double itm = BlackScholes.price(OptionType.CALL, 120, 100, 0.05, 0.0, 0.0, 1.0);
        assertEquals(120 - 100 * Math.exp(-0.05), itm, 1e-12);
        // OTM put on the forward: zero, not negative.
        assertEquals(0.0, BlackScholes.price(OptionType.PUT, 120, 100, 0.05, 0, 0, 1), 0.0);
    }

    @Test
    void unattainablePricesImplyNanNotTheSearchBound() {
        // Above the maximum call value (S itself): no vol explains it.
        assertTrue(Double.isNaN(
                BlackScholes.impliedVol(OptionType.CALL, 150, 100, 100, 0.0, 0.0, 1.0)));
        // Below intrinsic: no vol explains that either.
        assertTrue(Double.isNaN(
                BlackScholes.impliedVol(OptionType.CALL, 10, 120, 100, 0.0, 0.0, 1.0)));
        // Attainable prices still round-trip.
        double px = BlackScholes.price(OptionType.CALL, 100, 105, 0.02, 0.0, 0.35, 0.5);
        assertEquals(0.35, BlackScholes.impliedVol(OptionType.CALL, px, 100, 105, 0.02, 0.0, 0.5),
                1e-6);
    }

    // ----------------------------------------------------------- YTM bracket gate

    @Test
    void impossibleBondPriceThrowsInsteadOfReturningTheEndpoint() {
        // A 2y zero at "price 50,000 per 100 face" has no yield above -90%.
        assertThrows(IllegalArgumentException.class,
                () -> BondPricer.yieldToMaturity(50_000, 100, 0.0, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> BondPricer.yieldToMaturity(0.0001, 100, 0.0, 1, 2));
        // Sane prices still solve: par bond yields its coupon.
        assertEquals(0.05, BondPricer.yieldToMaturity(100, 100, 0.05, 2, 5), 1e-9);
    }

    // ----------------------------------------------------------------- TCA gates

    @Test
    void tcaRefusesGarbageBenchmarkPrices() {
        List<Execution> fills = List.of(
                new Execution("X", Side.BUY, 100.02, 1000, 0, "V"));
        assertThrows(IllegalArgumentException.class, () -> TransactionCostAnalyzer.analyze(
                fills, 0.0, 100.0, new double[]{100.0}));   // zero arrival mid
        assertThrows(IllegalArgumentException.class, () -> TransactionCostAnalyzer.analyze(
                fills, 100.0, 100.0, new double[]{0.0}));   // zero fill mid
        assertThrows(IllegalArgumentException.class, () -> TransactionCostAnalyzer.analyze(
                fills, 100.0, Double.NaN, new double[]{100.0})); // NaN vwap
    }

    // ------------------------------------------------------------- aged NDF mark

    @Test
    void ndfInsideItsFixingWindowMarksOffSpotInsteadOfThrowing() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        LocalDate trade = LocalDate.of(2026, 1, 7);
        SwapPointsCurve curve = SwapPointsCurve.builder(eurusd, trade, 1.0850)
                .add("1M", 12.6).add("3M", 38.4).build();
        // Fixing date on (or before) the curve's spot: the forward curve
        // starts at spot, so the mark degrades to the spot outright.
        Ndf aged = Ndf.of(eurusd, 10_000_000, 1.0800,
                curve.spotDate(), curve.spotDate().plusDays(2));
        double mtm = aged.markToMarket(curve);
        assertEquals(aged.settlementAmount(1.0850), mtm, 1e-9);

        Ndf past = Ndf.of(eurusd, 10_000_000, 1.0800,
                curve.spotDate().minusDays(3), curve.spotDate());
        assertEquals(past.settlementAmount(1.0850), past.markToMarket(curve), 1e-9);
    }

    // ------------------------------------------------- RegimeDetector consistency

    @Test
    void regimeFitEndsOnAnEStepSoProbabilitiesMatchParameters() {
        double[] returns = new double[400];
        Random rnd = new Random(7);
        for (int i = 0; i < 400; i++) {
            returns[i] = (i < 200 ? 0.005 : 0.03) * rnd.nextGaussian();
        }
        // Zero M-steps allowed: parameters must be the documented initial
        // guess (transition 0.95/0.05) and the likelihood/probabilities
        // must be computed UNDER those parameters — the old loop shape
        // returned parameters one M-step ahead of the probabilities.
        RegimeDetector.RegimeModel untrained = RegimeDetector.fit(returns, 0);
        assertEquals(0.95, untrained.transition()[0][0], 1e-12);
        assertEquals(0.95, untrained.transition()[1][1], 1e-12);
        assertTrue(Double.isFinite(untrained.logLikelihood()));

        // Training moves the parameters and improves the likelihood.
        RegimeDetector.RegimeModel trained = RegimeDetector.fit(returns, 50);
        assertTrue(trained.logLikelihood() > untrained.logLikelihood());
    }

    // ------------------------------------------------------ risk parity gates

    @Test
    void riskParityRefusesMisalignedOrDegenerateInput() {
        double[][] cov = {{0.04, 0.0}, {0.0, 0.09}};
        assertThrows(IllegalArgumentException.class,
                () -> RiskParityOptimizer.equalRiskContribution(new double[]{0.1}, cov));
        double[][] degenerate = {{0.04, 0.0}, {0.0, 0.0}};
        assertThrows(IllegalArgumentException.class,
                () -> RiskParityOptimizer.equalRiskContribution(new double[]{0.1, 0.1}, degenerate));
    }
}

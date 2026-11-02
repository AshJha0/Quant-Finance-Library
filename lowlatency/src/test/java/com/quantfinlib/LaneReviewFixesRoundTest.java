package com.quantfinlib;

import com.quantfinlib.core.Side;
import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import com.quantfinlib.execution.BenchmarkExecutor;
import com.quantfinlib.fx.CurrencyPair;
import com.quantfinlib.fx.Ndf;
import com.quantfinlib.fx.SwapPointsCurve;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.microstructure.TransactionCostAnalyzer;
import com.quantfinlib.ml.RegimeDetector;
import com.quantfinlib.optimization.RiskParityOptimizer;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.rates.BondPricer;
import com.quantfinlib.risk.PreTradeLimitChecker;
import com.quantfinlib.risk.SettlementRiskAnalyzer;
import com.quantfinlib.trading.HftRiskGate;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review-round fix pins for trading-lane types, split out of the main
 * module's ReviewFixesRoundTest in 3.0 when {@code trading} moved to the
 * low-latency module.
 */
class LaneReviewFixesRoundTest {

    @Test
    void overLimitPositionStillAcceptsRiskReducingOrders() {
        // Two 800-lot fills against a 1000 cap (each passed pre-fill);
        // the gate must then accept a 500-lot reducing sell even though
        // |1600-500| is still above the cap, and keep rejecting adds.
        // The gate does not reserve in-flight quantity: both orders are
        // checked before either fills (each projects 800 <= 1000), then
        // both fill. That is the classic route to an over-limit book.
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
}

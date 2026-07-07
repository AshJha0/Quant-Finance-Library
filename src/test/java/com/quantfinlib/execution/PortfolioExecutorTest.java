package com.quantfinlib.execution;

import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Portfolio-level scheduling: leg balance + capacity over per-symbol executors. */
class PortfolioExecutorTest {

    @Test
    void unconstrainedIsATransparentPassthrough() {
        var pe = new PortfolioExecutor(2, PortfolioExecutor.Config.unconstrained());
        int buy = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int sell = pe.add(BenchmarkExecutor.of(Side.SELL, 6_000, Benchmark.TWAP));

        var states = new MarketState[]{MarketState.neutral(100, 0.5),
                MarketState.neutral(50, 0.5)};
        long[] due = new long[2];
        pe.decide(0.5, states, due);

        assertEquals(5_000, due[buy], "TWAP at f=0.5: exactly the child's own due");
        assertEquals(3_000, due[sell]);
    }

    @Test
    void legBalanceStopsTheAheadLegWhenTheOtherIsStuck() {
        // Dollar-neutral transition: buy 10k @ 100, sell 10k @ 100.
        var config = new PortfolioExecutor.Config(100_000, Double.POSITIVE_INFINITY);
        var pe = new PortfolioExecutor(2, config);
        int buy = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int sell = pe.add(BenchmarkExecutor.of(Side.SELL, 10_000, Benchmark.TWAP));

        // The buy leg filled 5,000 @ 100; the sell leg got nothing.
        pe.onFill(buy, 5_000, 100);
        assertEquals(500_000, pe.netNotional(), 1e-9, "half a million long mid-flight");

        // Next interval: the sell side's book is empty (depth 0) — its child
        // can send nothing. Without the band the buy child would keep going.
        long[] due = new long[2];
        var buyState = MarketState.neutral(100, 0.6);
        var sellState = new MarketState(100, 0, 0, 0, 0.6, 0, 0);   // no depth
        pe.decide(0.6, new MarketState[]{buyState, sellState}, due);

        assertEquals(0, due[sell], "nothing to sell into");
        assertEquals(0, due[buy], "the band stops the buy leg from running further ahead");

        // Prove the band (not the child) is what stopped it.
        var free = new PortfolioExecutor(2, PortfolioExecutor.Config.unconstrained());
        int b2 = free.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        free.add(BenchmarkExecutor.of(Side.SELL, 10_000, Benchmark.TWAP));
        free.onFill(b2, 5_000, 100);
        long[] due2 = new long[2];
        free.decide(0.6, new MarketState[]{buyState, sellState}, due2);
        assertEquals(1_000, due2[b2], "unconstrained, the buy child continues its schedule");
    }

    @Test
    void legBalanceThrottlesPartiallyNotJustToZero() {
        var config = new PortfolioExecutor.Config(300_000, Double.POSITIVE_INFINITY);
        var pe = new PortfolioExecutor(2, config);
        int buy = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int sell = pe.add(BenchmarkExecutor.of(Side.SELL, 10_000, Benchmark.TWAP));
        pe.onFill(buy, 2_000, 100);            // net +200k

        long[] due = new long[2];
        var buyState = MarketState.neutral(100, 0.5);
        var sellState = new MarketState(100, 0, 0, 0, 0.5, 0, 0);   // sells stuck
        pe.decide(0.5, new MarketState[]{buyState, sellState}, due);

        // Buy child wants 3,000 (300k); band allows net to reach 300k from
        // 200k -> 100k of buys = 1,000 shares.
        assertEquals(1_000, due[buy], "scaled to the band, not zeroed");
    }

    @Test
    void capacityGoesToTheRiskiestNamesWhenTheBudgetBinds() {
        var config = new PortfolioExecutor.Config(Double.POSITIVE_INFINITY, 500_000);
        var pe = new PortfolioExecutor(2, config);
        int calm = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int wild = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));

        // Same schedule, same price; the wild name carries vol regime 1.
        // Its child self-damps (passive benchmark) to 2,500 (250k); the calm
        // one asks 5,000 (500k). Total 750k > 500k budget. Risk weights:
        // wild (1+1)x250k = 500k, calm (1+0)x500k = 500k -> equal allocations
        // of 250k each: the calm name is cut in half, the wild one keeps all
        // it asked for despite asking for half as much.
        var calmState = MarketState.neutral(100, 0.5);
        var wildState = new MarketState(100, 0, 1.0, Double.POSITIVE_INFINITY, 0.5, 0, 0);
        long[] due = new long[2];
        pe.decide(0.5, new MarketState[]{calmState, wildState}, due);

        assertEquals(2_500, due[wild], "the risky name keeps its full (self-damped) due");
        assertEquals(2_500, due[calm], "the calm name absorbs the cut");
        assertTrue(due[calm] * 100.0 + due[wild] * 100.0 <= 500_000, "budget respected");
    }

    @Test
    void capacityAllocationCannotReviolateTheLegBand() {
        // Regression: the risk-weighted capacity cut hits the two legs
        // asymmetrically, so it can push |net| back over the band the first
        // leg-balance pass enforced — the band must be re-applied after it.
        var config = new PortfolioExecutor.Config(100_000, 1_000_000);
        var pe = new PortfolioExecutor(2, config);
        int buy = pe.add(BenchmarkExecutor.of(Side.BUY, 20_000, Benchmark.TWAP));
        int sell = pe.add(BenchmarkExecutor.of(Side.SELL, 20_000, Benchmark.TWAP));

        // Calm buy leg, wild sell leg (vol regime 1 -> sell child self-damps
        // AND earns double risk weight when the budget binds).
        var buyState = MarketState.neutral(100, 0.5);
        var sellState = new MarketState(100, 0, 1.0, Double.POSITIVE_INFINITY, 0.5, 0, 0);
        long[] due = new long[2];
        pe.decide(0.5, new MarketState[]{buyState, sellState}, due);

        // Band pass 1: buys 1M vs sells 500k -> buys scaled to 600k (6,000).
        // Capacity: total 1.1M > 1M; weights buy 600k, sell 1M -> buy cut to
        // 375k (3,750), sell keeps 500k -> net now -125k: RE-VIOLATED.
        // Band pass 2: sells scaled to 475k (4,750) -> net exactly -100k.
        assertEquals(3_750, due[buy]);
        assertEquals(4_750, due[sell]);
        double projectedNet = due[buy] * 100.0 - due[sell] * 100.0;
        assertTrue(Math.abs(projectedNet) <= 100_000,
                "the band holds after the capacity cut: " + projectedNet);
        assertTrue(due[buy] * 100.0 + due[sell] * 100.0 <= 1_000_000, "budget still holds");
    }

    @Test
    void covarianceUpgradesCapacityFromDiagonalToBasketRisk() {
        // Three equal buys; A and B move together, C is independent. Under
        // the diagonal fallback (all vols equal) the budget splits evenly —
        // but A+B are ONE concentrated risk, and the covariance model sees
        // it: the correlated pair carries MRC 0.4 each vs C's 0.2, so it
        // gets more of the binding budget.
        var cov = new com.quantfinlib.microstructure.EwmaCovariance(3, 0.97);
        var rnd = new java.util.Random(7);
        double[] r = new double[3];
        for (int i = 0; i < 2_000; i++) {
            double g = 1e-4 * rnd.nextGaussian();
            r[0] = g;
            r[1] = g;
            r[2] = 1e-4 * rnd.nextGaussian();
            cov.onReturns(r);
        }

        var pe = new PortfolioExecutor(3,
                new PortfolioExecutor.Config(Double.POSITIVE_INFINITY, 750_000));
        int a = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int b = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int c = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        pe.useRiskModel(cov);

        var s = MarketState.neutral(100, 0.5);              // vols all neutral:
        long[] due = new long[3];                           // only the matrix differs
        pe.decide(0.5, new MarketState[]{s, s, s}, due);

        assertEquals(due[a], due[b], "symmetric legs get symmetric capacity");
        assertTrue(due[a] > due[c],
                "the correlated pair carries more basket risk: " + due[a] + " vs " + due[c]);
        assertTrue((due[a] + due[b] + due[c]) * 100.0 <= 750_000, "budget holds");

        // A model with no risk picture yet falls back to the diagonal form.
        var pe2 = new PortfolioExecutor(1,
                new PortfolioExecutor.Config(Double.POSITIVE_INFINITY, 100_000));
        pe2.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        pe2.useRiskModel(new com.quantfinlib.microstructure.EwmaCovariance(1));
        long[] one = new long[1];
        pe2.decide(0.5, new MarketState[]{MarketState.neutral(100, 0.5)}, one);
        assertEquals(1_000, one[0], "unlearned model: plain budget cut still applies");

        assertThrows(IllegalArgumentException.class, () -> pe.useRiskModel(
                new com.quantfinlib.microstructure.EwmaCovariance(2)));
    }

    @Test
    void overlaysOnlyEverReduceAChildsOwnDue() {
        var config = new PortfolioExecutor.Config(50_000, 100_000);
        var pe = new PortfolioExecutor(3, config);
        int a = pe.add(BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP));
        int b = pe.add(BenchmarkExecutor.of(Side.SELL, 4_000, Benchmark.ARRIVAL_PRICE));
        int c = pe.add(BenchmarkExecutor.of(Side.BUY, 2_000, Benchmark.CLOSING_PRICE));

        var states = new MarketState[]{MarketState.neutral(100, 0.4),
                MarketState.neutral(25, 0.4), MarketState.neutral(400, 0.4)};
        long[] portfolio = new long[3];
        pe.decide(0.4, states, portfolio);

        long[] own = {
                BenchmarkExecutor.of(Side.BUY, 10_000, Benchmark.TWAP)
                        .dueQuantity(0.4, states[0]),
                BenchmarkExecutor.of(Side.SELL, 4_000, Benchmark.ARRIVAL_PRICE)
                        .dueQuantity(0.4, states[1]),
                BenchmarkExecutor.of(Side.BUY, 2_000, Benchmark.CLOSING_PRICE)
                        .dueQuantity(0.4, states[2])};
        assertTrue(portfolio[a] <= own[0] && portfolio[b] <= own[1] && portfolio[c] <= own[2],
                "damp-only: no child is ever pushed past its own schedule");
    }

    @Test
    void fillsFlowThroughToChildrenAndTheLedger() {
        var pe = new PortfolioExecutor(2, PortfolioExecutor.Config.unconstrained());
        int buy = pe.add(BenchmarkExecutor.of(Side.BUY, 1_000, Benchmark.TWAP));
        int sell = pe.add(BenchmarkExecutor.of(Side.SELL, 1_000, Benchmark.TWAP));

        pe.onFill(buy, 1_000, 100);
        pe.onFill(sell, 400, 50);
        assertEquals(1_000, pe.child(buy).executed());
        assertEquals(100_000 - 20_000, pe.netNotional(), 1e-9);
        assertTrue(pe.child(buy).done());
        assertFalse(pe.done(), "sell leg still working");
        pe.onFill(sell, 600, 50);
        assertTrue(pe.done());

        // A NaN fill price advances the child but cannot poison the ledger.
        var pe2 = new PortfolioExecutor(1, PortfolioExecutor.Config.unconstrained());
        int h = pe2.add(BenchmarkExecutor.of(Side.BUY, 100, Benchmark.TWAP));
        pe2.onFill(h, 100, Double.NaN);
        assertEquals(100, pe2.child(h).executed());
        assertEquals(0, pe2.netNotional(), 1e-9);
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class,
                () -> new PortfolioExecutor.Config(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PortfolioExecutor.Config(1, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new PortfolioExecutor(0, PortfolioExecutor.Config.unconstrained()));

        var pe = new PortfolioExecutor(1, PortfolioExecutor.Config.unconstrained());
        pe.add(BenchmarkExecutor.of(Side.BUY, 100, Benchmark.TWAP));
        assertThrows(IllegalStateException.class,
                () -> pe.add(BenchmarkExecutor.of(Side.BUY, 100, Benchmark.TWAP)));
        assertThrows(IllegalArgumentException.class,
                () -> pe.decide(0.5, new MarketState[0], new long[1]));
    }

    @Test
    void decideIsAllocationFree() {
        var pe = new PortfolioExecutor(4, new PortfolioExecutor.Config(1e9, 1e8));
        for (int i = 0; i < 2; i++) {
            pe.add(BenchmarkExecutor.of(Side.BUY, 100_000_000L, Benchmark.TWAP));
            pe.add(BenchmarkExecutor.of(Side.SELL, 100_000_000L, Benchmark.TWAP));
        }
        var states = new MarketState[]{MarketState.neutral(100, 0.5),
                MarketState.neutral(100, 0.5), MarketState.neutral(50, 0.5),
                MarketState.neutral(50, 0.5)};
        long[] due = new long[4];

        long blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            pe.decide(0.5, states, due);
            blackhole += due[0] + due[3];
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            pe.decide(0.5, states, due);
            blackhole += due[0] + due[3];
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "decide allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }
}

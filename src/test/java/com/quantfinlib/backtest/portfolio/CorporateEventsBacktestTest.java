package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CorporateEvents;
import com.quantfinlib.data.CorporateEvents.CashStockMerger;
import com.quantfinlib.data.CorporateEvents.Dividend;
import com.quantfinlib.data.CorporateEvents.DividendKind;
import com.quantfinlib.data.CorporateEvents.Event;
import com.quantfinlib.data.CorporateEvents.ReverseSplit;
import com.quantfinlib.data.CorporateEvents.RightsIssue;
import com.quantfinlib.data.CorporateEvents.SpinOff;
import com.quantfinlib.data.CorporateEvents.StockDividend;
import com.quantfinlib.data.CorporateEvents.SymbolChange;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Extended corporate events booked by PortfolioBacktester. Every case is a
 * value-neutral event on an unadjusted tape, so a buy-and-hold book must keep
 * its equity at exactly 1,000,000; the share and cash numbers are hand computed.
 */
class CorporateEventsBacktestTest {

    private static final long DAY = 86_400_000L;
    private static final long EX = 2 * DAY;
    private static final int BARS = 5;

    private static PortfolioStrategy buy(String symbol) {
        return new PortfolioStrategy() {
            @Override
            public String name() {
                return "buy";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return index == 0 ? Map.of(symbol, 1.0) : Map.of();
            }
        };
    }

    private static PortfolioBacktester.Config config() {
        return new PortfolioBacktester.Config(1_000_000, 0, BARS, 252, null);
    }

    /** {@code cum} for bars 0..1, {@code ex} from bar 2. */
    private static BarSeries tape(String sym, double cum, double ex) {
        return BarSeries.of(sym, new double[] {cum, cum, ex, ex, ex});
    }

    private static PortfolioBacktester.Result run(Map<String, BarSeries> data, String held,
                                                  List<Event> events) {
        return PortfolioBacktester.run(buy(held), data, data, config(), null, Map.of(), null,
                Map.of(held, events));
    }

    private static void assertFlat(PortfolioBacktester.Result r) {
        for (int i = 0; i < r.equityCurveLength(); i++) {
            assertEquals(1_000_000, r.equityCurve(i), 1e-6, "bar " + i);
        }
        assertEquals(1, r.lifecycleEventsApplied());
    }

    @Test
    void emptyEventMapMatchesTheTwoOneRun() {
        Map<String, BarSeries> data = Map.of("EQ", tape("EQ", 100, 90));
        PortfolioBacktester.Result base = PortfolioBacktester.run(buy("EQ"), data, data, config(),
                null, Map.of(), null);
        PortfolioBacktester.Result opt = run(data, "EQ", List.of());
        assertArrayEquals(base.equityCurveCopy(), opt.equityCurveCopy());
        assertEquals(base, opt);
    }

    @Test
    void bonusIssueDoublesShares() {
        // 10,000 sh at 100; 1-for-1 bonus, price 50 -> 20,000 sh
        PortfolioBacktester.Result r = run(Map.of("EQ", tape("EQ", 100, 50)), "EQ",
                List.of(new StockDividend(EX, 1.0)));
        assertFlat(r);
        assertEquals(20_000, r.finalPositions().get("EQ"), 1e-9);
    }

    @Test
    void reverseSplitPaysCashInLieu() {
        // 10,000 / 3 = 3,333.33 -> 3,333 sh at 300 = 999,900; 1/3 sh * 300 = 100 cash
        PortfolioBacktester.Result r = run(Map.of("EQ", tape("EQ", 100, 300)), "EQ",
                List.of(new ReverseSplit(EX, 3)));
        assertFlat(r);
        assertEquals(3_333, r.finalPositions().get("EQ"), 1e-9);
    }

    @Test
    void specialDividendCreditsCash() {
        // 10,000 sh * 10 = 100,000 cash; price 100 -> 90
        PortfolioBacktester.Result r = run(Map.of("EQ", tape("EQ", 100, 90)), "EQ",
                List.of(new Dividend(EX, 10, DividendKind.SPECIAL)));
        assertFlat(r);
        assertEquals(100_000, r.dividendCashCredited(), 1e-9);
    }

    @Test
    void spinOffDeliversChildShares() {
        // 1 child per 2 at 40; parent 100 -> 80; 5,000 CH * 40 + 10,000 * 80 = 1,000,000
        Map<String, BarSeries> data = Map.of("EQ", tape("EQ", 100, 80), "CH", tape("CH", 40, 40));
        PortfolioBacktester.Result r = run(data, "EQ",
                List.of(new SpinOff(EX, "CH", "C1", 0.5, 40)));
        assertFlat(r);
        assertEquals(5_000, r.finalPositions().get("CH"), 1e-9);
    }

    @Test
    void rightsSoldAtTheoreticalValue() {
        // 1 for 4 at 80: TERP 96; rights worth 4 per share -> 40,000 cash
        PortfolioBacktester.Result r = run(Map.of("EQ", tape("EQ", 100, 96)), "EQ",
                List.of(new RightsIssue(EX, 0.25, 80)));
        assertFlat(r);
        assertEquals(10_000, r.finalPositions().get("EQ"), 1e-9);
    }

    @Test
    void symbolChangeMovesThePosition() {
        Map<String, BarSeries> data = Map.of("EQ", tape("EQ", 100, 100), "NEW", tape("NEW", 100, 100));
        PortfolioBacktester.Result r = run(data, "EQ", List.of(new SymbolChange(EX, "NEW", "I2")));
        assertFlat(r);
        assertEquals(10_000, r.finalPositions().get("NEW"), 1e-9);
        assertEquals(null, r.finalPositions().get("EQ"));
    }

    @Test
    void cashStockMergerAtTheCashCap() {
        // 20,000 TGT at 50; cash 50 or 0.8 ACQ at 62.5; 40 % cash cap
        // 8,000 sh -> 400,000 cash; 12,000 * 0.8 = 9,600 ACQ = 600,000
        Map<String, BarSeries> data = new HashMap<>();
        data.put("TGT", tape("TGT", 50, 50));
        data.put("ACQ", tape("ACQ", 62.5, 62.5));
        PortfolioBacktester.Result r = run(data, "TGT",
                List.of(new CashStockMerger(EX, "ACQ", "A1", 50, 0.8, 0.4, 62.5)));
        assertFlat(r);
        assertEquals(9_600, r.finalPositions().get("ACQ"), 1e-9);
        assertEquals(null, r.finalPositions().get("TGT"));
    }

    @Test
    void missingChildSeriesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("EQ", tape("EQ", 100, 80)),
                "EQ", List.<Event>of(new SpinOff(EX, "CH", "C1", 0.5, 40))));
    }

    @Test
    void adjustedSignalsAndBookedEventsAgree() {
        // the adjusted tape for a 1-for-1 bonus is flat at 50, same as booked equity / 20,000
        BarSeries raw = tape("EQ", 100, 50);
        List<Event> evs = List.of(new StockDividend(EX, 1.0));
        BarSeries adj = CorporateEvents.adjust(raw, evs);
        PortfolioBacktester.Result r = run(Map.of("EQ", raw), "EQ", evs);
        for (int i = 0; i < BARS; i++) {
            assertEquals(adj.close(i) / adj.close(0), r.equityCurve(i) / r.equityCurve(0), 1e-12);
        }
    }
}

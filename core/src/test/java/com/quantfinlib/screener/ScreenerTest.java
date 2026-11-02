package com.quantfinlib.screener;

import com.quantfinlib.TestData;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenerTest {

    private static StockSnapshot stock(String symbol, double drift, double vol, long seed,
                                       Fundamentals fundamentals) {
        BarSeries s = TestData.gbmSeries(symbol, 400, 100, drift, vol, seed);
        return new StockSnapshot(symbol, s, fundamentals);
    }

    private final List<StockSnapshot> universe = List.of(
            stock("CHEAP_QUALITY", 0.15, 0.18, 1, new Fundamentals(20e9, 12, 1.5, 5.0, 0.25, 0.03, 0.4)),
            stock("EXPENSIVE_GROWTH", 0.25, 0.30, 2, new Fundamentals(80e9, 45, 12, 1.2, 0.10, 0.0, 1.2)),
            stock("SMALL_JUNK", -0.10, 0.40, 3, new Fundamentals(0.5e9, -8, 0.8, -1.0, -0.05, 0.0, 3.5)));

    @Test
    void fundamentalFiltersSelectExpectedStocks() {
        StockScreener screener = new StockScreener(universe);
        List<StockSnapshot> value = screener.screen(
                FundamentalFilters.peBelow(20),
                FundamentalFilters.roeAbove(0.15),
                FundamentalFilters.debtToEquityBelow(1.0));
        assertEquals(1, value.size());
        assertEquals("CHEAP_QUALITY", value.getFirst().symbol());
    }

    @Test
    void technicalFiltersRunOnAllStocks() {
        StockScreener screener = new StockScreener(universe);
        // Should not throw and should return a subset.
        List<StockSnapshot> hits = screener.screen(
                TechnicalFilters.rsiAbove(14, 0),
                TechnicalFilters.priceAboveVwap().or(TechnicalFilters.priceAboveVwap().negate()));
        assertEquals(universe.size(), hits.size());
    }

    @Test
    void filtersCompose() {
        ScreenFilter largeCap = FundamentalFilters.marketCapAbove(10e9);
        ScreenFilter quality = FundamentalFilters.roeAbove(0.2);
        StockScreener screener = new StockScreener(universe);
        assertEquals(1, screener.screen(largeCap.and(quality)).size());
        assertEquals(2, screener.screen(largeCap.or(quality)).size());
    }

    @Test
    void rankingPrefersHighRoeLowPe() {
        RankingEngine ranking = new RankingEngine()
                .addCriterion("ROE", 1.0, s -> s.fundamentals().roe())
                .addCriterion("PE", -1.0, s -> s.fundamentals().peRatio());
        List<RankingEngine.ScoredStock> ranked = ranking.rank(universe);
        assertEquals("CHEAP_QUALITY", ranked.getFirst().stock().symbol());
        assertTrue(ranked.getFirst().score() >= ranked.getLast().score());
    }

    @Test
    void csvExportWritesAllRows(@TempDir Path dir) throws Exception {
        RankingEngine ranking = new RankingEngine()
                .addCriterion("ROE", 1.0, s -> s.fundamentals().roe());
        Path out = dir.resolve("screen.csv");
        StockScreener.exportCsv(out, ranking.rank(universe));
        List<String> lines = Files.readAllLines(out);
        assertEquals(universe.size() + 1, lines.size());
        assertTrue(lines.getFirst().startsWith("symbol,score"));
    }
}

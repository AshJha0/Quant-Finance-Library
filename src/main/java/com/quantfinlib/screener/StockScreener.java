package com.quantfinlib.screener;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Professional Stock Screener: applies technical and fundamental filters to a
 * universe, optionally ranks matches, and exports results to CSV.
 */
public final class StockScreener {

    private final List<StockSnapshot> universe;

    public StockScreener(List<StockSnapshot> universe) {
        this.universe = List.copyOf(universe);
    }

    /** Returns stocks matching every supplied filter. */
    public List<StockSnapshot> screen(ScreenFilter... filters) {
        List<StockSnapshot> out = new ArrayList<>();
        outer:
        for (StockSnapshot s : universe) {
            for (ScreenFilter f : filters) {
                if (!f.matches(s)) {
                    continue outer;
                }
            }
            out.add(s);
        }
        return out;
    }

    /** Screens then ranks the survivors best-first. */
    public List<RankingEngine.ScoredStock> screenAndRank(RankingEngine ranking, ScreenFilter... filters) {
        return ranking.rank(screen(filters));
    }

    /** Exports ranked results (symbol, score, last close, fundamentals) to CSV. */
    public static void exportCsv(Path path, List<RankingEngine.ScoredStock> results) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("symbol,score,lastClose,marketCap,peRatio,pbRatio,eps,roe,dividendYield,debtToEquity\n");
            for (RankingEngine.ScoredStock r : results) {
                Fundamentals f = r.stock().fundamentals();
                w.write(String.format(Locale.ROOT, "%s,%.4f,%.4f,%.0f,%.2f,%.2f,%.2f,%.4f,%.4f,%.2f%n",
                        r.stock().symbol(), r.score(), r.stock().lastClose(),
                        f.marketCap(), f.peRatio(), f.pbRatio(), f.eps(),
                        f.roe(), f.dividendYield(), f.debtToEquity()));
            }
        }
    }
}

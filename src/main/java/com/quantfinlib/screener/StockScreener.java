package com.quantfinlib.screener;

import com.quantfinlib.data.PointInTimeUniverse;

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
 *
 * <p><b>Survivorship caution</b>: the screener sees exactly the universe it
 * is given. A universe built from <em>today's</em> constituents has already
 * dropped every delisted or acquired name — the bias enters before any
 * filter runs. For historical screens, construct the snapshot list
 * point-in-time via {@link #membersAsOf} with a
 * {@link PointInTimeUniverse} that includes dead tickers.</p>
 */
public final class StockScreener {

    private final List<StockSnapshot> universe;

    public StockScreener(List<StockSnapshot> universe) {
        this.universe = List.copyOf(universe);
    }

    /**
     * Filters snapshots to the point-in-time members at {@code asOfTimestamp}
     * — the survivorship-safe way to build a historical screening universe
     * (assuming the snapshot list itself includes the dead tickers).
     */
    public static List<StockSnapshot> membersAsOf(List<StockSnapshot> snapshots,
                                                  PointInTimeUniverse universe,
                                                  long asOfTimestamp) {
        List<StockSnapshot> members = new ArrayList<>();
        for (StockSnapshot s : snapshots) {
            if (universe.isMember(s.symbol(), asOfTimestamp)) {
                members.add(s);
            }
        }
        return members;
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

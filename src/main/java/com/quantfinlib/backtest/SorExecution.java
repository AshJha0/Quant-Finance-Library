package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.execution.SmartOrderRouter;
import com.quantfinlib.execution.VenueQuote;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart-order-routed execution over a synthetic fragmented market. Each bar,
 * a top-of-book is derived from the bar (close ± half-spread; each venue's
 * touch size = its liquidity share of the bar volume) and the parent order is
 * split by {@link SmartOrderRouter}. Liquidity is finite per bar, so large
 * parents fill across several bars — making liquidity cost and fill duration
 * part of the backtest instead of an assumption.
 */
public final class SorExecution implements ExecutionModel {

    /**
     * @param liquidityShare fraction of the bar's volume dealable at this
     *                       venue's touch on each bar (e.g. 0.05)
     */
    public record VenueConfig(String name, double feeBps, double liquidityShare, boolean dark) {
    }

    private final List<VenueConfig> venues;
    private final Map<String, Double> feeByVenue = new HashMap<>();
    private final double halfSpreadBps;
    private final boolean preferDark;

    public SorExecution(List<VenueConfig> venues, double halfSpreadBps, boolean preferDark) {
        if (venues.isEmpty()) {
            throw new IllegalArgumentException("need at least one venue");
        }
        this.venues = List.copyOf(venues);
        this.halfSpreadBps = halfSpreadBps;
        this.preferDark = preferDark;
        for (VenueConfig v : venues) {
            feeByVenue.put(v.name(), v.feeBps());
        }
    }

    @Override
    public List<Execution> execute(Side side, long requestedQty, BarSeries series, int index) {
        if (requestedQty <= 0) {
            return List.of();
        }
        double mid = series.close(index);
        double bid = mid * (1 - halfSpreadBps / 1e4);
        double ask = mid * (1 + halfSpreadBps / 1e4);

        List<VenueQuote> quotes = new ArrayList<>(venues.size());
        for (VenueConfig v : venues) {
            long size = (long) (series.volume(index) * v.liquidityShare());
            if (size > 0) {
                quotes.add(new VenueQuote(v.name(), bid, size, ask, size, v.feeBps(), 0, v.dark()));
            }
        }
        if (quotes.isEmpty()) {
            return List.of();
        }

        SmartOrderRouter.RoutingPlan plan = SmartOrderRouter.route(side, requestedQty, quotes, preferDark);
        List<Execution> fills = new ArrayList<>(plan.legs().size());
        for (SmartOrderRouter.RouteLeg leg : plan.legs()) {
            double allIn = leg.price() * (1 + side.sign() * feeByVenue.get(leg.venue()) / 1e4);
            fills.add(new Execution(series.symbol(), side, allIn, leg.quantity(),
                    series.timestamp(index), leg.venue()));
        }
        return fills;
    }
}

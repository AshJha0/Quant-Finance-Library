package com.quantfinlib.data;

import com.quantfinlib.data.CorporateEvents.CashStockMerger;
import com.quantfinlib.data.CorporateEvents.Dividend;
import com.quantfinlib.data.CorporateEvents.Event;
import com.quantfinlib.data.CorporateEvents.ReverseSplit;
import com.quantfinlib.data.CorporateEvents.RightsIssue;
import com.quantfinlib.data.CorporateEvents.SpinOff;
import com.quantfinlib.data.CorporateEvents.StockDividend;
import com.quantfinlib.data.CorporateEvents.SymbolChange;

import java.util.List;
import java.util.Objects;

/**
 * Position-side treatment of {@link CorporateEvents}: what happens to a
 * holding's share count, cost basis and cash. This is a stand-alone adapter
 * and does not touch any ledger; a caller maps {@link Outcome} onto its own
 * position store.
 *
 * <p>Conventions: total cost basis is conserved except where cash leaves
 * the position (cash in lieu and merger cash reduce basis pro rata to the
 * value they replace, a simplification of tax rules that differ by
 * jurisdiction). Spin-off basis is split by relative market value on the
 * ex-date, {@code parentPrice : ratio * childPrice}.
 */
public final class CorporateEventPositions {

    /** One holding of an instrument. */
    public record Holding(String symbol, String isin, double shares, double costBasis) {
        public Holding {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(isin, "isin");
        }
    }

    /**
     * Result of applying an event: the resulting holdings (the original
     * instrument first, possibly with zero shares) and cash received.
     */
    public record Outcome(List<Holding> holdings, double cash) {
        public Outcome {
            holdings = List.copyOf(holdings);
        }
    }

    private CorporateEventPositions() {
    }

    /**
     * Applies an event that needs no election.
     *
     * @param exPrice the instrument's first ex-date price, used for cash in
     *                lieu of fractions and spin-off basis allocation
     */
    public static Outcome apply(Holding h, Event event, double exPrice) {
        return switch (event) {
            case StockDividend s -> new Outcome(List.of(
                    new Holding(h.symbol(), h.isin(), h.shares() * (1 + s.rate()), h.costBasis())), 0);
            case ReverseSplit r -> reverseSplit(h, r, exPrice);
            case Dividend d -> new Outcome(List.of(h), h.shares() * d.amount());
            case SpinOff s -> spinOff(h, s, exPrice);
            // rights are a separate tradable entitlement; the holding itself is unchanged
            case RightsIssue r -> new Outcome(List.of(h), 0);
            case SymbolChange c -> new Outcome(List.of(
                    new Holding(c.newSymbol(), c.newIsin(), h.shares(), h.costBasis())), 0);
            case CashStockMerger m -> merger(h, m, 0, 0);
        };
    }

    /** Takes up rights in full: pays {@code ratio * S} per share for {@code ratio} new shares. */
    public static Outcome takeUpRights(Holding h, RightsIssue r) {
        double cost = h.shares() * r.ratio() * r.subscriptionPrice();
        return new Outcome(List.of(new Holding(h.symbol(), h.isin(),
                h.shares() * (1 + r.ratio()), h.costBasis() + cost)), -cost);
    }

    private static Outcome reverseSplit(Holding h, ReverseSplit r, double exPrice) {
        double exact = h.shares() / r.oldPerNew();
        double whole = Math.floor(exact + 1e-9);
        double fraction = Math.max(0, exact - whole);
        double cash = fraction * exPrice;
        double basis = exact > 0 ? h.costBasis() * whole / exact : 0;
        return new Outcome(List.of(new Holding(h.symbol(), h.isin(), whole, basis)), cash);
    }

    private static Outcome spinOff(Holding h, SpinOff s, double parentExPrice) {
        double parentValue = parentExPrice;
        double childValue = s.ratio() * s.childPrice();
        double parentShare = parentValue / (parentValue + childValue);
        return new Outcome(List.of(
                new Holding(h.symbol(), h.isin(), h.shares(), h.costBasis() * parentShare),
                new Holding(s.childSymbol(), s.childIsin(), h.shares() * s.ratio(),
                        h.costBasis() * (1 - parentShare))), 0);
    }

    /**
     * Applies a cash-and-stock merger with election and proration.
     *
     * @param cashElectedFraction       fraction of this holding elected for cash (rest elects stock)
     * @param aggregateCashElectedFraction fraction of all target shares elected for cash; when it
     *                                  exceeds {@link CashStockMerger#maxCashFraction()} every
     *                                  cash elector receives cash on {@code max / aggregate} of
     *                                  its cash-elected shares and stock on the rest
     */
    public static Outcome merger(Holding h, CashStockMerger m, double cashElectedFraction,
                                 double aggregateCashElectedFraction) {
        if (cashElectedFraction < 0 || cashElectedFraction > 1
                || aggregateCashElectedFraction < 0 || aggregateCashElectedFraction > 1) {
            throw new IllegalArgumentException("election fractions must be in [0, 1]");
        }
        double proration = aggregateCashElectedFraction > m.maxCashFraction()
                ? m.maxCashFraction() / aggregateCashElectedFraction : 1.0;
        double cashShares = h.shares() * cashElectedFraction * proration;
        double stockShares = h.shares() - cashShares;
        double exactNew = stockShares * m.stockPerShare();
        double wholeNew = Math.floor(exactNew + 1e-9);
        double cashInLieu = Math.max(0, exactNew - wholeNew) * m.acquirerPrice();
        double cash = cashShares * m.cashPerShare() + cashInLieu;
        double stockValue = wholeNew * m.acquirerPrice();
        double total = stockValue + cash;
        double basis = total > 0 ? h.costBasis() * stockValue / total : 0;
        return new Outcome(List.of(
                new Holding(h.symbol(), h.isin(), 0, 0),
                new Holding(m.acquirerSymbol(), m.acquirerIsin(), wholeNew, basis)), cash);
    }
}

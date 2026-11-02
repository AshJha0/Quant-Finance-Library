package com.quantfinlib.data;

import com.quantfinlib.core.Bar;
import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Extended corporate actions and the back-adjustment factors they imply.
 *
 * <p>The plain idea: on an ex-date the share price drops (or jumps) for a
 * mechanical reason, not because the holder gained or lost anything. Every
 * bar before the ex-date is multiplied by one factor so that the adjusted
 * series moves exactly like a holder's wealth would, assuming anything
 * paid out is reinvested in the same stock at the theoretical ex price.
 * {@link CorporateActions} keeps handling the 2.1 split and cash-dividend
 * cases; this class adds the rest.
 *
 * <p>Formally, with {@code P} the last close before the ex-date, the price
 * factor {@code f} applied to all earlier bars is:
 * <ul>
 *   <li>{@link StockDividend} (bonus issue, rate {@code b} new per old):
 *       {@code f = 1 / (1 + b)}, volume times {@code (1 + b)}.</li>
 *   <li>{@link ReverseSplit} ({@code k} old shares become 1):
 *       {@code f = k}, volume divided by {@code k}.</li>
 *   <li>{@link Dividend} (regular or special, amount {@code d}):
 *       {@code f = (P - d) / P}. Which kinds adjust is set by
 *       {@link DividendPolicy}.</li>
 *   <li>{@link SpinOff} ({@code r} child shares per parent share, child
 *       reference price {@code C}): {@code f = (P - rC) / P}.</li>
 *   <li>{@link RightsIssue} ({@code r} new shares per old at subscription
 *       price {@code S}): the theoretical ex-rights price is
 *       {@code TERP = (P + rS) / (1 + r)} and {@code f = TERP / P}.</li>
 *   <li>{@link SymbolChange}: {@code f = 1}; the output series takes the
 *       latest symbol.</li>
 *   <li>{@link CashStockMerger}: terminal for the target. No factor; the
 *       position side is in {@link CorporateEventPositions}.</li>
 * </ul>
 *
 * <p>Worked example (own test, {@code CorporateEventsTest}): a rights issue
 * of 1 new for 4 held at 80 with the stock at 100 gives
 * {@code TERP = (100 + 0.25 * 80) / 1.25 = 96}, so {@code f = 0.96} and a
 * cum-rights close of 100 becomes 96 on the adjusted tape.
 *
 * <p>{@link #totalReturnError} checks the factors against an independent
 * share-count simulation of a reinvesting holder and returns the largest
 * per-bar gap between the two return series.
 */
public final class CorporateEvents {

    /** Which dividends enter the back-adjustment. */
    public enum DividendPolicy {
        /** Regular and special dividends both adjust (CRSP-style total return). */
        ALL,
        /** Only special dividends adjust; regular ones stay in the price series. */
        SPECIAL_ONLY,
        /** No dividend adjusts (price-return series). */
        NONE
    }

    /** Regular or special cash dividend. */
    public enum DividendKind { REGULAR, SPECIAL }

    /** A corporate event effective from its ex timestamp. */
    public sealed interface Event permits StockDividend, ReverseSplit, Dividend, SpinOff,
            RightsIssue, SymbolChange, CashStockMerger {
        /** First bar timestamp trading on the new basis. */
        long exTimestamp();
    }

    /** Stock dividend or bonus issue: {@code rate} new shares per share held (0.05 = 5 %). */
    public record StockDividend(long exTimestamp, double rate) implements Event {
        public StockDividend {
            positive(rate, "rate");
        }
    }

    /** Reverse split: {@code oldPerNew} old shares are consolidated into one. */
    public record ReverseSplit(long exTimestamp, double oldPerNew) implements Event {
        public ReverseSplit {
            if (!(oldPerNew > 1)) {
                throw new IllegalArgumentException("reverse split ratio must exceed 1");
            }
        }
    }

    /** Cash dividend tagged regular or special. */
    public record Dividend(long exTimestamp, double amount, DividendKind kind) implements Event {
        public Dividend {
            positive(amount, "amount");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * Spin-off of a new instrument.
     *
     * @param childSymbol    symbol of the new instrument
     * @param childIsin      ISIN of the new instrument
     * @param ratio          child shares distributed per parent share
     * @param childPrice     reference price of the child (first regular-way close)
     */
    public record SpinOff(long exTimestamp, String childSymbol, String childIsin,
                          double ratio, double childPrice) implements Event {
        public SpinOff {
            Objects.requireNonNull(childSymbol, "childSymbol");
            Objects.requireNonNull(childIsin, "childIsin");
            positive(ratio, "ratio");
            positive(childPrice, "childPrice");
        }
    }

    /** Rights issue: {@code ratio} new shares per old share at {@code subscriptionPrice}. */
    public record RightsIssue(long exTimestamp, double ratio, double subscriptionPrice)
            implements Event {
        public RightsIssue {
            positive(ratio, "ratio");
            positive(subscriptionPrice, "subscriptionPrice");
        }
    }

    /** Symbol and/or ISIN change; no economic effect. */
    public record SymbolChange(long exTimestamp, String newSymbol, String newIsin) implements Event {
        public SymbolChange {
            Objects.requireNonNull(newSymbol, "newSymbol");
            Objects.requireNonNull(newIsin, "newIsin");
        }
    }

    /**
     * Cash-and-stock merger with holder election and proration.
     *
     * @param acquirerSymbol      acquirer symbol
     * @param acquirerIsin        acquirer ISIN
     * @param cashPerShare        cash paid per target share under a cash election
     * @param stockPerShare       acquirer shares per target share under a stock election
     * @param maxCashFraction     fraction of all target shares the deal will pay in cash;
     *                            if cash elections exceed it, cash electors are prorated
     * @param acquirerPrice       acquirer price used for cash in lieu of fractions
     */
    public record CashStockMerger(long exTimestamp, String acquirerSymbol, String acquirerIsin,
                                  double cashPerShare, double stockPerShare,
                                  double maxCashFraction, double acquirerPrice) implements Event {
        public CashStockMerger {
            Objects.requireNonNull(acquirerSymbol, "acquirerSymbol");
            Objects.requireNonNull(acquirerIsin, "acquirerIsin");
            if (cashPerShare < 0 || stockPerShare < 0 || cashPerShare + stockPerShare <= 0) {
                throw new IllegalArgumentException("merger consideration must be positive");
            }
            if (maxCashFraction < 0 || maxCashFraction > 1) {
                throw new IllegalArgumentException("maxCashFraction must be in [0, 1]");
            }
            positive(acquirerPrice, "acquirerPrice");
        }
    }

    private CorporateEvents() {
    }

    private static void positive(double v, String name) {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }

    /**
     * Price factor for one event given the last cum close {@code prevClose};
     * returns 1 for events that do not adjust under {@code policy}.
     */
    public static double priceFactor(Event event, double prevClose, DividendPolicy policy) {
        return switch (event) {
            case StockDividend s -> 1 / (1 + s.rate());
            case ReverseSplit r -> r.oldPerNew();
            case Dividend d -> {
                boolean adjusts = policy == DividendPolicy.ALL
                        || (policy == DividendPolicy.SPECIAL_ONLY && d.kind() == DividendKind.SPECIAL);
                yield adjusts ? distributionFactor(d.amount(), prevClose) : 1.0;
            }
            case SpinOff s -> distributionFactor(s.ratio() * s.childPrice(), prevClose);
            case RightsIssue r -> terp(prevClose, r) / prevClose;
            case SymbolChange c -> 1.0;
            case CashStockMerger m -> 1.0;
        };
    }

    /** Volume factor applied to bars before the ex-date (share-count changes only). */
    public static double volumeFactor(Event event) {
        return switch (event) {
            case StockDividend s -> 1 + s.rate();
            case ReverseSplit r -> 1 / r.oldPerNew();
            default -> 1.0;
        };
    }

    /** Theoretical ex-rights price {@code (P + rS) / (1 + r)}. */
    public static double terp(double cumPrice, RightsIssue rights) {
        return (cumPrice + rights.ratio() * rights.subscriptionPrice()) / (1 + rights.ratio());
    }

    private static double distributionFactor(double value, double prevClose) {
        if (value >= prevClose) {
            throw new IllegalArgumentException("distribution " + value + " >= prior close " + prevClose);
        }
        return (prevClose - value) / prevClose;
    }

    /** Back-adjusts with {@link DividendPolicy#ALL}. */
    public static BarSeries adjust(BarSeries series, List<? extends Event> events) {
        return adjust(series, events, DividendPolicy.ALL);
    }

    /**
     * Returns a new back-adjusted series; the input is untouched. Factors
     * compound from the latest event backwards, prevClose is always the raw
     * close, and the result takes the last {@link SymbolChange} symbol.
     */
    public static BarSeries adjust(BarSeries series, List<? extends Event> events, DividendPolicy policy) {
        int n = series.size();
        double[] pf = new double[n];
        double[] vf = new double[n];
        java.util.Arrays.fill(pf, 1.0);
        java.util.Arrays.fill(vf, 1.0);
        String symbol = series.symbol();
        for (Event e : sorted(events)) {
            if (e instanceof SymbolChange c) {
                symbol = c.newSymbol();
            }
            int ex = firstIndexAtOrAfter(series, e.exTimestamp());
            if (ex <= 0 || ex >= n && e instanceof CashStockMerger) {
                continue;
            }
            double f = priceFactor(e, series.close(ex - 1), policy);
            double v = volumeFactor(e);
            for (int i = 0; i < ex; i++) {
                pf[i] *= f;
                vf[i] *= v;
            }
        }
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < n; i++) {
            Bar bar = series.bar(i);
            b.add(bar.timestamp(), bar.open() * pf[i], bar.high() * pf[i], bar.low() * pf[i],
                    bar.close() * pf[i], bar.volume() * vf[i]);
        }
        return b.build();
    }

    /**
     * Total-return consistency check. Simulates a holder of one share who
     * reinvests every payout (dividends, spin-off shares sold at the child
     * price, rights sold at their theoretical value) at the theoretical ex
     * price, and compares that wealth path's close-to-close returns with the
     * adjusted series. Returns the largest absolute return gap; with
     * {@link DividendPolicy#ALL} it should be at rounding level.
     */
    public static double totalReturnError(BarSeries raw, BarSeries adjusted, List<? extends Event> events) {
        if (raw.size() != adjusted.size()) {
            throw new IllegalArgumentException("series lengths differ");
        }
        List<Event> evs = sorted(events);
        double shares = 1;
        int k = 0;
        double maxErr = 0;
        while (k < evs.size() && evs.get(k).exTimestamp() <= raw.timestamp(0)) {
            k++;   // effective before the first bar: nothing to compare
        }
        for (int i = 1; i < raw.size(); i++) {
            double prev = raw.close(i - 1);
            double wealthBefore = shares * prev;
            while (k < evs.size() && evs.get(k).exTimestamp() <= raw.timestamp(i)) {
                Event e = evs.get(k++);
                shares = switch (e) {
                    case StockDividend s -> shares * (1 + s.rate());
                    case ReverseSplit r -> shares / r.oldPerNew();
                    // cash paid, reinvested at the cum close less the cash
                    case Dividend d -> shares * prev / (prev - d.amount());
                    case SpinOff s -> shares * prev / (prev - s.ratio() * s.childPrice());
                    // rights worth P - TERP sold, proceeds reinvested at TERP
                    case RightsIssue r -> shares * prev / terp(prev, r);
                    case SymbolChange c -> shares;
                    case CashStockMerger m -> shares;
                };
            }
            double wealthAfter = shares * raw.close(i);
            double trReturn = wealthAfter / wealthBefore - 1;
            double adjReturn = adjusted.close(i) / adjusted.close(i - 1) - 1;
            maxErr = Math.max(maxErr, Math.abs(trReturn - adjReturn));
        }
        return maxErr;
    }

    private static List<Event> sorted(List<? extends Event> events) {
        List<Event> s = new ArrayList<>(events);
        s.sort(Comparator.comparingLong(Event::exTimestamp));
        return s;
    }

    private static int firstIndexAtOrAfter(BarSeries series, long timestamp) {
        for (int i = 0; i < series.size(); i++) {
            if (series.timestamp(i) >= timestamp) {
                return i;
            }
        }
        return series.size();
    }
}

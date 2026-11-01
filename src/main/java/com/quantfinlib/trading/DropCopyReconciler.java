package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares the fills this system booked with the venue's or the broker's
 * drop copy, the independent record of the same executions, and lists every
 * difference as a break.
 *
 * <p>Fills are matched on execution id. For each id the reconciler reports:</p>
 * <ul>
 *   <li>{@link BreakType#MISSING}: in the drop copy, not booked here (a fill
 *       this system never saw: its position is wrong);</li>
 *   <li>{@link BreakType#EXTRA}: booked here, not in the drop copy (a fill
 *       the venue does not know about);</li>
 *   <li>{@link BreakType#MISMATCH}: in both, but symbol, side or quantity
 *       differ, or the prices differ by more than the tolerance;</li>
 *   <li>{@link BreakType#DUPLICATE}: an id that appears more than once on
 *       either side (a fill applied twice, or a replayed drop-copy record).</li>
 * </ul>
 * <p>Account and strategy are this system's allocation and are not compared.
 * The report also gives the net quantity difference per symbol,
 * {@code sum(drop copy) - sum(own)} with buys positive, which is the
 * position correction the breaks imply.</p>
 *
 * <p>Not on the hot path: runs on a snapshot, allocates freely.</p>
 */
public final class DropCopyReconciler {

    private DropCopyReconciler() {
    }

    /** One execution, as booked or as reported by the drop copy. */
    public record Fill(long execId, int accountId, int strategyId, int symbolId, Side side,
                       long quantity, double price) {
        public Fill {
            Objects.requireNonNull(side, "side");
            if (quantity <= 0 || !Double.isFinite(price)) {
                throw new IllegalArgumentException("fill needs quantity > 0 and a finite price");
            }
        }

        /** A drop-copy record, which carries no account or strategy allocation. */
        public static Fill dropCopy(long execId, int symbolId, Side side, long quantity, double price) {
            return new Fill(execId, -1, -1, symbolId, side, quantity, price);
        }

        long signedQuantity() {
            return side.sign() * quantity;
        }
    }

    /** Kind of difference. */
    public enum BreakType { MISSING, EXTRA, MISMATCH, DUPLICATE }

    /**
     * One break. {@code own} or {@code dropCopy} is null when that side has no
     * record of the execution.
     */
    public record Break(BreakType type, long execId, Fill own, Fill dropCopy, String detail) {
    }

    /** Result of one reconciliation. */
    public static final class Report {
        private final List<Break> breaks;
        private final Map<Integer, Long> quantityDifference;
        private final int matched;
        private final boolean ownLogComplete;

        Report(List<Break> breaks, Map<Integer, Long> quantityDifference, int matched,
               boolean ownLogComplete) {
            this.breaks = List.copyOf(breaks);
            this.quantityDifference = Collections.unmodifiableMap(new LinkedHashMap<>(quantityDifference));
            this.matched = matched;
            this.ownLogComplete = ownLogComplete;
        }

        /** Every break, in the order: own fills by log order, then drop-copy-only fills. */
        public List<Break> breaks() {
            return breaks;
        }

        /** Breaks of one type. */
        public List<Break> breaks(BreakType type) {
            return breaks.stream().filter(b -> b.type() == type).toList();
        }

        /** Executions present on both sides with identical economics. */
        public int matched() {
            return matched;
        }

        public boolean isClean() {
            return breaks.isEmpty() && ownLogComplete;
        }

        /** False when the ledger's fill log overflowed: some own fills could not be compared. */
        public boolean ownLogComplete() {
            return ownLogComplete;
        }

        /**
         * Drop copy minus own signed quantity per symbol id, only for symbols
         * where it is non-zero.
         */
        public Map<Integer, Long> quantityDifference() {
            return quantityDifference;
        }

        /** Plain-text break report, one line per break. */
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("matched=").append(matched).append(" breaks=").append(breaks.size());
            if (!ownLogComplete) {
                sb.append(" (own fill log incomplete)");
            }
            sb.append('\n');
            for (Break b : breaks) {
                sb.append(b.type()).append(" execId=").append(b.execId()).append(' ')
                        .append(b.detail()).append('\n');
            }
            quantityDifference.forEach((s, d) ->
                    sb.append("symbol ").append(s).append(" position difference ").append(d).append('\n'));
            return sb.toString();
        }
    }

    /** Reconciles the ledger's fill log against a drop copy. */
    public static Report reconcile(PositionLedger ledger, List<Fill> dropCopy, double priceTolerance) {
        List<Fill> own = new ArrayList<>(ledger.loggedFills());
        for (int i = 0; i < ledger.loggedFills(); i++) {
            own.add(ledger.loggedFill(i));
        }
        return reconcile(own, dropCopy, priceTolerance, ledger.fillLogOverflow() == 0);
    }

    /** Reconciles two fill lists; {@code priceTolerance} is an absolute price difference. */
    public static Report reconcile(List<Fill> own, List<Fill> dropCopy, double priceTolerance) {
        return reconcile(own, dropCopy, priceTolerance, true);
    }

    private static Report reconcile(List<Fill> own, List<Fill> dropCopy, double priceTolerance,
                                    boolean ownComplete) {
        if (!(priceTolerance >= 0) || !Double.isFinite(priceTolerance)) {
            throw new IllegalArgumentException("priceTolerance must be finite and >= 0");
        }
        List<Break> breaks = new ArrayList<>();
        Map<Long, Fill> theirs = new LinkedHashMap<>();
        for (Fill f : dropCopy) {
            if (theirs.putIfAbsent(f.execId(), f) != null) {
                breaks.add(new Break(BreakType.DUPLICATE, f.execId(), null, f,
                        "repeated in drop copy"));
            }
        }
        Map<Long, Fill> ours = new LinkedHashMap<>();
        Map<Integer, Long> diff = new java.util.TreeMap<>();
        int matched = 0;
        for (Fill f : own) {
            diff.merge(f.symbolId(), -f.signedQuantity(), Long::sum);
            if (ours.putIfAbsent(f.execId(), f) != null) {
                breaks.add(new Break(BreakType.DUPLICATE, f.execId(), f, null, "booked twice"));
                continue;
            }
            Fill d = theirs.get(f.execId());
            if (d == null) {
                breaks.add(new Break(BreakType.EXTRA, f.execId(), f, null,
                        "booked " + describe(f) + ", absent from drop copy"));
                continue;
            }
            String why = mismatch(f, d, priceTolerance);
            if (why == null) {
                matched++;
            } else {
                breaks.add(new Break(BreakType.MISMATCH, f.execId(), f, d, why));
            }
        }
        for (Fill d : theirs.values()) {
            diff.merge(d.symbolId(), d.signedQuantity(), Long::sum);
            if (!ours.containsKey(d.execId())) {
                breaks.add(new Break(BreakType.MISSING, d.execId(), null, d,
                        "drop copy " + describe(d) + ", not booked"));
            }
        }
        diff.values().removeIf(v -> v == 0L);
        return new Report(breaks, diff, matched, ownComplete);
    }

    private static String mismatch(Fill own, Fill dc, double tol) {
        StringBuilder sb = new StringBuilder();
        if (own.symbolId() != dc.symbolId()) {
            sb.append("symbol ").append(own.symbolId()).append(" vs ").append(dc.symbolId()).append("; ");
        }
        if (own.side() != dc.side()) {
            sb.append("side ").append(own.side()).append(" vs ").append(dc.side()).append("; ");
        }
        if (own.quantity() != dc.quantity()) {
            sb.append("qty ").append(own.quantity()).append(" vs ").append(dc.quantity()).append("; ");
        }
        if (Math.abs(own.price() - dc.price()) > tol) {
            sb.append("price ").append(own.price()).append(" vs ").append(dc.price()).append("; ");
        }
        return sb.isEmpty() ? null : sb.substring(0, sb.length() - 2);
    }

    private static String describe(Fill f) {
        return f.side() + " " + f.quantity() + " of symbol " + f.symbolId() + " at " + f.price();
    }
}

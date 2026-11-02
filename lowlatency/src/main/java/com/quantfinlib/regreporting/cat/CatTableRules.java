package com.quantfinlib.regreporting.cat;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Field rules of the option event types added in 3.2.0 (rules CAT-R22 and CAT-R23 of
 * {@link CatEvent}), one row per type with the seq numbers of the fields in its table of CAT
 * Reporting Technical Specifications for Industry Members v4.2.0 r4 (since 4.0.0; v4.1.0 r14 for
 * the quote types of {@link #V41_ONLY}). A seq of 0 means the
 * table has no such rule. The rows were transcribed from the price, initiator, senderType and
 * priceType descriptions of each table (pdftotext -raw; the scripts are in scripts/cat-transcription, gen2.py and
 * genjava.py). Package-private.
 */
final class CatTableRules {
    /**
     * One row.
     *
     * @param table the table number
     * @param priceNonNegative seq of a price whose description says "must be greater than or
     *     equal to zero"
     * @param priceLmt seq of a price "Required when orderType is LMT"
     * @param priceMkt seq of a price that "Must be blank when orderType is MKT"
     * @param initiator seq of initiator (Appendix G: C or F)
     * @param senderType seq of senderType
     * @param quoteSender whether senderType takes the quote-event values (F, N, S) rather than
     *     the order-event values (E, F, O), Appendix G senderType
     * @param priceType seq of priceType (Appendix G: PU, TC or TS; required with LMT)
     */
    record Row(int table, int priceNonNegative, int priceLmt, int priceMkt, int initiator, int senderType,
            boolean quoteSender, int priceType) {
    }

    static final Set<String> ORDER_SENDERS = Set.of("E", "F", "O");
    static final Set<String> QUOTE_SENDERS = Set.of("F", "N", "S");
    private static final Set<String> INITIATORS = Set.of("C", "F");
    private static final Set<String> PRICE_TYPES = Set.of("PU", "TC", "TS");

    /**
     * The option and multi-leg quote types that v4.2.0 no longer defines; their rows keep the
     * v4.1.0 r14 table and seq numbers (deprecated for removal in 4.0.0).
     */
    @SuppressWarnings("removal")
    static final Set<CatEventType> V41_ONLY = Collections.unmodifiableSet(java.util.EnumSet.of(CatEventType.MONQ,
            CatEventType.MORQ, CatEventType.MOQR, CatEventType.MOQC, CatEventType.MOQM, CatEventType.MLNQ,
            CatEventType.MLRQ, CatEventType.MLQS, CatEventType.MLQR, CatEventType.MLQC, CatEventType.MLQM));

    static final Map<CatEventType, Row> ROWS = rows();

    @SuppressWarnings("removal")
    private static Map<CatEventType, Row> rows() {
        Map<CatEventType, Row> m = new EnumMap<>(CatEventType.class);
        for (Map.Entry<CatEventType, Row> e : List.of(
            row(CatEventType.MOMR, 64, 20, 20, 20, 0, 0, false, 0),
            row(CatEventType.MOCR, 65, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOORS, 66, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOMRS, 67, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOCRS, 68, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOIR, 70, 18, 18, 18, 0, 0, false, 0),
            row(CatEventType.MOIM, 71, 19, 19, 19, 17, 0, false, 0),
            row(CatEventType.MOIC, 72, 0, 0, 0, 15, 0, false, 0),
            row(CatEventType.MOIMR, 73, 16, 16, 16, 0, 0, false, 0),
            row(CatEventType.MOICR, 74, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOCO, 75, 14, 14, 14, 0, 0, false, 0),
            row(CatEventType.MOCOM, 76, 14, 14, 14, 0, 0, false, 0),
            row(CatEventType.MOCOC, 77, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOOMS, 79, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOOMR, 80, 17, 17, 17, 0, 15, false, 0),
            row(CatEventType.MOOJ, 81, 16, 0, 0, 15, 0, false, 0),
            row(CatEventType.MOOCR, 83, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOOFS, 88, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOFA, 90, 17, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOPA, 91, 13, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOAA, 92, 13, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOOE, 93, 17, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLMR, 98, 0, 20, 20, 0, 0, false, 33),
            row(CatEventType.MLCR, 100, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLOA, 101, 0, 19, 19, 0, 17, false, 30),
            row(CatEventType.MLIR, 103, 0, 16, 16, 0, 0, false, 24),
            row(CatEventType.MLIM, 105, 0, 19, 19, 17, 0, false, 29),
            row(CatEventType.MLIC, 107, 0, 0, 0, 15, 0, false, 0),
            row(CatEventType.MLIMR, 108, 0, 16, 16, 0, 0, false, 25),
            row(CatEventType.MLICR, 110, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLCO, 111, 0, 12, 12, 0, 0, false, 20),
            row(CatEventType.MLCOM, 113, 0, 14, 14, 0, 0, false, 23),
            row(CatEventType.MLCOC, 115, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLOM, 116, 0, 22, 22, 21, 19, false, 35),
            row(CatEventType.MLOMR, 118, 0, 17, 17, 0, 15, false, 24),
            row(CatEventType.MLOCR, 121, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLOE, 124, 0, 12, 12, 0, 0, false, 19),
            // option and multi-leg quote events: not in v4.2.0; seq numbers of v4.1.0 r14 (deprecated in 4.0.0)
            row(CatEventType.MORQ, 86, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOQR, 87, 0, 0, 0, 0, 23, true, 0),
            row(CatEventType.MOQC, 88, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MOQM, 89, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLNQ, 128, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLRQ, 130, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLQS, 132, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLQR, 134, 0, 0, 0, 0, 23, true, 0),
            row(CatEventType.MLQC, 136, 0, 0, 0, 0, 0, false, 0),
            row(CatEventType.MLQM, 137, 0, 0, 0, 0, 0, false, 0))) {
            m.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(m);
    }

    private CatTableRules() {
    }

    private static Map.Entry<CatEventType, Row> row(CatEventType t, int table, int nonNeg, int lmt, int mkt,
            int initiator, int sender, boolean quoteSender, int priceType) {
        return Map.entry(t, new Row(table, nonNeg, lmt, mkt, initiator, sender, quoteSender, priceType));
    }

    /**
     * Applies CAT-R22 (price) and CAT-R23 (choice values) for a type added in 3.2.0; does
     * nothing for the other types.
     */
    static void check(CatEventType t, UnaryOperator<String> v, List<String> out) {
        Row r = ROWS.get(t);
        if (r == null) {
            return;
        }
        String px = v.apply("price");
        String ot = v.apply("orderType");
        String at = (V41_ONLY.contains(t) ? " (v4.1.0 r14 Table " : " (Table ") + r.table() + " seq ";
        if (r.priceNonNegative() > 0 && px.startsWith("-")) {
            out.add("price must be greater than or equal to zero" + at + r.priceNonNegative() + ")");
        }
        if (r.priceLmt() > 0 && "LMT".equals(ot) && px.isEmpty()) {
            out.add("price required when orderType is LMT" + at + r.priceLmt() + ")");
        }
        if (r.priceMkt() > 0 && "MKT".equals(ot) && !px.isEmpty()) {
            out.add("price must be blank when orderType is MKT" + at + r.priceMkt() + ")");
        }
        choice(v, "initiator", r.initiator(), INITIATORS, at, out);
        choice(v, "senderType", r.senderType(), r.quoteSender() ? QUOTE_SENDERS : ORDER_SENDERS, at, out);
        choice(v, "priceType", r.priceType(), PRICE_TYPES, at, out);
        if (r.priceType() > 0 && "LMT".equals(ot) && v.apply("priceType").isEmpty()) {
            out.add("priceType required when orderType is LMT" + at + r.priceType() + ")");
        }
    }

    private static void choice(UnaryOperator<String> v, String field, int seq, Set<String> allowed, String at,
            List<String> out) {
        if (seq == 0) {
            return;
        }
        String x = v.apply(field);
        if (!x.isEmpty() && !allowed.contains(x)) {
            out.add(field + " must be one of " + new java.util.TreeSet<>(allowed) + at + seq + ")");
        }
    }
}

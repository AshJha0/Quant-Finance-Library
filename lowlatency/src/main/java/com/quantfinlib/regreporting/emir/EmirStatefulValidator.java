package com.quantfinlib.regreporting.emir;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import com.quantfinlib.regreporting.registry.LeiRegistry;
import com.quantfinlib.regreporting.registry.LeiStatus;
import com.quantfinlib.regreporting.registry.LeiValidity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The EMIR Refit validation rules that need state beyond the report itself: earlier reports
 * for the same UTI, the trade repository's receipt time, GLEIF status and the official
 * ISO 4217 list. Opt-in and additive: {@link EmirRefitReport#validate()} is unchanged, and
 * this class runs it first and then adds the stateful checks for whichever sources it was
 * given. A source left out means its rules are skipped, never failed.
 *
 * <p><b>Plain idea.</b> A trade repository rejects a second "new" report for a trade it
 * already holds, a report stamped after it arrived, and a report naming a counterparty
 * whose LEI has lapsed. None of these can be seen from the report alone; each needs the
 * repository's memory, its clock or a reference file.</p>
 *
 * <p><b>Formal statement.</b> The rules, codes and sheet rows are those of ESMA74-362-2683,
 * sheet {@code Validations_Cpty_derivative}, columns AC (conditions) and AD (error codes);
 * {@link #rules()} lists each with its evidence class. LEI status is read with
 * {@link LeiRegistry#validityOn(String, LocalDate)} on the date part of field 1.1, as the
 * workbook says ("Correct status of the LEI should be verified as of the date reported in
 * the field 1.1"). For fields 1.3, 1.4 and 1.9 the status check is skipped when 2.153 is
 * earlier than the day before the date of 1.1 (same rows). The branch test (EMIR-VR-1002-04
 * and similar) is not applied: {@link com.quantfinlib.regreporting.registry.LeiRecord}
 * does not carry the GLEIF entity category.</p>
 *
 * <p><b>Worked example</b> (sheet row 5, own test): reporting timestamp
 * 2026-03-02T10:00:00Z and receipt 2026-03-02T09:59:59Z fail EMIR-VR-1001-02 (stamped
 * after receipt); the same timestamp received 2026-03-04T00:00:00Z fails EMIR-VR-1001-03,
 * because 2026-03-02 is earlier than the day before 2026-03-04.</p>
 *
 * @since 3.1.0
 */
public final class EmirStatefulValidator {
    private static final String WB = "ESMA74-362-2683, sheet Validations_Cpty_derivative, ";

    private static final Set<LeiStatus> ISSUED_LAPSED = EnumSet.of(LeiStatus.ISSUED, LeiStatus.LAPSED,
            LeiStatus.PENDING_TRANSFER, LeiStatus.PENDING_ARCHIVAL);
    private static final Set<LeiStatus> ISSUED_ONLY = EnumSet.of(LeiStatus.ISSUED, LeiStatus.PENDING_TRANSFER,
            LeiStatus.PENDING_ARCHIVAL);
    private static final Set<EmirActionType> OPENING = EnumSet.of(EmirActionType.NEWT, EmirActionType.POSC);
    private static final Set<EmirActionType> NON_OUTSTANDING_ALLOWED = EnumSet.of(EmirActionType.EROR,
            EmirActionType.REVI, EmirActionType.MODI, EmirActionType.CORR, EmirActionType.VALU);

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("EMIR-VR-1001-02", "1.1 is equal to or earlier than the TR receipt time",
                    WB + "row 5 (field 1.1)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1001-03", "the date of 1.1 is not earlier than the day before the "
                    + "receipt date", WB + "row 5 (field 1.1)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1002-01..03", "1.2 is in GLEIF; status Issued, Lapsed, Pending transfer "
                    + "or Pending archival except for TERM and EROR", WB + "row 6 (field 1.2)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1003-02..04", "1.3, when given, is in GLEIF; status Issued, Pending "
                    + "transfer or Pending archival except for TERM, REVI and EROR", WB + "row 7 (field 1.3)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1004-01..03", "1.4 is in GLEIF; status Issued, Pending transfer or "
                    + "Pending archival except for TERM, REVI and EROR", WB + "row 8 (field 1.4)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1009-01..03", "with 1.8 true, 1.9 is in GLEIF; status Issued, Lapsed, "
                    + "Pending transfer or Pending archival except for TERM, REVI and EROR",
                    WB + "row 13 (field 1.9)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1004-05", "1.4 is unchanged since the first accepted report",
                    WB + "row 8 (field 1.4, condition 5)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1009-09", "1.9 is unchanged since the first accepted report",
                    WB + "row 13 (field 1.9, condition 9 in column AD; column AC prints it as '9 8.')",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-VR-2151-02", "the first accepted report for the Counterparty ID-UTI pair is "
                    + "NEWT or POSC", WB + "row 184 (field 2.151)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2151-03", "only one NEWT or POSC per Counterparty ID-UTI pair",
                    WB + "row 184 (field 2.151)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2151-04", "after EROR only REVI, until a REVI is accepted",
                    WB + "row 184 (field 2.151)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2151-05", "a non-outstanding derivative takes only EROR, REVI, MODI, CORR "
                    + "or VALU; non-outstanding means the last of TERM/REVI was TERM, or the latest 2.44/2.45 "
                    + "in the history is before the date of 1.1", WB + "row 184 (field 2.151); the meaning of "
                    + "'non-outstanding' is the library's reading", Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-VR-2151-06", "an outstanding derivative does not take REVI",
                    WB + "row 184 (field 2.151); 'outstanding' as in EMIR-VR-2151-05", Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-VR-2056-01", "2.56 is on the official ISO 4217 list",
                    WB + "row 82 (field 2.56)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2022-01", "2.22, when given, is on the official ISO 4217 list",
                    WB + "row 47 (field 2.22)", Evidence.PUBLISHED_REFERENCE));

    private final @Nullable UtiHistory history;
    private final @Nullable LeiRegistry leis;
    private final @Nullable CurrencyRegistry currencies;

    private EmirStatefulValidator(@Nullable UtiHistory history, @Nullable LeiRegistry leis,
            @Nullable CurrencyRegistry currencies) {
        this.history = history;
        this.leis = leis;
        this.currencies = currencies;
    }

    /**
     * A validator with no state sources; add them with the {@code with...} methods.
     *
     * @return the validator
     */
    public static EmirStatefulValidator create() {
        return new EmirStatefulValidator(null, null, null);
    }

    /**
     * A copy that also applies the action-history rules.
     *
     * @param h earlier accepted reports
     * @return a new validator
     */
    public EmirStatefulValidator withHistory(UtiHistory h) {
        return new EmirStatefulValidator(h, leis, currencies);
    }

    /**
     * A copy that also applies the GLEIF status rules.
     *
     * @param r the LEI registry
     * @return a new validator
     */
    public EmirStatefulValidator withLeiRegistry(LeiRegistry r) {
        return new EmirStatefulValidator(history, r, currencies);
    }

    /**
     * A copy that also checks currencies against the official ISO 4217 list.
     *
     * @param c the currency list
     * @return a new validator
     */
    public EmirStatefulValidator withCurrencies(CurrencyRegistry c) {
        return new EmirStatefulValidator(history, leis, c);
    }

    /**
     * Every stateful rule this class can apply, with citation and evidence class.
     *
     * @return the rule catalogue (immutable)
     */
    public static List<RuleProvenance> rules() {
        return RULES;
    }

    /**
     * Validates a report without a receipt time: the single-report rules of
     * {@link EmirRefitReport#validate()}, then the stateful rules for the sources configured;
     * EMIR-VR-1001-02/03 are skipped.
     *
     * @param report the report
     * @return error messages, each starting with its ESMA error code; empty when valid
     */
    public List<String> validate(EmirRefitReport report) {
        return check(report, null);
    }

    /**
     * Validates a report as received by a trade repository at a given time: as
     * {@link #validate(EmirRefitReport)} plus EMIR-VR-1001-02 and -03.
     *
     * @param report the report
     * @param receivedByTr when the report entered the trade repository's system
     * @return error messages, each starting with its ESMA error code; empty when valid
     */
    public List<String> validate(EmirRefitReport report, Instant receivedByTr) {
        return check(report, java.util.Objects.requireNonNull(receivedByTr, "receivedByTr"));
    }

    private List<String> check(EmirRefitReport report, @Nullable Instant receivedByTr) {
        List<String> out = new ArrayList<>(report.validate());
        java.util.Map<String, String> f = report.fields();
        @Nullable EmirActionType action = action(f.getOrDefault("actionType", ""));
        @Nullable Instant reported = instant(f.getOrDefault("reportingTimestamp", ""));
        if (receivedByTr != null && reported != null) {
            if (reported.isAfter(receivedByTr)) {
                out.add("EMIR-VR-1001-02: field 1.1 " + reported + " is after the TR receipt time " + receivedByTr);
            }
            LocalDate rd = receivedByTr.atZone(ZoneOffset.UTC).toLocalDate();
            if (reported.atZone(ZoneOffset.UTC).toLocalDate().isBefore(rd.minusDays(1))) {
                out.add("EMIR-VR-1001-03: the date of field 1.1 is earlier than the day before receipt " + rd);
            }
        }
        if (currencies != null) {
            currency(f.getOrDefault("notionalCurrency1", ""), "EMIR-VR-2056-01", "2.56", out);
            currency(f.getOrDefault("valuationCurrency", ""), "EMIR-VR-2022-01", "2.22", out);
        }
        if (leis != null && action != null && reported != null) {
            checkLeis(f, action, reported.atZone(ZoneOffset.UTC).toLocalDate(), out);
        }
        if (history != null && action != null) {
            checkHistory(f, action, reported, report.reference(), out);
        }
        return out;
    }

    private void currency(String code, String rule, String field, List<String> out) {
        if (!code.isEmpty() && currencies != null && !currencies.isListed(code)) {
            out.add(rule + ": field " + field + " " + code + " is not on the official ISO 4217 list");
        }
    }

    private void checkLeis(java.util.Map<String, String> f, EmirActionType action, LocalDate onDate,
            List<String> out) {
        boolean anyStatus12 = action == EmirActionType.TERM || action == EmirActionType.EROR;
        boolean anyStatus = anyStatus12 || action == EmirActionType.REVI;
        boolean skipStatus = skipStatus(f.getOrDefault("eventDate", ""), onDate);
        lei(f.getOrDefault("reportSubmittingEntityId", ""), "1.2", "EMIR-VR-1002-", 1, anyStatus12,
                false, ISSUED_LAPSED, onDate, out);
        lei(f.getOrDefault("entityResponsibleForReporting", ""), "1.3", "EMIR-VR-1003-", 2, anyStatus,
                skipStatus, ISSUED_ONLY, onDate, out);
        lei(f.getOrDefault("reportingCounterpartyId", ""), "1.4", "EMIR-VR-1004-", 1, anyStatus, skipStatus,
                ISSUED_ONLY, onDate, out);
        if ("true".equalsIgnoreCase(f.getOrDefault("otherCounterpartyIsLegalEntity", ""))) {
            lei(f.getOrDefault("otherCounterpartyId", ""), "1.9", "EMIR-VR-1009-", 1, anyStatus, skipStatus,
                    ISSUED_LAPSED, onDate, out);
        }
    }

    private static boolean skipStatus(String eventDate, LocalDate reportDate) {
        try {
            return !eventDate.isEmpty() && LocalDate.parse(eventDate).isBefore(reportDate.minusDays(1));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Codes: base+first = not in GLEIF (status group), base+(first+1) = wrong status,
     * base+(first+2) = not in GLEIF (any-status group).
     */
    private void lei(String lei, String field, String base, int first, boolean anyStatus, boolean skipStatus,
            Set<LeiStatus> allowed, LocalDate onDate, List<String> out) {
        if (lei.isEmpty() || leis == null) {
            return;
        }
        Optional<LeiValidity> v = leis.validityOn(lei, onDate);
        boolean known = v.isPresent() && v.get().status() != LeiStatus.UNKNOWN;
        if (!known) {
            int code = anyStatus ? first + 2 : first;
            out.add(base + String.format("%02d", code) + ": field " + field + " LEI " + lei
                    + " is not in the GLEIF database");
            return;
        }
        if (!anyStatus && !skipStatus && !allowed.contains(v.get().status())) {
            out.add(base + String.format("%02d", first + 1) + ": field " + field + " LEI " + lei + " has status "
                    + v.get().status() + " on " + onDate + "; allowed " + allowed);
        }
    }

    private void checkHistory(java.util.Map<String, String> f, EmirActionType action, @Nullable Instant reported,
            String uti, List<String> out) {
        String cpty = f.getOrDefault("reportingCounterpartyId", "");
        List<EmirRefitReport> prior = history == null ? List.of() : history.accepted(cpty, uti);
        if (prior.isEmpty()) {
            if (!OPENING.contains(action)) {
                out.add("EMIR-VR-2151-02: the first report for UTI " + uti + " must be NEWT or POSC, not " + action);
            }
            return;
        }
        boolean opened = false;
        boolean errored = false;
        boolean terminated = false;
        LocalDate lastEnd = null;
        for (EmirRefitReport p : prior) {
            java.util.Map<String, String> pf = p.fields();
            @Nullable EmirActionType a = action(pf.getOrDefault("actionType", ""));
            if (a == null) {
                continue;
            }
            switch (a) {
                case NEWT, POSC -> opened = true;
                case EROR -> errored = true;
                case TERM -> terminated = true;
                case REVI -> {
                    errored = false;
                    terminated = false;
                }
                default -> {
                    // MODI, CORR, VALU leave the state alone
                }
            }
            lastEnd = laterOf(lastEnd, date(pf.getOrDefault("expirationDate", "")));
            LocalDate early = date(pf.getOrDefault("earlyTerminationDate", ""));
            if (early != null) {
                lastEnd = early;
            }
        }
        if (OPENING.contains(action) && opened) {
            out.add("EMIR-VR-2151-03: UTI " + uti + " already has a NEWT or POSC from " + cpty);
        }
        if (errored) {
            if (action != EmirActionType.REVI) {
                out.add("EMIR-VR-2151-04: UTI " + uti + " was reported in error; only REVI is accepted, not "
                        + action);
            }
            return;
        }
        LocalDate today = reported == null ? null : reported.atZone(ZoneOffset.UTC).toLocalDate();
        boolean outstanding = !terminated && (lastEnd == null || today == null || !lastEnd.isBefore(today));
        if (!outstanding && !NON_OUTSTANDING_ALLOWED.contains(action)) {
            out.add("EMIR-VR-2151-05: UTI " + uti + " is not outstanding; " + action + " is not allowed");
        }
        if (outstanding && action == EmirActionType.REVI) {
            out.add("EMIR-VR-2151-06: UTI " + uti + " is outstanding; REVI is not allowed");
        }
        java.util.Map<String, String> first = prior.get(0).fields();
        unchanged(first, f, "reportingCounterpartyId", "1.4", "EMIR-VR-1004-05", out);
        unchanged(first, f, "otherCounterpartyId", "1.9", "EMIR-VR-1009-09", out);
    }

    private static void unchanged(java.util.Map<String, String> first, java.util.Map<String, String> now,
            String key, String field, String code, List<String> out) {
        String a = first.getOrDefault(key, "");
        String b = now.getOrDefault(key, "");
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
            out.add(code + ": field " + field + " changed from " + a + " (first report) to " + b);
        }
    }

    private static @Nullable LocalDate laterOf(@Nullable LocalDate a, @Nullable LocalDate b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isAfter(a) ? b : a;
    }

    private static @Nullable LocalDate date(String s) {
        try {
            return s.isEmpty() ? null : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static @Nullable Instant instant(String s) {
        try {
            return s.isEmpty() ? null : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static @Nullable EmirActionType action(String s) {
        for (EmirActionType a : EmirActionType.values()) {
            if (a.name().equals(s)) {
                return a;
            }
        }
        return null;
    }
}

package com.quantfinlib.regreporting.emir;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import com.quantfinlib.regreporting.registry.LeiRecord;
import com.quantfinlib.regreporting.registry.LeiRegistry;
import com.quantfinlib.regreporting.registry.LeiStatus;
import com.quantfinlib.regreporting.registry.LeiValidity;
import com.quantfinlib.regreporting.registry.MicRecord;
import com.quantfinlib.regreporting.registry.MicRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
 * earlier than the day before the date of 1.1 (same rows). Since part 2 of 3.1.0 the branch
 * tests (EMIR-VR-1002-04, 1003-05, 1004-04, 1009-04) read {@link LeiRecord#entityCategory()}
 * and fail only when GLEIF says {@code BRANCH}; an unknown category is not a failure. With a
 * {@link MicRegistry} (see {@link #withMicRegistry}) EMIR-VR-1003-01 reads field 2.41
 * (builder key {@code venueOfExecution}). With a history, the "reported for that UTI" date
 * rules (2044-04, 2045-02/03/06/07/08, 2153-06/08) take a date the current report leaves
 * blank from the latest earlier report that carried it, and a trade key (see
 * {@link #withTradeKey}) enables EMIR-VR-2001-03.</p>
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

    private static final String VENUE_KEY = "venueOfExecution";

    /** EU 27 plus Iceland, Liechtenstein and Norway (EEA Agreement, Art. 126 and Protocol 1 as amended). */
    private static final Set<String> EEA = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
            "IS", "LI", "NO");

    private static final List<RuleProvenance> RULES_31B = List.of(
            new RuleProvenance("EMIR-VR-1002-04/1003-05/1004-04/1009-04", "the LEI in 1.2, 1.3, 1.4 or 1.9 "
                    + "pertains to a legal entity and not a branch; GLEIF Entity.EntityCategory BRANCH fails, "
                    + "an unknown category is skipped", WB + "rows 6, 7, 8 and 13; category values from the "
                    + "LEI-CDF 3.1 XSD, EntityCategoryTypeEnum", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-1003-01", "1.3 is populated unless 2.41 is a regulated market, and blank "
                    + "when it is; regulated market read as ISO 10383 category RMKT in an EEA country, active on "
                    + "the date of 2.42; a non-EEA RMKT (third-country equivalence) or an unknown MIC or "
                    + "category skips the rule", WB + "row 7 (field 1.3); the RMKT/EEA reading is the library's",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-VR-1008-02", "1.8 is unchanged since the first accepted report",
                    WB + "row 12 (field 1.8)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2001-03", "the UTI of a trade does not change once reported; the trade "
                    + "identity is a caller-supplied key, because the UTI is otherwise the only identity",
                    WB + "row 26 (field 2.1); the trade key is the library's device", Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-VR-2042-03", "MODI does not change 2.42 from the previous reports",
                    WB + "row 67 (field 2.42)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2044-04", "REVI after a report that was not EROR: 2.44 on or after the "
                    + "date of 1.1", WB + "row 69 (field 2.44, condition 4)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2045-02", "2.45 on or after the date of 2.42 reported for the UTI "
                    + "(history form; the single-report form is in EmirRefitReport)", WB + "row 70 (field 2.45)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2045-03", "2.45 earlier than a 2.44 previously reported for the UTI",
                    WB + "row 70 (field 2.45)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2045-06", "2.45 is reported for the first time only with TERM",
                    WB + "row 70 (field 2.45)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2045-07", "terminated and not revived: CORR carries 2.45",
                    WB + "row 70 (field 2.45)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2045-08", "REVI after a report that was not EROR: 2.45 blank",
                    WB + "row 70 (field 2.45)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2153-06", "CORR: 2.153 on or after the date of 2.42 (taken from the "
                    + "history when the CORR leaves it blank)", WB + "row 186 (field 2.153)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-VR-2153-08", "except REVI, 2.153 on or before the 2.44 and 2.45 reported for "
                    + "the UTI (history form); column AC numbers it 7 after a second 6, so the code is taken "
                    + "from column AD", WB + "row 186 (field 2.153)", Evidence.LIBRARY_READING));

    private static final List<RuleProvenance> ALL_RULES;

    static {
        List<RuleProvenance> all = new ArrayList<>(RULES);
        all.addAll(RULES_31B);
        ALL_RULES = List.copyOf(all);
    }

    private final @Nullable UtiHistory history;
    private final @Nullable LeiRegistry leis;
    private final @Nullable CurrencyRegistry currencies;
    private final @Nullable MicRegistry mics;
    private final @Nullable Function<EmirRefitReport, String> tradeKey;

    private EmirStatefulValidator(@Nullable UtiHistory history, @Nullable LeiRegistry leis,
            @Nullable CurrencyRegistry currencies, @Nullable MicRegistry mics,
            @Nullable Function<EmirRefitReport, String> tradeKey) {
        this.history = history;
        this.leis = leis;
        this.currencies = currencies;
        this.mics = mics;
        this.tradeKey = tradeKey;
    }

    /**
     * A validator with no state sources; add them with the {@code with...} methods.
     *
     * @return the validator
     */
    public static EmirStatefulValidator create() {
        return new EmirStatefulValidator(null, null, null, null, null);
    }

    /**
     * A copy that also applies the action-history rules.
     *
     * @param h earlier accepted reports
     * @return a new validator
     */
    public EmirStatefulValidator withHistory(UtiHistory h) {
        return new EmirStatefulValidator(h, leis, currencies, mics, tradeKey);
    }

    /**
     * A copy that also applies the GLEIF status rules.
     *
     * @param r the LEI registry
     * @return a new validator
     */
    public EmirStatefulValidator withLeiRegistry(LeiRegistry r) {
        return new EmirStatefulValidator(history, r, currencies, mics, tradeKey);
    }

    /**
     * A copy that also checks currencies against the official ISO 4217 list.
     *
     * @param c the currency list
     * @return a new validator
     */
    public EmirStatefulValidator withCurrencies(CurrencyRegistry c) {
        return new EmirStatefulValidator(history, leis, c, mics, tradeKey);
    }

    /**
     * A copy that also applies EMIR-VR-1003-01, which needs the market category and country
     * of the venue in field 2.41 (builder key {@code venueOfExecution}).
     *
     * @param m the ISO 10383 registry
     * @return a new validator
     * @since 3.1.0
     */
    public EmirStatefulValidator withMicRegistry(MicRegistry m) {
        return new EmirStatefulValidator(history, leis, currencies, java.util.Objects.requireNonNull(m, "m"),
                tradeKey);
    }

    /**
     * A copy that also applies EMIR-VR-2001-03 ("not allowed to change the content of this
     * field once it is reported"). The history is keyed by UTI, so a changed UTI looks like a
     * new trade; the rule needs another identity for the trade, which the caller supplies
     * (for example the firm's own trade id, put on the report with
     * {@code builder.field("internalTradeId", id)}). Give the same function to
     * {@link InMemoryUtiHistory#InMemoryUtiHistory(Function)}, or implement
     * {@link UtiHistory#firstUtiForTradeKey}. A blank key skips the rule. It needs a history.
     *
     * @param key maps a report to its trade key
     * @return a new validator
     * @since 3.1.0
     */
    public EmirStatefulValidator withTradeKey(Function<EmirRefitReport, String> key) {
        return new EmirStatefulValidator(history, leis, currencies, mics,
                java.util.Objects.requireNonNull(key, "key"));
    }

    /**
     * Every stateful rule this class can apply, with citation and evidence class.
     *
     * @return the rule catalogue (immutable)
     */
    public static List<RuleProvenance> rules() {
        return ALL_RULES;
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
        if (mics != null && action != null) {
            checkVenue(f, action, out);
        }
        if (history != null && action != null) {
            checkHistory(f, action, reported, report.reference(), out);
            checkTradeKey(report, f, out);
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
        if (leis.lookup(lei).flatMap(LeiRecord::isBranch).orElse(false)) {
            out.add(base + String.format("%02d", first + 3) + ": field " + field + " LEI " + lei
                    + " is a branch in GLEIF; it must pertain to a legal entity");
        }
    }

    private void checkVenue(java.util.Map<String, String> f, EmirActionType action, List<String> out) {
        String mic = f.getOrDefault(VENUE_KEY, "");
        if (mic.isEmpty() || mics == null) {
            return;
        }
        Optional<MicRecord> rec = mics.lookup(mic);
        if (rec.isEmpty() || rec.get().marketCategory().isEmpty()) {
            return;
        }
        String country = rec.get().country().isEmpty() ? mics.country(mic).orElse("") : rec.get().country();
        boolean rmkt = "RMKT".equals(rec.get().marketCategory());
        if (rmkt && !EEA.contains(country)) {
            return; // third-country equivalence is not in ISO 10383, so it cannot be decided here
        }
        @Nullable Instant executed = instant(f.getOrDefault("executionTimestamp", ""));
        boolean regulated = rmkt && (executed == null
                || mics.isActiveOn(mic, executed.atZone(ZoneOffset.UTC).toLocalDate()));
        boolean populated = !f.getOrDefault("entityResponsibleForReporting", "").isEmpty();
        if (regulated && populated) {
            out.add("EMIR-VR-1003-01: field 1.3 must be blank, 2.41 " + mic + " is a regulated market");
        } else if (!regulated && !populated && action != EmirActionType.EROR) {
            out.add("EMIR-VR-1003-01: field 1.3 must be populated, 2.41 " + mic + " is not a regulated market");
        }
    }

    private void checkTradeKey(EmirRefitReport report, java.util.Map<String, String> f, List<String> out) {
        if (tradeKey == null || history == null) {
            return;
        }
        String key = tradeKey.apply(report);
        if (key == null || key.isEmpty()) {
            return;
        }
        String cpty = f.getOrDefault("reportingCounterpartyId", "");
        Optional<String> first = history.firstUtiForTradeKey(cpty, key);
        if (first.isPresent() && !first.get().equals(report.reference())) {
            out.add("EMIR-VR-2001-03: trade " + key + " was reported with UTI " + first.get()
                    + "; it cannot change to " + report.reference());
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
        checkUtiDates(prior, f, action, reported, out);
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
        unchanged(first, f, "otherCounterpartyIsLegalEntity", "1.8", "EMIR-VR-1008-02", out);
    }

    /**
     * The rules that compare with a value "reported for that UTI" (rows 67, 69, 70, 186). A
     * date the current report leaves blank is taken from the latest earlier report that
     * carried it; a REVI resets the earlier 2.45, since a revived trade is no longer
     * terminated.
     */
    private static void checkUtiDates(List<EmirRefitReport> prior, java.util.Map<String, String> f,
            EmirActionType action, @Nullable Instant reported, List<String> out) {
        String priorExec = "";
        String priorExpiry = "";
        String priorEarly = "";
        boolean earlyEverReported = false;
        boolean terminated = false;
        for (EmirRefitReport p : prior) {
            java.util.Map<String, String> pf = p.fields();
            @Nullable EmirActionType a = action(pf.getOrDefault("actionType", ""));
            priorExec = pick(pf.getOrDefault("executionTimestamp", ""), priorExec);
            priorExpiry = pick(pf.getOrDefault("expirationDate", ""), priorExpiry);
            String pe = pf.getOrDefault("earlyTerminationDate", "");
            if (!pe.isEmpty()) {
                earlyEverReported = true;
            }
            if (a == EmirActionType.REVI) {
                priorEarly = pe;
                terminated = false;
            } else {
                priorEarly = pick(pe, priorEarly);
            }
            if (a == EmirActionType.TERM) {
                terminated = true;
            }
        }
        @Nullable EmirActionType previous = action(prior.get(prior.size() - 1).fields()
                .getOrDefault("actionType", ""));
        String exec = f.getOrDefault("executionTimestamp", "");
        String expiry = f.getOrDefault("expirationDate", "");
        String early = f.getOrDefault("earlyTerminationDate", "");
        @Nullable LocalDate event = date(f.getOrDefault("eventDate", ""));
        @Nullable LocalDate today = reported == null ? null : reported.atZone(ZoneOffset.UTC).toLocalDate();
        @Nullable Instant execAt = instant(exec.isEmpty() ? priorExec : exec);
        @Nullable LocalDate execDate = execAt == null ? null : execAt.atZone(ZoneOffset.UTC).toLocalDate();
        @Nullable LocalDate earlyDate = date(early);
        @Nullable LocalDate expiryDate = date(expiry);
        @Nullable LocalDate priorExpiryDate = date(priorExpiry);

        if (action == EmirActionType.MODI && !exec.isEmpty() && !priorExec.isEmpty() && !exec.equals(priorExec)) {
            out.add("EMIR-VR-2042-03: MODI changes field 2.42 from " + priorExec + " to " + exec);
        }
        boolean reviAfterNonError = action == EmirActionType.REVI && previous != EmirActionType.EROR;
        if (reviAfterNonError && expiryDate != null && today != null && expiryDate.isBefore(today)) {
            out.add("EMIR-VR-2044-04: REVI after " + previous + ": field 2.44 " + expiryDate
                    + " is before the date of 1.1 " + today);
        }
        if (reviAfterNonError && !early.isEmpty()) {
            out.add("EMIR-VR-2045-08: REVI after " + previous + ": field 2.45 must be blank");
        }
        if (earlyDate != null && exec.isEmpty() && execDate != null && earlyDate.isBefore(execDate)) {
            out.add("EMIR-VR-2045-02: field 2.45 " + earlyDate + " is before the date of 2.42 " + execDate
                    + " reported for the UTI");
        }
        if (earlyDate != null && priorExpiryDate != null && !earlyDate.isBefore(priorExpiryDate)) {
            out.add("EMIR-VR-2045-03: field 2.45 " + earlyDate + " is not earlier than the 2.44 "
                    + priorExpiryDate + " previously reported for the UTI");
        }
        if (earlyDate != null && !earlyEverReported && action != EmirActionType.TERM) {
            out.add("EMIR-VR-2045-06: field 2.45 is reported for the first time with " + action + ", not TERM");
        }
        if (terminated && action == EmirActionType.CORR && early.isEmpty()) {
            out.add("EMIR-VR-2045-07: the derivative was terminated and not revived; CORR must carry field 2.45");
        }
        if (action == EmirActionType.CORR && event != null && execDate != null && event.isBefore(execDate)) {
            out.add("EMIR-VR-2153-06: CORR event date " + event + " is before the date of 2.42 " + execDate);
        }
        if (action != EmirActionType.REVI && event != null) {
            @Nullable LocalDate bound = expiry.isEmpty() ? priorExpiryDate : null;
            if (bound != null && event.isAfter(bound)) {
                out.add("EMIR-VR-2153-08: event date " + event + " is after the 2.44 " + bound
                        + " reported for the UTI");
            }
            @Nullable LocalDate earlyBound = early.isEmpty() ? date(priorEarly) : null;
            if (earlyBound != null && event.isAfter(earlyBound)) {
                out.add("EMIR-VR-2153-08: event date " + event + " is after the 2.45 " + earlyBound
                        + " reported for the UTI");
            }
        }
    }

    private static String pick(String now, String before) {
        return now.isEmpty() ? before : now;
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

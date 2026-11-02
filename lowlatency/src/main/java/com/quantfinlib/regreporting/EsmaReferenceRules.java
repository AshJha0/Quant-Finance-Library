package com.quantfinlib.regreporting;

import com.quantfinlib.regreporting.registry.CodeRegistry;
import com.quantfinlib.regreporting.registry.InstrumentRegistry;
import com.quantfinlib.regreporting.registry.LeiRegistry;
import com.quantfinlib.regreporting.registry.LeiStatus;
import com.quantfinlib.regreporting.registry.LeiValidity;
import com.quantfinlib.regreporting.registry.MicRegistry;
import com.quantfinlib.regreporting.registry.ReferenceData;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.quantfinlib.regreporting.Rts22Field.*;

/**
 * ESMA validation rules that need reference data (ESMA65-8-2594 Annex 1, sheet
 * TransactionDataValidations, v1.7). Each check names its error code. See
 * {@link Rts22TransactionReport#validateWithReferenceData} for the scope.
 */
final class EsmaReferenceRules {
    /** EU 27 plus Iceland, Liechtenstein and Norway (EEA Agreement, Art. 126 and Protocol 1 as amended). */
    static final Set<String> EEA = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE",
            "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
            "IS", "LI", "NO");
    /** Special codes the currency rules reject (CON-310, CON-340 and the others, same list each time). */
    static final Set<String> SPECIAL_CURRENCIES = Set.of("XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XDR",
            "XEU", "XFU", "XPD", "XPT", "XXX");
    /** CON-070 etc: from this trading date the InitialRegistrationDate is also checked. */
    static final LocalDate LEI_REG_DATE_CHECK_FROM = LocalDate.of(2018, 7, 3);

    private final Rts22TransactionReport r;
    private final ReferenceData refs;
    private final LocalDate submission;
    private final List<String> errs = new ArrayList<>();

    private EsmaReferenceRules(Rts22TransactionReport r, ReferenceData refs, LocalDate submission) {
        this.r = r;
        this.refs = refs;
        this.submission = submission;
    }

    static List<String> check(Rts22TransactionReport r, ReferenceData refs, LocalDate submission) {
        EsmaReferenceRules e = new EsmaReferenceRules(r, Objects.requireNonNull(refs, "refs"),
                Objects.requireNonNull(submission, "submission"));
        e.run();
        return List.copyOf(e.errs);
    }

    private String v(Rts22Field f) {
        return r.get(f);
    }

    private boolean has(Rts22Field f) {
        return !r.get(f).isEmpty();
    }

    private void err(String code, String msg) {
        errs.add(code + ": " + msg);
    }

    private void run() {
        LeiRegistry leis = refs.leis();
        if (leis != null) {
            // Field 6 is in cancellations too and is checked on the submission date.
            lei("CON-060", SUBMITTING_ENTITY_ID, leis, submission, true, false);
        }
        if ("CANC".equals(v(REPORT_STATUS))) {
            return;
        }
        LocalDate td = tradeDate();
        if (td == null) {
            err("REF", "field 28 has no parsable trading date; date-dependent rules not run");
            return;
        }
        if (leis != null) {
            executingEntity(leis, td);
            for (Object[] p : new Object[][] {{"CON-070", BUYER_ID}, {"CON-120", BUYER_DECISION_MAKER_ID},
                    {"CON-160", SELLER_ID}, {"CON-210", SELLER_DECISION_MAKER_ID}}) {
                Rts22Field f = (Rts22Field) p[1];
                if (Auth016Writer.isLei(v(f))) {
                    lei((String) p[0], f, leis, td, true, td.isBefore(LEI_REG_DATE_CHECK_FROM));
                }
            }
            lei("CON-260", TRANSMITTING_FIRM_ID_BUYER, leis, td, true, false);
            lei("CON-270", TRANSMITTING_FIRM_ID_SELLER, leis, td, true, false);
        }
        CodeRegistry countries = refs.countries();
        if (countries != null) {
            countries(countries, td);
        }
        CodeRegistry ccys = refs.currencies();
        if (ccys != null) {
            currency("CON-310", QUANTITY_CURRENCY, ccys, td, true);
            currency("CON-340", PRICE_CURRENCY, ccys, td, false);
            currency("CON-390", UP_FRONT_PAYMENT_CURRENCY, ccys, td, false);
            currency("CON-440", NOTIONAL_CURRENCY_1, ccys, td, true);
            currency("CON-451", NOTIONAL_CURRENCY_2, ccys, td, true);
            currency("CON-520", STRIKE_PRICE_CURRENCY, ccys, td, false);
        }
        MicRegistry mics = refs.mics();
        if (mics != null) {
            partyMic("CON-072", BUYER_ID, mics, td);
            partyMic("CON-162", SELLER_ID, mics, td);
            String venue = v(VENUE);
            if (!venue.isEmpty() && !"XOFF".equals(venue) && !"XXXX".equals(venue) && !mics.isActiveOn(venue, td)) {
                err("CON-360", "field 36 MIC " + venue + " is not active on " + td);
            }
            if (has(WAIVER_INDICATOR) && eea(mics, venue).equals(Optional.of(false))) {
                err("CON-610", "field 61 must be blank for the non-EEA venue " + venue);
            }
        }
        InstrumentRegistry instruments = refs.instruments();
        if (instruments != null) {
            instrument(instruments, mics, td);
        }
    }

    private LocalDate tradeDate() {
        String t = v(TRADING_DATE_TIME);
        if (t.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** CON-040 (field 5 true) and CON-041 (field 5 false). */
    private void executingEntity(LeiRegistry leis, LocalDate td) {
        if ("true".equals(v(INVESTMENT_FIRM_INDICATOR))) {
            String lei = v(EXECUTING_ENTITY_ID);
            Optional<LeiValidity> onTrade = lei.isEmpty() ? Optional.empty() : leis.validityOn(lei, td);
            if (onTrade.isPresent() && onTrade.get().status() == LeiStatus.LAPSED) {
                // Lapsed on the trading date: CON-040 re-checks the data as of the submission date.
                Optional<LeiValidity> onSub = leis.validityOn(lei, submission);
                if (onSub.isEmpty() || !onSub.get().status().isMaintained()) {
                    err("CON-040", "field 4 LEI " + lei + " is lapsed on " + td + " and not renewed by " + submission);
                    return;
                }
                dates("CON-040", EXECUTING_ENTITY_ID, lei, onTrade.get(), td, false);
                return;
            }
            lei("CON-040", EXECUTING_ENTITY_ID, leis, td, false, false);
        } else {
            lei("CON-041", EXECUTING_ENTITY_ID, leis, td, true, false);
        }
    }

    /**
     * The common LEI rule: in GLEIF; status Issued, Pending transfer, Pending archival
     * (and Lapsed when allowed); InitialRegistrationDate on or before the date (unless
     * skipped); EntityStatus Active, or LastUpdateDate on or after the date.
     */
    private void lei(String code, Rts22Field f, LeiRegistry leis, LocalDate on, boolean lapsedOk,
            boolean skipRegDate) {
        String lei = v(f);
        if (lei.isEmpty()) {
            return;
        }
        Optional<LeiValidity> val = leis.validityOn(lei, on);
        if (val.isEmpty()) {
            err(code, Rts22TransactionReport.tag(f) + " LEI " + lei + " is not in the GLEIF data");
            return;
        }
        LeiStatus s = val.get().status();
        if (!(s.isMaintained() || (lapsedOk && s == LeiStatus.LAPSED))) {
            err(code, Rts22TransactionReport.tag(f) + " LEI " + lei + " has status " + s + " on " + on);
            return;
        }
        dates(code, f, lei, val.get(), on, skipRegDate);
    }

    private void dates(String code, Rts22Field f, String lei, LeiValidity val, LocalDate on, boolean skipRegDate) {
        if (!skipRegDate && val.initialRegistrationDate().map(d -> d.isAfter(on)).orElse(false)) {
            err(code, Rts22TransactionReport.tag(f) + " LEI " + lei + " was first registered after " + on);
        }
        if (!val.entityActive() && val.lastUpdateDate().map(d -> d.isBefore(on)).orElse(true)) {
            err(code, Rts22TransactionReport.tag(f) + " LEI " + lei + " entity is inactive on " + on);
        }
    }

    private void countries(CodeRegistry c, LocalDate td) {
        nationalIdCountry("CON-071", BUYER_ID, c, td, true);
        nationalIdCountry("CON-121", BUYER_DECISION_MAKER_ID, c, td, false);
        nationalIdCountry("CON-161", SELLER_ID, c, td, true);
        nationalIdCountry("CON-211", SELLER_DECISION_MAKER_ID, c, td, false);
        // Fields 57 and 59 hold a person (national id) only when the branch field 58 / 60 is set.
        if (has(INVESTMENT_DECISION_BRANCH_COUNTRY)) {
            nationalIdCountry("CON-573", INVESTMENT_DECISION_WITHIN_FIRM, c, td, false);
        }
        if (has(EXECUTION_BRANCH_COUNTRY)) {
            nationalIdCountry("CON-590", EXECUTION_WITHIN_FIRM, c, td, false);
        }
        country("CON-080", BUYER_BRANCH_COUNTRY, c, td);
        country("CON-170", SELLER_BRANCH_COUNTRY, c, td);
        country("CON-371", COUNTRY_OF_BRANCH_MEMBERSHIP, c, td);
        country("CON-580", INVESTMENT_DECISION_BRANCH_COUNTRY, c, td);
        country("CON-600", EXECUTION_BRANCH_COUNTRY, c, td);
    }

    private void nationalIdCountry(String code, Rts22Field f, CodeRegistry c, LocalDate td, boolean micOrIntc) {
        String raw = v(f);
        if (raw.isEmpty() || Auth016Writer.isLei(raw) || (micOrIntc && ("INTC".equals(raw) || raw.length() == 4))) {
            return;
        }
        String bare = raw.startsWith("CCPT:") || raw.startsWith("NIDN:") ? raw.substring(5) : raw;
        if (bare.length() < 2 || !c.isValidOn(bare.substring(0, 2), td)) {
            err(code, Rts22TransactionReport.tag(f) + " national id " + raw
                    + " does not start with a country code valid on " + td);
        }
    }

    private void country(String code, Rts22Field f, CodeRegistry c, LocalDate td) {
        String cc = v(f);
        if (!cc.isEmpty() && !c.isValidOn(cc, td)) {
            err(code, Rts22TransactionReport.tag(f) + " country " + cc + " is not valid on " + td);
        }
    }

    private void currency(String code, Rts22Field f, CodeRegistry c, LocalDate td, boolean preEuroOk) {
        String ccy = v(f);
        if (ccy.isEmpty()) {
            return;
        }
        if (SPECIAL_CURRENCIES.contains(ccy)) {
            err(code, Rts22TransactionReport.tag(f) + " special currency code " + ccy + " is not allowed");
        } else if (!c.isValidOn(ccy, td) && !(preEuroOk && refs.preEuroCurrencies().contains(ccy))) {
            err(code, Rts22TransactionReport.tag(f) + " currency " + ccy + " is not valid on " + td);
        }
    }

    private void partyMic(String code, Rts22Field f, MicRegistry mics, LocalDate td) {
        String raw = v(f);
        if (raw.length() == 4 && !"INTC".equals(raw) && !"XOFF".equals(raw) && !"XXXX".equals(raw)
                && !mics.isActiveOn(raw, td)) {
            err(code, Rts22TransactionReport.tag(f) + " MIC " + raw + " is not valid for " + td);
        }
    }

    /** True for an EEA venue, false for a non-EEA one, empty when the country is not known. */
    private static Optional<Boolean> eea(MicRegistry mics, String mic) {
        if ("XOFF".equals(mic) || "XXXX".equals(mic) || mic.isEmpty()) {
            return Optional.empty();
        }
        return mics.country(mic).map(EEA::contains);
    }

    private void instrument(InstrumentRegistry ins, MicRegistry mics, LocalDate td) {
        String venue = v(VENUE);
        String isin = v(INSTRUMENT_ID);
        boolean underlying = has(UNDERLYING_INSTRUMENT_ID) || has(UNDERLYING_INDEX_NAME);
        Optional<Boolean> venueEea = mics == null ? Optional.empty() : eea(mics, venue);
        boolean si = mics != null && !venue.isEmpty() && mics.isSystematicInternaliser(venue).orElse(false);
        boolean known = !isin.isEmpty() && ins.isKnownOn(isin, td);

        // CON-361: XXXX may not be used for an instrument in the reference data.
        if ("XXXX".equals(venue) && known) {
            err("CON-361", "field 41 " + isin + " is in the instrument reference data on " + td
                    + ", so field 36 cannot be XXXX");
        }

        // CON-411: instrument present for the trading date. ESMA pends the report for up to 7 days first.
        if (!isin.isEmpty()) {
            if (venueEea.orElse(false) && !si) {
                if (!ins.isAdmittedOn(isin, venue, td)) {
                    err("CON-411", "field 41 " + isin + " is not in the reference data for venue " + venue
                            + " on " + td + " (the NCA pends this for up to 7 days)");
                }
            } else if (!underlying && ("XOFF".equals(venue) || si || venueEea.equals(Optional.of(false))) && !known) {
                err("CON-411", "field 41 " + isin + " is not in the reference data on any EEA venue on " + td
                        + " (the NCA pends this for up to 7 days)");
            }
        }

        // CON-471: underlying instruments present (not for an index underlying, field 48).
        if (has(UNDERLYING_INSTRUMENT_ID) && !has(UNDERLYING_INDEX_NAME)) {
            boolean otc = "XXXX".equals(venue);
            boolean nonEea = ("XOFF".equals(venue) || venueEea.equals(Optional.of(false))) && !known;
            if (otc || nonEea) {
                for (String u : Auth016Writer.list(v(UNDERLYING_INSTRUMENT_ID))) {
                    if (!ins.isKnownOn(u, td)) {
                        err("CON-471", "field 47 underlying " + u + " is not in the reference data on " + td
                                + " (the NCA pends this for up to 7 days)");
                    }
                }
            }
        }

        // Rules that read the CFI and the commodity flag from the reference data (field 41 alone used).
        if (known && !has(INSTRUMENT_CLASSIFICATION)) {
            String cfi = ins.lookup(isin).map(rec -> rec.cfi()).orElse("");
            if (EsmaRules.cfi(cfi, "DB****", "DC****", "DT****", "DN****") && !has(NET_AMOUNT)) {
                err("CON-351", "field 35 is mandatory for the debt instrument " + isin + " (CFI " + cfi + ")");
            }
            if (EsmaRules.cfi(cfi, "SC****") && !has(UP_FRONT_PAYMENT)) {
                err("CON-381", "field 38 is mandatory for the CDS " + isin + " (CFI " + cfi + ")");
            }
            if (ins.isCommodityDerivative(isin).orElse(false) && !has(COMMODITY_DERIVATIVE_INDICATOR)) {
                err("CON-640", "field 64 is mandatory for the commodity derivative " + isin);
            }
        }
    }
}

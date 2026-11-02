package com.quantfinlib.reporting;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.reporting.registry.CodeRegistry;
import com.quantfinlib.reporting.registry.CodeRegistry.Window;
import com.quantfinlib.reporting.registry.InstrumentRecord;
import com.quantfinlib.reporting.registry.InstrumentRegistry;
import com.quantfinlib.reporting.registry.LeiRecord;
import com.quantfinlib.reporting.registry.LeiRegistry;
import com.quantfinlib.reporting.registry.LeiStatus;
import com.quantfinlib.reporting.registry.LeiValidity;
import com.quantfinlib.reporting.registry.MicRecord;
import com.quantfinlib.reporting.registry.MicRegistry;
import com.quantfinlib.reporting.registry.MicStatus;
import com.quantfinlib.reporting.registry.ReferenceData;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference-data ESMA rules against hand-made registries. Each case cites the error
 * code of ESMA65-8-2594 Annex 1, sheet TransactionDataValidations, v1.7 (published
 * reference); expected outcomes are read off the rule text (own test). The registry
 * contents are invented and describe no real entity or instrument.
 */
class Rts22ReferenceRulesTest {
    static final LocalDate TRADE = LocalDate.of(2026, 11, 2);
    static final LocalDate SUBMIT = LocalDate.of(2026, 11, 3);
    static final String ISIN = "GB0002634946";

    /** LEI fixture: one validity for dates before {@link #SUBMIT}, another from it on. */
    static final class Leis implements LeiRegistry {
        final Map<String, LeiValidity> onTrade = new HashMap<>();
        final Map<String, LeiValidity> onSubmit = new HashMap<>();

        Leis put(String lei, LeiValidity v) {
            onTrade.put(lei, v);
            onSubmit.put(lei, v);
            return this;
        }

        @Override
        public Optional<LeiRecord> lookup(String lei) {
            return Optional.ofNullable(onTrade.get(lei)).map(v -> new LeiRecord(lei, "TEST", v.status()));
        }

        @Override
        public Optional<LeiValidity> validityOn(String lei, LocalDate date) {
            return Optional.ofNullable((date.isBefore(SUBMIT) ? onTrade : onSubmit).get(lei));
        }
    }

    static LeiValidity issued() {
        return new LeiValidity(LeiStatus.ISSUED, LocalDate.of(2015, 1, 1), true, LocalDate.of(2026, 1, 1));
    }

    static Leis leis() {
        return new Leis().put(Rts22Fixtures.FIRM, issued()).put(Rts22Fixtures.CPTY, issued())
                .put(Rts22Fixtures.CLIENT_LEI, issued());
    }

    /** FIRDS fixture: ISIN on XPAR and XETR; a debt ISIN; a commodity ISIN. */
    static InstrumentRegistry instruments() {
        Map<String, InstrumentRecord> m = Map.of(
                ISIN, new InstrumentRecord(ISIN, "TEST EQUITY", "ESVUFR", Set.of("XPAR", "XETR")),
                "XS0000000009", new InstrumentRecord("XS0000000009", "TEST BOND", "DBFTFB", Set.of("XPAR")),
                "DE000A0AAAA9", new InstrumentRecord("DE000A0AAAA9", "TEST POWER FUT", "FCEPSX", Set.of("XEEE")));
        return new InstrumentRegistry() {
            @Override
            public Optional<InstrumentRecord> lookup(String isin) {
                return Optional.ofNullable(m.get(isin));
            }

            @Override
            public Optional<Boolean> isCommodityDerivative(String isin) {
                return Optional.of("DE000A0AAAA9".equals(isin));
            }
        };
    }

    /** ISO 10383 fixture with countries: XPAR FR, XETR DE, XEEE DE, XLON GB, XNYS US, XOLD expired, SINT FR SI. */
    static MicRegistry mics() {
        Map<String, String[]> m = Map.of("XPAR", new String[] {"FR", "ACTIVE"}, "XETR", new String[] {"DE", "ACTIVE"},
                "XEEE", new String[] {"DE", "ACTIVE"}, "XLON", new String[] {"GB", "ACTIVE"},
                "XNYS", new String[] {"US", "ACTIVE"}, "XOLD", new String[] {"FR", "EXPIRED"},
                "SIFR", new String[] {"FR", "ACTIVE"});
        return new MicRegistry() {
            @Override
            public Optional<MicRecord> lookup(String mic) {
                return Optional.ofNullable(m.get(mic))
                        .map(a -> new MicRecord(mic, mic, "TEST", MicStatus.parse(a[1])));
            }

            @Override
            public Optional<String> country(String mic) {
                return Optional.ofNullable(m.get(mic)).map(a -> a[0]);
            }

            @Override
            public Optional<Boolean> isSystematicInternaliser(String mic) {
                return Optional.of("SIFR".equals(mic));
            }
        };
    }

    static CodeRegistry currencies() {
        return CodeRegistry.of(Map.of("EUR", Window.always(), "GBP", Window.always(), "USD", Window.always(),
                "HRK", new Window(null, LocalDate.of(2022, 12, 31))));
    }

    static CodeRegistry countries() {
        return CodeRegistry.of(Map.of("FR", Window.always(), "DE", Window.always(), "GB", Window.always(),
                "AN", new Window(null, LocalDate.of(2010, 12, 15))));
    }

    static ReferenceData refs(Leis leis) {
        return new ReferenceData(leis, instruments(), mics(), currencies(), countries(), Set.of("FRF", "DEM"));
    }

    private static List<String> check(Rts22ReportBuilder b, Side side, ReferenceData refs) {
        return Rts22Fixtures.build(b, side, 10, "R1").validateWithReferenceData(refs, SUBMIT);
    }

    private static List<String> check(Rts22ReportBuilder b, Leis leis) {
        return check(b, Side.BUY, refs(leis));
    }

    private static boolean hit(List<String> errs, String code) {
        return errs.stream().anyMatch(s -> s.startsWith(code + ":"));
    }

    private static Rts22ReportBuilder onXpar() {
        return Rts22Fixtures.equityDeal().venue("XPAR").priceCurrency("EUR");
    }

    @Test
    void cleanReportsPass() {
        assertEquals(List.of(), check(onXpar(), leis()));
        assertEquals(List.of(), check(Rts22Fixtures.agencyForPerson(), leis()));
        assertEquals(List.of(), check(onXpar(), Side.SELL, ReferenceData.of(null, null, null)));
    }

    @Test
    void workedExampleRetiredVersusLapsedCounterparty() {
        Leis l = leis().put(Rts22Fixtures.CPTY, new LeiValidity(LeiStatus.RETIRED, null, true, null));
        List<String> e = check(onXpar(), l);
        assertTrue(hit(e, "CON-160"), e.toString());
        l.put(Rts22Fixtures.CPTY, new LeiValidity(LeiStatus.LAPSED, null, true, null));
        assertFalse(hit(check(onXpar(), l), "CON-160"));
    }

    @Test
    void unknownLeiFailsAndNamesTheField() {
        Leis l = leis();
        l.onTrade.remove(Rts22Fixtures.CPTY);
        List<String> e = check(onXpar(), l);
        assertTrue(e.stream().anyMatch(s -> s.startsWith("CON-160:") && s.contains("field 16")), e.toString());
    }

    @Test
    void executingEntityLapsedNeedsRenewalWhenInvestmentFirm() {
        // CON-040: field 5 true, lapsed on the trade date, still lapsed at submission: rejected.
        Leis l = leis();
        l.onTrade.put(Rts22Fixtures.FIRM, new LeiValidity(LeiStatus.LAPSED, LocalDate.of(2015, 1, 1), true, null));
        l.onSubmit.put(Rts22Fixtures.FIRM, new LeiValidity(LeiStatus.LAPSED, LocalDate.of(2015, 1, 1), true, null));
        assertTrue(hit(check(onXpar(), l), "CON-040"));
        // Renewed by the submission date: accepted (CON-060 on field 6 also reads the submission date).
        l.onSubmit.put(Rts22Fixtures.FIRM, issued());
        List<String> e = check(onXpar(), l);
        assertFalse(hit(e, "CON-040"), e.toString());
        assertFalse(hit(e, "CON-060"), e.toString());
    }

    @Test
    void executingEntityLapsedIsFineWhenNotInvestmentFirm() {
        Leis l = leis();
        l.put(Rts22Fixtures.FIRM, new LeiValidity(LeiStatus.LAPSED, null, true, null));
        Rts22ReportBuilder b = onXpar().set(Rts22Field.INVESTMENT_FIRM_INDICATOR, "false");
        List<String> e = check(b, l);
        assertFalse(hit(e, "CON-041"), e.toString());
        l.put(Rts22Fixtures.FIRM, new LeiValidity(LeiStatus.ANNULLED, null, true, null));
        assertTrue(hit(check(b, l), "CON-041"));
        assertTrue(hit(check(b, l), "CON-060"));
    }

    @Test
    void registrationDateAndInactiveEntity() {
        Leis l = leis().put(Rts22Fixtures.CPTY,
                new LeiValidity(LeiStatus.ISSUED, TRADE.plusDays(1), true, null));
        assertTrue(hit(check(onXpar(), l), "CON-160"));
        // Inactive entity whose last update is before the trade date: rejected; on or after: accepted.
        l.put(Rts22Fixtures.CPTY, new LeiValidity(LeiStatus.ISSUED, null, false, TRADE.minusDays(1)));
        assertTrue(hit(check(onXpar(), l), "CON-160"));
        l.put(Rts22Fixtures.CPTY, new LeiValidity(LeiStatus.ISSUED, null, false, TRADE));
        assertFalse(hit(check(onXpar(), l), "CON-160"));
    }

    @Test
    void cancellationOnlyChecksSubmittingEntity() {
        Leis l = new Leis().put(Rts22Fixtures.FIRM, issued());
        Rts22TransactionReport canc = Rts22Fixtures.build(onXpar().cancellation(true), Side.BUY, 10, "R1");
        assertEquals(List.of(), canc.validateWithReferenceData(refs(l), SUBMIT));
        Rts22TransactionReport cancUnknown = Rts22Fixtures.build(onXpar().cancellation(true), Side.BUY, 10, "R1");
        assertTrue(hit(cancUnknown.validateWithReferenceData(refs(new Leis()), SUBMIT), "CON-060"));
    }

    @Test
    void countryRules() {
        Rts22ReportBuilder b = Rts22Fixtures.agencyForPerson().set(Rts22Field.BUYER_BRANCH_COUNTRY, "AN");
        assertTrue(hit(check(b, leis()), "CON-080"));
        b = Rts22Fixtures.agencyForPerson().set(Rts22Field.COUNTRY_OF_BRANCH_MEMBERSHIP, "ZZ");
        assertTrue(hit(check(b, leis()), "CON-371"));
        // CON-071: national id prefix must be a valid country.
        b = Rts22Fixtures.agencyForPerson().client("ZZ19800413JOHN#SMITH");
        assertTrue(hit(check(b, leis()), "CON-071"));
        // CON-590: field 59 is a person (60 set) whose prefix is not a country.
        b = Rts22Fixtures.agencyForPerson().set(Rts22Field.EXECUTION_WITHIN_FIRM, "QQ19750102ANNE#MARTI");
        assertTrue(hit(check(b, leis()), "CON-590"));
    }

    @Test
    void currencyRules() {
        assertTrue(hit(check(onXpar().priceCurrency("XAU"), leis()), "CON-340"));
        // HRK was replaced by EUR on 1 January 2023 (own fixture window): not valid in 2026 for field 34.
        assertTrue(hit(check(onXpar().priceCurrency("HRK"), leis()), "CON-340"));
        // Pre-euro codes are allowed in field 31 (CON-310) but not field 34 (CON-340).
        Rts22ReportBuilder b = onXpar().set(Rts22Field.QUANTITY_CURRENCY, "FRF");
        assertFalse(hit(check(b, leis()), "CON-310"));
        assertTrue(hit(check(onXpar().priceCurrency("FRF"), leis()), "CON-340"));
    }

    @Test
    void micRules() {
        assertTrue(hit(check(Rts22Fixtures.equityDeal().venue("XOLD").priceCurrency("EUR"), leis()), "CON-360"));
        assertTrue(hit(check(onXpar().counterparty("XOLD"), leis()), "CON-162"));
        // CON-610: waiver on a non-EEA venue.
        Rts22ReportBuilder b = Rts22Fixtures.equityDeal().venue("XNYS").priceCurrency("USD")
                .set(Rts22Field.WAIVER_INDICATOR, "RFPT");
        assertTrue(hit(check(b, leis()), "CON-610"));
        assertFalse(hit(check(onXpar().set(Rts22Field.WAIVER_INDICATOR, "RFPT"), leis()), "CON-610"));
    }

    @Test
    void instrumentOnVenue() {
        // CON-411: on an EEA venue the ISIN must be listed on that venue.
        assertFalse(hit(check(onXpar(), leis()), "CON-411"));
        Rts22ReportBuilder xeee = Rts22Fixtures.equityDeal().venue("XEEE").priceCurrency("EUR");
        List<String> e = check(xeee, leis());
        assertTrue(hit(e, "CON-411") && e.toString().contains("7 days"), e.toString());
        // XOFF: listed on any EEA venue is enough; an unknown ISIN is not.
        assertFalse(hit(check(onXpar().venue("XOFF"), leis()), "CON-411"));
        assertTrue(hit(check(onXpar().venue("XOFF").isin("US0378331005"), leis()), "CON-411"));
        // SI: any EEA venue.
        assertFalse(hit(check(onXpar().venue("SIFR"), leis()), "CON-411"));
        // Non-EEA venue (XLON after Brexit in the fixture): no venue-specific check.
        assertFalse(hit(check(Rts22Fixtures.equityDeal(), leis()), "CON-411"));
    }

    @Test
    void xxxxAndUnderlyings() {
        // CON-361: XXXX with an ISIN in FIRDS.
        Rts22ReportBuilder b = Rts22Fixtures.otcOption().set(Rts22Field.INSTRUMENT_ID, ISIN);
        assertTrue(hit(check(b, Side.SELL, refs(leis())), "CON-361"));
        // CON-471: the OTC option's underlying US0378331005 is not in the fixture FIRDS.
        List<String> e = check(Rts22Fixtures.otcOption(), Side.SELL, refs(leis()));
        assertTrue(hit(e, "CON-471"), e.toString());
        b = Rts22Fixtures.otcOption().set(Rts22Field.UNDERLYING_INSTRUMENT_ID, ISIN);
        assertFalse(hit(check(b, Side.SELL, refs(leis())), "CON-471"));
    }

    @Test
    void rulesThatReadFirdsCfi() {
        Rts22ReportBuilder bond = onXpar().isin("XS0000000009");
        assertTrue(hit(check(bond, leis()), "CON-351"));
        assertFalse(hit(check(bond.set(Rts22Field.NET_AMOUNT, "1000"), leis()), "CON-351"));
        Rts22ReportBuilder fut = Rts22Fixtures.equityDeal().venue("XEEE").isin("DE000A0AAAA9").priceCurrency("EUR");
        assertTrue(hit(check(fut, leis()), "CON-640"));
        assertFalse(hit(check(fut.set(Rts22Field.COMMODITY_DERIVATIVE_INDICATOR, "false"), leis()), "CON-640"));
    }

    @Test
    void defaultInterfaceMethodsUseTheSnapshot() {
        MicRegistry m = mics();
        assertTrue(m.isActiveOn("XPAR", TRADE));
        assertFalse(m.isActiveOn("XOLD", TRADE));
        InstrumentRegistry ins = instruments();
        assertTrue(ins.isAdmittedOn(ISIN, "XPAR", TRADE));
        assertFalse(ins.isAdmittedOn(ISIN, "XEEE", TRADE));
        LeiRegistry plain = lei -> Optional.of(new LeiRecord(lei, "X", LeiStatus.ISSUED));
        LeiValidity v = plain.validityOn("A", TRADE).orElseThrow();
        assertTrue(v.entityActive());
        assertTrue(v.initialRegistrationDate().isEmpty());
        LeiRegistry noData = lei -> Optional.empty();
        assertTrue(noData.validityOn("A", TRADE).isEmpty());
        MicRegistry bare = mic -> Optional.empty();
        assertTrue(bare.country("XPAR").isEmpty());
        assertTrue(bare.isSystematicInternaliser("XPAR").isEmpty());
    }

    @Test
    void codeRegistryWindows() {
        CodeRegistry c = countries();
        assertTrue(c.isValidOn("AN", LocalDate.of(2010, 12, 15)));
        assertFalse(c.isValidOn("AN", LocalDate.of(2010, 12, 16)));
        assertFalse(c.isValidOn("XX", TRADE));
        assertThrows(NullPointerException.class, () -> c.isValidOn("FR", null));
        assertThrows(NullPointerException.class, () -> check(onXpar(), Side.BUY, null));
    }
}

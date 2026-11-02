package com.quantfinlib.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.reporting.Rts22StatusTracker.KeyState;
import com.quantfinlib.reporting.registry.InstrumentRecord;
import com.quantfinlib.reporting.registry.InstrumentRegistry;
import com.quantfinlib.reporting.registry.ReferenceData;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Field 2 history (CON-023, CON-024, CON-025; ESMA65-8-2594 Annex 1 v1.7 rules 005 to 007)
 * and the pending workflow of ESMA65-8-2356 paragraphs 20, 28, 53, 70 to 72 and 79 (all
 * published reference). Registries are invented fixtures (own test).
 */
class Rts22StatusTrackerTest {
    private static final LocalDate T1 = Rts22ReferenceRulesTest.SUBMIT; // trade on T = 2026-11-02, received T+1
    private static final String ISIN = Rts22ReferenceRulesTest.ISIN;

    /** FIRDS fixture that can gain an instrument later. */
    private static final class Firds implements InstrumentRegistry {
        final Map<String, InstrumentRecord> m = new HashMap<>();

        Firds add(String isin, String... venues) {
            m.put(isin, new InstrumentRecord(isin, "TEST", "ESVUFR", Set.of(venues)));
            return this;
        }

        @Override
        public Optional<InstrumentRecord> lookup(String isin) {
            return Optional.ofNullable(m.get(isin));
        }
    }

    private static ReferenceData refs(Firds f) {
        return new ReferenceData(Rts22ReferenceRulesTest.leis(), f, Rts22ReferenceRulesTest.mics(),
                Rts22ReferenceRulesTest.currencies(), Rts22ReferenceRulesTest.countries(), Set.of());
    }

    private static Rts22TransactionReport newReport(String trn) {
        return Rts22Fixtures.build(Rts22Fixtures.equityDeal().venue("XPAR").priceCurrency("EUR"), Side.BUY, 10, trn);
    }

    private static Rts22TransactionReport cancel(String trn) {
        return Rts22Fixtures.build(Rts22Fixtures.equityDeal().venue("XPAR").priceCurrency("EUR").cancellation(true),
                Side.BUY, 10, trn);
    }

    private static String key(String trn) {
        return Rts22StatusTracker.recordId(newReport(trn));
    }

    @Test
    void recordIdIsField4ThenField2() {
        Rts22TransactionReport r = newReport("TXN162");
        // Paragraph 103: field 4 followed by field 2, no separator.
        assertEquals(Rts22Fixtures.FIRM + r.get(Rts22Field.TRANSACTION_REFERENCE_NUMBER),
                Rts22StatusTracker.recordId(r));
    }

    @Test
    void con023ReusedReferenceRejectedUntilCancelled() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        ReferenceData refs = refs(new Firds().add(ISIN, "XPAR"));
        RecordOutcome first = t.submit(newReport("TXN1"), T1, refs);
        assertEquals(RecordStatus.ACCEPTED, first.status(), first.errors().toString());
        assertEquals(KeyState.ACTIVE, t.state(key("TXN1")));
        RecordOutcome again = t.submit(newReport("TXN1"), T1, refs);
        assertEquals(RecordStatus.REJECTED, again.status());
        assertEquals(List.of("CON-023"), again.codes());
        // Paragraph 73-74: cancel, then the corrected report reuses the reference.
        assertEquals(RecordStatus.ACCEPTED, t.submit(cancel("TXN1"), T1, refs).status());
        assertEquals(KeyState.CANCELLED, t.state(key("TXN1")));
        assertEquals(RecordStatus.ACCEPTED, t.submit(newReport("TXN1"), T1.plusDays(1), refs).status());
    }

    @Test
    void con024AndCon025Cancellations() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        ReferenceData refs = refs(new Firds().add(ISIN, "XPAR"));
        RecordOutcome none = t.submit(cancel("TXN9"), T1, refs);
        assertEquals(RecordStatus.REJECTED, none.status());
        assertTrue(none.cancellation());
        assertEquals(List.of("CON-024"), none.codes());
        t.submit(newReport("TXN9"), T1, refs);
        t.submit(cancel("TXN9"), T1, refs);
        RecordOutcome twice = t.submit(cancel("TXN9"), T1, refs);
        assertEquals(List.of("CON-025"), twice.codes());
        assertEquals(RecordStatus.REJECTED, twice.status());
    }

    @Test
    void paragraph79Scenario1And2() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        // Scenario 1: new accepted on T+1, cancellation accepted on T+2.
        Firds f = new Firds().add(ISIN, "XPAR");
        assertEquals(RecordStatus.ACCEPTED, t.submit(newReport("TXN162"), T1, refs(f)).status());
        assertEquals(RecordStatus.ACCEPTED, t.submit(cancel("TXN162"), T1.plusDays(1), refs(f)).status());
        // Scenario 2: new rejected (here a special currency code, CON-340), so its cancellation is too.
        Rts22StatusTracker t2 = new Rts22StatusTracker();
        Rts22TransactionReport bad = Rts22Fixtures.build(Rts22Fixtures.equityDeal().venue("XPAR").priceCurrency("XAU"),
                Side.BUY, 10, "TXN163");
        assertEquals(RecordStatus.REJECTED, t2.submit(bad, T1, refs(f)).status());
        assertNull(t2.state(Rts22StatusTracker.recordId(bad)), "a rejected report leaves no trace");
        assertEquals(List.of("CON-024"), t2.submit(cancel("TXN163"), T1.plusDays(1), refs(f)).codes());
    }

    @Test
    void workedExampleScenario3CancelPending() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        Firds empty = new Firds();
        RecordOutcome p = t.submit(newReport("TXN162"), T1, refs(empty));
        assertEquals(RecordStatus.PENDING, p.status());
        assertEquals(List.of("CON-411"), p.codes());
        assertEquals(List.of(key("TXN162")), t.pending());
        // A pending report counts as live for CON-023.
        RecordOutcome dup = t.submit(newReport("TXN162"), T1, refs(empty));
        assertEquals(RecordStatus.REJECTED, dup.status());
        assertEquals(List.of("CON-411", "CON-023"), dup.codes());
        // Paragraph 72: the cancellation is accepted and the queue emptied.
        assertEquals(RecordStatus.ACCEPTED, t.submit(cancel("TXN162"), T1.plusDays(1), refs(empty)).status());
        assertEquals(List.of(), t.pending());
        assertEquals(List.of(), t.revalidate(T1.plusDays(2), refs(empty)));
        assertEquals(RecordStatus.PENDING, t.submit(newReport("TXN162"), T1.plusDays(1), refs(empty)).status());
    }

    @Test
    void pendingAcceptedWhenInstrumentArrives() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        Firds f = new Firds();
        t.submit(newReport("TXN1"), T1, refs(f));
        // Paragraph 28: still pending on the next days, so nothing to report.
        assertEquals(List.of(), t.revalidate(T1.plusDays(1), refs(f)));
        f.add(ISIN, "XPAR");
        List<RecordOutcome> out = t.revalidate(T1.plusDays(3), refs(f));
        assertEquals(1, out.size());
        assertEquals(RecordStatus.ACCEPTED, out.get(0).status());
        assertEquals(KeyState.ACTIVE, t.state(key("TXN1")));
        assertEquals(List.of(), t.pending());
    }

    @Test
    void sevenDayBoundary() {
        // CON-411 error text: pending while "it is the 7th day or before"; rejected after.
        Rts22StatusTracker t = new Rts22StatusTracker();
        Firds f = new Firds();
        t.submit(newReport("TXN1"), T1, refs(f));
        assertEquals(List.of(), t.revalidate(T1.plusDays(Rts22StatusTracker.PENDING_DAYS), refs(f)));
        List<RecordOutcome> out = t.revalidate(T1.plusDays(Rts22StatusTracker.PENDING_DAYS + 1), refs(f));
        assertEquals(RecordStatus.REJECTED, out.get(0).status());
        assertEquals(List.of("CON-411"), out.get(0).codes());
        assertNull(t.state(key("TXN1")));
        assertEquals(RecordStatus.ACCEPTED, t.submit(newReport("TXN1"), T1.plusDays(9),
                refs(f.add(ISIN, "XPAR"))).status());
    }

    @Test
    void paragraph20eOtherErrorsRejectAtOnce() {
        // A currency error is not pended (paragraph 54) even when the instrument is missing too.
        Rts22StatusTracker t = new Rts22StatusTracker();
        Rts22TransactionReport r = Rts22Fixtures.build(
                Rts22Fixtures.equityDeal().venue("XPAR").priceCurrency("XAU"), Side.BUY, 10, "TXN2");
        RecordOutcome o = t.submit(r, T1, refs(new Firds()));
        assertEquals(RecordStatus.REJECTED, o.status());
        assertTrue(o.codes().contains("CON-411"), o.toString());
        assertTrue(o.codes().contains("CON-340"), o.toString());
        assertEquals(List.of(), t.pending());
    }

    @Test
    void paragraph20dBadCheckDigitRejectsAtOnce() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        Rts22TransactionReport r = Rts22Fixtures.build(
                Rts22Fixtures.equityDeal().venue("XPAR").priceCurrency("EUR").isin("GB0002634947"), Side.BUY, 10, "T3");
        RecordOutcome o = t.submit(r, T1, refs(new Firds()));
        assertEquals(RecordStatus.REJECTED, o.status());
        assertTrue(o.codes().contains("CON-410"), o.toString());
    }

    @Test
    void pendingRejectedOnOtherErrorAtRevalidation() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        Firds f = new Firds();
        t.submit(newReport("TXN1"), T1, refs(f));
        // The instrument arrives as a commodity derivative and field 64 is empty: CON-640, so
        // the report is rejected (paragraph 20(c)(iv)).
        InstrumentRegistry commodity = new InstrumentRegistry() {
            @Override
            public Optional<InstrumentRecord> lookup(String isin) {
                return Optional.of(new InstrumentRecord(isin, "T", "FCEPSX", Set.of("XPAR")));
            }

            @Override
            public Optional<Boolean> isCommodityDerivative(String isin) {
                return Optional.of(true);
            }
        };
        ReferenceData r = new ReferenceData(Rts22ReferenceRulesTest.leis(), commodity, Rts22ReferenceRulesTest.mics(),
                null, null, Set.of());
        List<RecordOutcome> out = t.revalidate(T1.plusDays(2), r);
        assertEquals(RecordStatus.REJECTED, out.get(0).status());
        assertEquals(List.of("CON-640"), out.get(0).codes());
        assertEquals(List.of(), t.pending());
    }

    @Test
    void restoreSeedsHistory() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        ReferenceData refs = refs(new Firds().add(ISIN, "XPAR"));
        t.restore(key("OLD1"), KeyState.ACTIVE);
        assertEquals(List.of("CON-023"), t.submit(newReport("OLD1"), T1, refs).codes());
        t.restore(key("OLD1"), KeyState.CANCELLED);
        assertEquals(RecordStatus.ACCEPTED, t.submit(newReport("OLD1"), T1, refs).status());
        assertThrows(IllegalArgumentException.class, () -> t.restore(key("OLD1"), KeyState.PENDING));
        assertThrows(NullPointerException.class, () -> t.restore(null, KeyState.ACTIVE));
        assertThrows(NullPointerException.class, () -> t.state(null));
        // Restoring over a pending key drops it from the queue.
        t.submit(newReport("P1"), T1, refs(new Firds()));
        t.restore(key("P1"), KeyState.CANCELLED);
        assertEquals(List.of(), t.pending());
    }

    @Test
    void nullArgumentsRejected() {
        Rts22StatusTracker t = new Rts22StatusTracker();
        ReferenceData refs = refs(new Firds());
        assertThrows(NullPointerException.class, () -> t.submit(null, T1, refs));
        assertThrows(NullPointerException.class, () -> t.submit(newReport("A"), null, refs));
        assertThrows(NullPointerException.class, () -> t.submit(newReport("A"), T1, null));
        assertThrows(NullPointerException.class, () -> t.revalidate(null, refs));
        assertThrows(NullPointerException.class, () -> t.revalidate(T1, null));
    }

    @Test
    void outcomeCodes() {
        RecordOutcome o = new RecordOutcome("K", false, RecordStatus.REJECTED, List.of("CON-023: x", "plain"));
        assertEquals(List.of("CON-023", "plain"), o.codes());
        assertEquals(RecordStatus.PENDING, RecordStatus.ofCode("PDNG"));
        assertEquals("RCVD", RecordStatus.RECEIVED.code());
        assertThrows(IllegalArgumentException.class, () -> RecordStatus.ofCode("ACPD"));
    }
}

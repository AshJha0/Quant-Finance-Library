package com.quantfinlib.regreporting.registry;

import com.quantfinlib.regreporting.TransactionReport;
import com.quantfinlib.regreporting.TransactionReportSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A {@link TransactionReportSink} decorator that checks identifiers in a report's
 * {@link TransactionReport#fields()} against the registries before forwarding it.
 *
 * <p>The caller names which field keys hold LEIs, ISINs and MICs, since keys differ between
 * regimes. An LEI must be maintained ({@link LeiStatus#isMaintained()}), an ISIN must be
 * known, and a MIC must be usable ({@link MicStatus#isUsable()}); the conventional
 * {@code XOFF} and {@code XXXX} venue values are passed through. Blank fields are skipped,
 * because presence is the report's own {@code validate()} job. {@link #submit} throws
 * {@link IllegalArgumentException} on any problem and forwards clean reports.</p>
 */
public final class ReferenceDataChecker implements TransactionReportSink {
    private final LeiRegistry leis;
    private final InstrumentRegistry instruments;
    private final MicRegistry mics;
    private final Set<String> leiFields;
    private final Set<String> isinFields;
    private final Set<String> micFields;
    private final TransactionReportSink downstream;

    /**
     * Creates the checker.
     *
     * @param leis LEI registry
     * @param instruments instrument registry
     * @param mics MIC registry
     * @param leiFields field keys holding LEIs
     * @param isinFields field keys holding ISINs
     * @param micFields field keys holding MICs
     * @param downstream where accepted reports go
     */
    public ReferenceDataChecker(LeiRegistry leis, InstrumentRegistry instruments, MicRegistry mics,
            Set<String> leiFields, Set<String> isinFields, Set<String> micFields,
            TransactionReportSink downstream) {
        this.leis = Objects.requireNonNull(leis, "leis");
        this.instruments = Objects.requireNonNull(instruments, "instruments");
        this.mics = Objects.requireNonNull(mics, "mics");
        this.leiFields = new TreeSet<>(leiFields);
        this.isinFields = new TreeSet<>(isinFields);
        this.micFields = new TreeSet<>(micFields);
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    /**
     * Lists reference-data problems; empty when clean.
     *
     * @param report the report
     * @return problems, in field-key order
     */
    public List<String> check(TransactionReport report) {
        Map<String, String> f = report.fields();
        List<String> out = new ArrayList<>();
        for (String k : leiFields) {
            String v = f.getOrDefault(k, "");
            if (!v.isBlank()) {
                LeiStatus s = leis.status(v);
                if (!s.isMaintained()) {
                    out.add(k + ": LEI " + v + " status " + s);
                }
            }
        }
        for (String k : isinFields) {
            String v = f.getOrDefault(k, "");
            if (!v.isBlank() && !instruments.isKnown(v)) {
                out.add(k + ": ISIN " + v + " not in instrument registry");
            }
        }
        for (String k : micFields) {
            String v = f.getOrDefault(k, "");
            if (!v.isBlank() && !"XOFF".equals(v) && !"XXXX".equals(v)) {
                MicStatus s = mics.status(v);
                if (!s.isUsable()) {
                    out.add(k + ": MIC " + v + " status " + s);
                }
            }
        }
        return out;
    }

    @Override
    public void submit(TransactionReport report) {
        List<String> p = check(report);
        if (!p.isEmpty()) {
            throw new IllegalArgumentException("reference data: " + String.join("; ", p));
        }
        downstream.submit(report);
    }
}

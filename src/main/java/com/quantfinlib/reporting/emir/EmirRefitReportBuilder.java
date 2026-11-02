package com.quantfinlib.reporting.emir;

import com.quantfinlib.reporting.ReportingIdentifiers;
import com.quantfinlib.reporting.ReportingIdentifiers.Granularity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an {@link EmirRefitReport}.
 *
 * <p>Populated fields (camel-case names here, Refit field numbers in brackets, per
 * Implementing Regulation (EU) 2022/1860 Annex): {@code reportingTimestamp} (1.1),
 * {@code reportingCounterpartyId} (1.4), {@code otherCounterpartyId} (1.9) and
 * {@code otherCounterpartyIsLegalEntity}, {@code direction} (1.17),
 * {@code entityResponsibleForReporting} (1.3), {@code uti} (2.1), {@code isin} (2.7),
 * {@code upi} (2.8), {@code contractType} (2.10), {@code assetClass} (2.11),
 * {@code executionTimestamp} (2.42), {@code effectiveDate} (2.43),
 * {@code expirationDate} (2.44), {@code earlyTerminationDate} (2.45),
 * {@code notional1} (2.55), {@code notionalCurrency1} (2.56), {@code price} (2.48),
 * {@code actionType} (2.151), {@code eventType} (2.152), {@code eventDate} (2.153),
 * and the valuation block {@code valuationAmount} (2.21), {@code valuationCurrency} (2.22),
 * {@code valuationTimestamp} (2.23), {@code valuationMethod} (2.24). The field numbers are indicative, written from the Annex layout but NOT re-checked
 * against the published text in this change; treat them as a cross-reference only.
 * Collateral, margin, clearing, legs 2, schedules and package fields are not populated;
 * add them with {@link #field(String, String)}.</p>
 *
 * <p>Timestamps are UTC with seconds granularity
 * ({@link ReportingIdentifiers#formatTimestamp}). Worked example: generating a UTI for the
 * 20-character LEI and the suffix {@code TRADE0001} gives that LEI followed by
 * {@code TRADE0001}, 29 characters, within the 52-character limit (own test).</p>
 */
public final class EmirRefitReportBuilder {
    private final Map<String, String> f = new LinkedHashMap<>();

    private EmirRefitReportBuilder(EmirActionType action, EmirEventType event) {
        for (String k : EmirRefitReport.ALWAYS) {
            f.put(k, "");
        }
        f.put("actionType", action.name());
        f.put("eventType", event == null ? "" : event.name());
    }

    /**
     * Starts a report.
     *
     * @param action action type
     * @param event event type, or null where the action takes none (VALU, EROR, REVI, POSC)
     * @return the builder
     */
    public static EmirRefitReportBuilder of(EmirActionType action, EmirEventType event) {
        return new EmirRefitReportBuilder(Objects.requireNonNull(action, "action"), event);
    }

    /**
     * Generates a UTI in the ESMA-recommended form: the generating entity's LEI followed by a
     * unique suffix, at most 52 characters in total.
     *
     * @param generatorLei LEI of the generating entity
     * @param suffix unique part, upper-case alphanumerics
     * @return the UTI
     * @throws IllegalArgumentException if the LEI is invalid or the result is too long
     */
    public static String uti(String generatorLei, String suffix) {
        if (!ReportingIdentifiers.isValidLei(generatorLei)) {
            throw new IllegalArgumentException("invalid LEI: " + generatorLei);
        }
        String u = generatorLei + suffix;
        if (!isValidUtiShape(u)) {
            throw new IllegalArgumentException("UTI must be 1-52 upper-case alphanumerics: " + u);
        }
        return u;
    }

    /**
     * Whether a string has UTI shape (1 to 52 upper-case letters or digits).
     *
     * @param uti the candidate
     * @return true if well formed
     */
    public static boolean isValidUtiShape(String uti) {
        return uti.matches("[A-Z0-9]{1,52}");
    }

    /**
     * Sets any field by name.
     *
     * @param name field name
     * @param value value
     * @return this
     */
    public EmirRefitReportBuilder field(String name, String value) {
        f.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets the counterparties; the reporting counterparty is also the entity responsible
     * unless {@link #entityResponsible(String)} is called after.
     *
     * @param reportingLei reporting counterparty LEI
     * @param otherId other counterparty LEI (or client code when not a legal entity)
     * @param otherIsLegalEntity whether {@code otherId} is an LEI
     * @return this
     */
    public EmirRefitReportBuilder counterparties(String reportingLei, String otherId, boolean otherIsLegalEntity) {
        return field("reportingCounterpartyId", reportingLei).field("otherCounterpartyId", otherId)
                .field("otherCounterpartyIsLegalEntity", Boolean.toString(otherIsLegalEntity))
                .field("entityResponsibleForReporting", reportingLei);
    }

    /**
     * Sets the entity responsible for reporting.
     *
     * @param lei the LEI
     * @return this
     */
    public EmirRefitReportBuilder entityResponsible(String lei) {
        return field("entityResponsibleForReporting", lei);
    }

    /**
     * Sets the UTI.
     *
     * @param uti the UTI
     * @return this
     */
    public EmirRefitReportBuilder uti(String uti) {
        return field("uti", uti);
    }

    /**
     * Sets the reporting timestamp.
     *
     * @param t time
     * @return this
     */
    public EmirRefitReportBuilder reportingTimestamp(Instant t) {
        return field("reportingTimestamp", ReportingIdentifiers.formatTimestamp(t, Granularity.SECONDS));
    }

    /**
     * Sets the core trade terms.
     *
     * @param executed execution time
     * @param effective effective date
     * @param expiration expiration date
     * @param assetClass COMM, CRDT, CURR, EQUI or INTR
     * @param contractType e.g. SWAP, OPTN, FORW
     * @param direction BYER, SLLR, MAKE or TAKE
     * @param notional notional amount of leg 1
     * @param currency notional currency of leg 1
     * @return this
     */
    public EmirRefitReportBuilder terms(Instant executed, LocalDate effective, LocalDate expiration,
            String assetClass, String contractType, String direction, BigDecimal notional, String currency) {
        return field("executionTimestamp", ReportingIdentifiers.formatTimestamp(executed, Granularity.SECONDS))
                .field("effectiveDate", effective.toString())
                .field("expirationDate", expiration.toString())
                .field("assetClass", assetClass)
                .field("contractType", contractType)
                .field("direction", direction)
                .field("notional1", notional.toPlainString())
                .field("notionalCurrency1", currency);
    }

    /**
     * Sets the valuation block.
     *
     * @param amount valuation amount (signed)
     * @param currency valuation currency
     * @param at valuation time
     * @param method MTMA, MTMO or CCPV
     * @return this
     */
    public EmirRefitReportBuilder valuation(BigDecimal amount, String currency, Instant at, String method) {
        return field("valuationAmount", amount.toPlainString()).field("valuationCurrency", currency)
                .field("valuationTimestamp", ReportingIdentifiers.formatTimestamp(at, Granularity.SECONDS))
                .field("valuationMethod", method);
    }

    /**
     * Builds the report. Call {@link EmirRefitReport#validate()} before submitting.
     *
     * @return the report
     */
    public EmirRefitReport build() {
        return new EmirRefitReport(f);
    }
}

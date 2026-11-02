package com.quantfinlib.regreporting.emir;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import com.quantfinlib.regreporting.ReportingIdentifiers.Granularity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an {@link EmirRefitReport}.
 *
 * <p>Populated fields (camel-case names here, Refit field numbers in brackets). The
 * numbers were checked against the Annex of Commission Implementing Regulation (EU)
 * 2022/1860 (Table 1 for 1.x, Table 2 for 2.x) and against ESMA74-362-2683, sheet
 * Validations_Cpty_derivative, column C, which reproduces the same table: {@code reportingTimestamp} (1.1),
 * {@code reportSubmittingEntityId} (1.2), {@code entityResponsibleForReporting} (1.3),
 * {@code reportingCounterpartyId} (1.4), {@code otherCounterpartyIsLegalEntity} (1.8),
 * {@code otherCounterpartyId} (1.9), {@code direction} (1.17, BYER or SLLR),
 * {@code directionLeg1} and {@code directionLeg2} (1.18, 1.19, MAKE or TAKE),
 * {@code uti} (2.1), {@code isin} (2.7), {@code upi} (2.8), {@code contractType} (2.10),
 * {@code assetClass} (2.11), the valuation block {@code valuationAmount} (2.21),
 * {@code valuationCurrency} (2.22), {@code valuationTimestamp} (2.23),
 * {@code valuationMethod} (2.24), {@code cleared} (2.31), {@code centralCounterparty}
 * (2.33), {@code executionTimestamp} (2.42), {@code effectiveDate} (2.43),
 * {@code expirationDate} (2.44), {@code earlyTerminationDate} (2.45), {@code price}
 * (2.48), {@code notional1} (2.55), {@code notionalCurrency1} (2.56), {@code actionType}
 * (2.151), {@code eventType} (2.152) and {@code eventDate} (2.153). Collateral, margin,
 * legs 2, schedules and package fields are not populated; add them with
 * {@link #field(String, String)}.</p>
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
     * and the submitting entity unless {@link #entityResponsible(String)} or
     * {@link #submittingEntity(String)} is called after.
     *
     * @param reportingLei reporting counterparty LEI
     * @param otherId other counterparty LEI (or client code when not a legal entity)
     * @param otherIsLegalEntity whether {@code otherId} is an LEI
     * @return this
     */
    public EmirRefitReportBuilder counterparties(String reportingLei, String otherId, boolean otherIsLegalEntity) {
        return field("reportingCounterpartyId", reportingLei).field("otherCounterpartyId", otherId)
                .field("otherCounterpartyIsLegalEntity", Boolean.toString(otherIsLegalEntity))
                .field("entityResponsibleForReporting", reportingLei)
                .field("reportSubmittingEntityId", reportingLei);
    }

    /**
     * Sets the report submitting entity (field 1.2), when it differs from the reporting
     * counterparty set by {@link #counterparties}.
     *
     * @param lei the LEI
     * @return this
     */
    public EmirRefitReportBuilder submittingEntity(String lei) {
        return field("reportSubmittingEntityId", lei);
    }

    /**
     * Sets the event date (field 2.153).
     *
     * @param d the date
     * @return this
     */
    public EmirRefitReportBuilder eventDate(LocalDate d) {
        return field("eventDate", d.toString());
    }

    /**
     * Sets the leg directions (fields 1.18 and 1.19) and clears field 1.17; the workbook
     * wants either 1.17 or both legs (EMIR-VR-1017-01).
     *
     * @param leg1 MAKE or TAKE
     * @param leg2 the other of MAKE or TAKE
     * @return this
     */
    public EmirRefitReportBuilder legDirections(String leg1, String leg2) {
        return field("direction", "").field("directionLeg1", leg1).field("directionLeg2", leg2);
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
     * Sets the core trade terms. Also sets the event date (2.153) to the UTC date of
     * {@code executed} when it is still blank, because for NEWT and POSC the two must agree
     * (EMIR-VR-2153-02); call {@link #eventDate(LocalDate)} afterwards for other actions.
     *
     * @param executed execution time
     * @param effective effective date
     * @param expiration expiration date
     * @param assetClass COMM, CRDT, CURR, EQUI or INTR
     * @param contractType e.g. SWAP, OPTN, FORW
     * @param direction BYER or SLLR (field 1.17; use {@link #legDirections} for MAKE/TAKE)
     * @param notional notional amount of leg 1
     * @param currency notional currency of leg 1
     * @return this
     */
    public EmirRefitReportBuilder terms(Instant executed, LocalDate effective, LocalDate expiration,
            String assetClass, String contractType, String direction, BigDecimal notional, String currency) {
        if (f.getOrDefault("eventDate", "").isEmpty()) {
            f.put("eventDate", executed.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        }
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
     * @param method MTMA or MTMO (uncleared), CCPV (cleared)
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

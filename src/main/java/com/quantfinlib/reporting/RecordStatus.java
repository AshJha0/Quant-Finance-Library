package com.quantfinlib.reporting;

import java.util.Objects;

/**
 * Status of one transaction record in a competent authority's feedback, the
 * {@code ReportingRecordStatus1Code__1} codes of the ESMA auth.031.001.01 usage guideline
 * (ESMA65-8-2598 Annex 2, XSD 1.1.0: ACPT, PDNG, RCVD, RJCT; published reference) as used
 * in section 6.3.2 of the technical reporting instructions (ESMA65-8-2356, paragraph 106).
 * The codes ACPD and RJPD that ISO 20022 also defines are marked "Not used" there; after a
 * pending period the record is reported ACPT or RJCT.
 *
 * @since 3.0.0
 */
public enum RecordStatus {
    /** {@code ACPT}: the transaction has been accepted. */
    ACCEPTED("ACPT"),
    /** {@code PDNG}: validation is pending missing instrument reference data (CON-411, CON-471). */
    PENDING("PDNG"),
    /** {@code RCVD}: received and awaiting validation. */
    RECEIVED("RCVD"),
    /** {@code RJCT}: the transaction has been rejected. */
    REJECTED("RJCT");

    private final String code;

    RecordStatus(String code) {
        this.code = code;
    }

    /**
     * The four-letter ISO 20022 code.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /**
     * The status for a code.
     *
     * @param code ACPT, PDNG, RCVD or RJCT
     * @return the status
     * @throws IllegalArgumentException for any other code
     */
    public static RecordStatus ofCode(String code) {
        Objects.requireNonNull(code, "code");
        for (RecordStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("not an auth.031 record status: '" + code + "'");
    }
}

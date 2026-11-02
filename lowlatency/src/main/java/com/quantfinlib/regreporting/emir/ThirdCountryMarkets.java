package com.quantfinlib.regreporting.emir;

import java.time.LocalDate;

/**
 * The third-country markets considered equivalent to a regulated market, as reference data
 * the caller supplies for EMIR-VR-1003-01.
 *
 * <p><b>Plain idea.</b> Field 1.3 (entity responsible for reporting) is left blank when the
 * trade was done on a regulated market, or on a non-EEA market the EU treats as equivalent
 * to one. Whether a non-EEA venue is equivalent is a legal decision, not a property of its
 * MIC, so ISO 10383 cannot answer it; this interface lets the caller answer it.</p>
 *
 * <p><b>Formal statement.</b> ESMA74-362-2683, sheet {@code Validations_Cpty_derivative},
 * row 7, column AC, condition 1: 1.3 is populated unless 2.41 holds a MIC of a venue that
 * was a regulated market or a third-country market considered equivalent to one at the
 * time the derivative was concluded. The equivalence regime is Article 2a of Regulation
 * (EU) No 648/2012 (EMIR): a Commission implementing decision, with ESMA publishing the list
 * of such markets on its website. That list is not on disk and is not bundled; supply it
 * from your own copy. {@code onDate} is the date part of field 2.42 (execution timestamp,
 * UTC).</p>
 *
 * <p><b>Worked example</b> (own test): with a list holding {@code XNYS} from 2020-01-01,
 * a NEWT executed on XNYS on 2026-03-02 must leave 1.3 blank; with an empty list it must
 * populate 1.3.</p>
 *
 * @since 4.0.0
 */
@FunctionalInterface
public interface ThirdCountryMarkets {
    /**
     * Whether the market with this MIC was considered equivalent on the date.
     *
     * @param mic the ISO 10383 MIC of field 2.41
     * @param onDate the date of conclusion (date part of 2.42, UTC)
     * @return true when the market was on the caller's equivalence list that day
     */
    boolean isEquivalent(String mic, LocalDate onDate);
}

package com.quantfinlib.regreporting.emir;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The official ISO 4217 currency list, as far as the EMIR rules EMIR-VR-2056-01 and
 * EMIR-VR-2022-01 need it ("ISO 4217 Currency Code (official list only)", ESMA74-362-2683,
 * sheet Validations_Cpty_derivative, rows 82 and 47).
 *
 * <p>The library does not ship the list: ISO 4217 is maintained by SIX on behalf of ISO and
 * changes several times a year, so the caller loads the current "List one" (active codes)
 * and passes the codes in. {@link EmirRefitReport#validate()} already checks the shape (three
 * upper-case letters) and the excluded special codes (EMIR-VR-2056-02, -2022-02).</p>
 *
 * @since 3.1.0
 */
@FunctionalInterface
public interface CurrencyRegistry {
    /**
     * Whether the code is on the official list today.
     *
     * @param code three-letter code
     * @return true if listed
     */
    boolean isListed(String code);

    /**
     * A registry over a fixed set of codes, for example parsed from SIX "List one".
     *
     * @param codes the listed codes (upper-cased here)
     * @return the registry
     */
    static CurrencyRegistry of(Collection<String> codes) {
        Set<String> s = new TreeSet<>();
        for (String c : codes) {
            s.add(c.trim().toUpperCase(Locale.ROOT));
        }
        Set<String> frozen = Set.copyOf(s);
        return frozen::contains;
    }
}

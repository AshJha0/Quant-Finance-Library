package com.quantfinlib.reporting.registry;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * A dated code list, such as ISO 4217 currencies or ISO 3166 countries, each code valid
 * from one date up to and including another. ESMA rules such as CON-080 ("an ISO 3166
 * country code that was valid at the trading date") and CON-340 (an ISO 4217 code
 * "active at the trading date") read it (ESMA65-8-2594 Annex 1).
 *
 * <p>The library ships no code list. ISO maintains the lists and the caller loads them;
 * {@link #of(Map)} builds a registry from a map of code to validity window.
 *
 * @since 2.4.0
 */
@FunctionalInterface
public interface CodeRegistry {
    /**
     * Whether the code was valid on a date.
     *
     * @param code the code
     * @param date the date
     * @return true if valid
     */
    boolean isValidOn(String code, LocalDate date);

    /**
     * A validity window, inclusive at both ends; a null end is open.
     *
     * @param from first valid date, or null
     * @param to last valid date, or null
     */
    record Window(LocalDate from, LocalDate to) {
        /**
         * Whether the date is inside the window.
         *
         * @param d the date
         * @return true if inside
         */
        public boolean contains(LocalDate d) {
            return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
        }

        /**
         * A window open at both ends.
         *
         * @return the window
         */
        public static Window always() {
            return new Window(null, null);
        }
    }

    /**
     * A registry from a code-to-window map.
     *
     * @param windows code to validity window
     * @return the registry
     */
    static CodeRegistry of(Map<String, Window> windows) {
        Map<String, Window> m = Map.copyOf(windows);
        return (code, date) -> {
            Window w = m.get(code);
            return w != null && w.contains(Objects.requireNonNull(date, "date"));
        };
    }
}

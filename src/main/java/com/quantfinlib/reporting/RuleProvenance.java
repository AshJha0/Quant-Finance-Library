package com.quantfinlib.reporting;

import java.util.Objects;

/**
 * Where one validation rule comes from and how far it has been checked.
 *
 * <p>The plain idea: a check that says "field X must be one of A, B, C" is only as good as
 * the text it was written from. Each rule therefore carries the citation it was written
 * against and an evidence class, so a reader can tell a rule copied from a published table
 * apart from one the library inferred.</p>
 *
 * @param id short rule identifier used by this library (for example {@code EMIR-R03})
 * @param rule what the rule checks, in one sentence
 * @param citation the article, annex table, field number or spec section the rule was
 *        written against, as precise as the evidence allows
 * @param evidence evidence class of the rule
 */
public record RuleProvenance(String id, String rule, String citation, Evidence evidence) {

    /** Evidence class of a rule. */
    public enum Evidence {
        /** Checked against the published text held on disk. */
        PUBLISHED_REFERENCE,
        /** The library's own interpretation; the javadoc says why. */
        LIBRARY_READING,
        /** Written from memory of the source; not checked against the text. */
        UNCONFIRMED
    }

    /**
     * Validates the components.
     *
     * @param id rule id, not blank
     * @param rule rule text, not blank
     * @param citation citation, not blank
     * @param evidence evidence class
     */
    public RuleProvenance {
        Objects.requireNonNull(evidence, "evidence");
        for (String s : new String[] {id, rule, citation}) {
            if (Objects.requireNonNull(s, "text").isBlank()) {
                throw new IllegalArgumentException("blank provenance text");
            }
        }
    }
}

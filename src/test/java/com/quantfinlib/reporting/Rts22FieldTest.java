package com.quantfinlib.reporting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Rts22FieldTest {
    @Test
    void numbersRunOneToSixtyFiveInAnnexOrder() {
        Rts22Field[] all = Rts22Field.values();
        assertEquals(65, all.length);
        for (int i = 0; i < all.length; i++) {
            assertEquals(i + 1, all[i].number(), all[i].name());
        }
    }

    @Test
    void field58LabelMatchesAnnexI() {
        // Delegated Regulation (EU) 2017/590, Annex I, Table 2, field 58 (published reference).
        assertEquals("Country of the branch supervising the person responsible for the investment decision",
                Rts22Field.INVESTMENT_DECISION_BRANCH_COUNTRY.label());
        assertEquals(58, Rts22Field.INVESTMENT_DECISION_BRANCH_COUNTRY.number());
    }
}

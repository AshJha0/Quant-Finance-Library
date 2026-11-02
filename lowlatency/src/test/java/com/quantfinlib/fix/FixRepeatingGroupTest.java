package com.quantfinlib.fix;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixRepeatingGroupTest {

    private static final FixDictionary D44 = FixDictionary.forVersion(FixVersion.FIX_4_4);

    private static FixGroup party(String id, String role, FixGroup... subs) {
        FixGroup.Builder b = FixGroup.builder().field(448, id).field(447, "D").field(452, role);
        if (subs.length > 0) {
            b.group(FixDictionary.NO_PARTY_SUB_IDS, List.of(subs));
        }
        return b.build();
    }

    private static FixMessage roundTrip(FixVersion v, FixMessage.Builder b) {
        return FixMessage.parse(b.encode(v, "A", "B", 1, "20261102-10:00:00.000", false, null));
    }

    /** Raw message from a body with '|' for SOH, framed as FIX 4.4. */
    private static FixMessage raw(String msgType, String body) {
        FixMessage.Builder b = FixMessage.builder(msgType);
        for (String f : body.split("\\|")) {
            int eq = f.indexOf('=');
            b.field(Integer.parseInt(f.substring(0, eq)), f.substring(eq + 1));
        }
        return roundTrip(FixVersion.FIX_4_4, b);
    }

    @Test
    void partiesWithNestedSubIdsRoundTrip() {
        FixGroup sub = FixGroup.builder().field(523, "DESK1").field(803, "4").build();
        List<FixGroup> parties = List.of(party("BRKR", "1", sub), party("CLNT", "3"));
        FixMessage m = roundTrip(FixVersion.FIX_4_4, FixTestKit.order("o1")
                .group(FixDictionary.PARTIES, parties).field(FixMessage.TEXT, "after"));
        D44.validate(m);
        List<FixGroup> back = D44.readGroup(m, FixDictionary.NO_PARTY_IDS);
        assertEquals(parties, back);
        assertEquals("DESK1", back.get(0).group(FixDictionary.NO_PARTY_SUB_IDS).get(0).get(523));
        assertTrue(back.get(1).group(FixDictionary.NO_PARTY_SUB_IDS).isEmpty());
        assertEquals("after", m.getString(FixMessage.TEXT));   // group ended at a non-member
    }

    @Test
    void legsWithNestedPartiesRoundTrip() {
        FixGroup np = FixGroup.builder().field(524, "X").field(538, "1").build();
        FixGroup leg1 = FixGroup.builder().field(600, "EUR").field(624, "1").field(623, "1")
                .group(FixDictionary.NO_NESTED_PARTY_IDS, List.of(np)).build();
        FixGroup leg2 = FixGroup.builder().field(600, "USD").field(624, "2").build();
        FixMessage m = roundTrip(FixVersion.FIX_5_0_SP2, FixMessage.builder("AB")
                .field(FixMessage.CL_ORD_ID, "m1").group(FixDictionary.LEGS, List.of(leg1, leg2)));
        assertEquals("FIXT.1.1", m.beginString());
        assertEquals(FixVersion.FIX_5_0_SP2, m.version());
        FixDictionary d = FixDictionary.forVersion(FixVersion.FIX_5_0_SP2);
        d.validate(m);
        assertEquals(List.of(leg1, leg2), d.readGroup(m, FixDictionary.NO_LEGS));
    }

    @Test
    void marketDataGroupsDependOnMessageType() {
        FixMessage snap = raw("W", "55=EURUSD|268=2|269=0|270=1.1|271=5|269=1|270=1.2|271=7");
        assertEquals(2, D44.readGroup(snap, FixDictionary.NO_MD_ENTRIES).size());
        FixMessage inc = raw("X", "268=2|279=0|269=0|55=EURUSD|270=1.1|279=2|269=1|55=EURUSD");
        List<FixGroup> e = D44.readGroup(inc, FixDictionary.NO_MD_ENTRIES);
        assertEquals("2", e.get(1).get(279));
        FixMessage req = raw("V", "262=r|263=1|264=0|267=2|269=0|269=1|146=2|55=EURUSD|55=GBPUSD");
        D44.validate(req);
        assertEquals("GBPUSD", D44.readGroup(req, FixDictionary.NO_RELATED_SYM).get(1).get(55));
        // 5.0 SP2 incremental entries may carry MDPriceLevel(1023); 4.4 ends the group there.
        FixMessage lvl = raw("X", "268=1|279=0|269=0|1023=3|270=1.1");
        assertEquals("3", FixDictionary.forVersion(FixVersion.FIX_5_0_SP2)
                .readGroup(lvl, FixDictionary.NO_MD_ENTRIES).get(0).get(1023));
        assertEquals(null, D44.readGroup(lvl, FixDictionary.NO_MD_ENTRIES).get(0).get(1023));
    }

    @Test
    void countHigherThanInstancesIsRejected() {
        FixMessage m = raw("D", "11=a|453=2|448=X|452=1|58=t");
        FixParseException e = assertThrows(FixParseException.class, () -> D44.validate(m));
        assertEquals(FixDictionary.REJECT_INCORRECT_NUM_IN_GROUP, e.rejectReason());
        assertEquals(453, e.refTagId());
        assertFalse(e.garbled());
        assertEquals(1, e.seqNum());
    }

    @Test
    void countLowerThanInstancesIsRejected() {
        FixMessage m = raw("D", "11=a|453=1|448=X|452=1|448=Y|452=3");
        FixParseException e = assertThrows(FixParseException.class,
                () -> D44.readGroup(m, FixDictionary.NO_PARTY_IDS));
        assertEquals(FixDictionary.REJECT_INCORRECT_NUM_IN_GROUP, e.rejectReason());
    }

    @Test
    void instanceNotStartingWithDelimiterIsRejected() {
        FixMessage m = raw("D", "11=a|453=1|452=1|448=X");
        FixParseException e = assertThrows(FixParseException.class, () -> D44.validate(m));
        assertEquals(FixDictionary.REJECT_GROUP_FIELDS_OUT_OF_ORDER, e.rejectReason());
        assertEquals(452, e.refTagId());
    }

    @Test
    void repeatedFieldInInstanceAndBadCountAreRejected() {
        FixMessage rep = raw("D", "11=a|453=1|448=X|452=1|452=3");
        assertEquals(FixDictionary.REJECT_GROUP_FIELDS_OUT_OF_ORDER, assertThrows(
                FixParseException.class, () -> D44.validate(rep)).rejectReason());
        FixMessage bad = raw("D", "11=a|453=two|448=X");
        assertEquals(FixDictionary.REJECT_INCORRECT_NUM_IN_GROUP, assertThrows(
                FixParseException.class, () -> D44.validate(bad)).rejectReason());
        FixMessage twice = raw("D", "453=1|448=X|11=a|453=1|448=Y");
        assertThrows(FixParseException.class, () -> D44.validate(twice));
        FixMessage nestedShort = raw("D", "453=1|448=X|802=2|523=S");
        assertEquals(802, assertThrows(FixParseException.class,
                () -> D44.validate(nestedShort)).refTagId());
    }

    @Test
    void zeroCountAndAbsentGroupAreEmpty() {
        assertTrue(D44.readGroup(raw("D", "11=a|453=0"), FixDictionary.NO_PARTY_IDS).isEmpty());
        assertTrue(D44.readGroup(raw("D", "11=a"), FixDictionary.NO_PARTY_IDS).isEmpty());
    }

    @Test
    void encoderRefusesMalformedInstances() {
        FixMessage.Builder b = FixMessage.builder("D");
        FixGroup noDelim = FixGroup.builder().field(452, "1").build();
        assertThrows(IllegalArgumentException.class,
                () -> b.group(FixDictionary.PARTIES, List.of(noDelim)));
        FixGroup stranger = FixGroup.builder().field(448, "X").field(55, "EURUSD").build();
        assertThrows(IllegalArgumentException.class,
                () -> b.group(FixDictionary.PARTIES, List.of(stranger)));
        FixGroup badNest = FixGroup.builder().field(448, "X")
                .group(539, List.of(FixGroup.builder().field(524, "Y").build())).build();
        assertThrows(IllegalArgumentException.class,
                () -> b.group(FixDictionary.PARTIES, List.of(badNest)));
        assertThrows(IllegalArgumentException.class,
                () -> FixGroup.builder().field(448, "X").field(448, "Y"));
    }

    @Test
    void fix42HasNoPartiesOrLegsButCustomGroupsCanBeAdded() {
        FixDictionary d42 = FixDictionary.forVersion(FixVersion.FIX_4_2);
        FixMessage m = raw("D", "11=a|453=1|448=X");
        assertThrows(IllegalArgumentException.class,
                () -> d42.readGroup(m, FixDictionary.NO_PARTY_IDS));
        d42.validate(m);   // 453 is just a field in 4.2
        FixDictionary custom = d42.with("D", FixDictionary.PARTIES);
        assertEquals("X", custom.readGroup(m, 453).get(0).get(448));
        assertThrows(IllegalArgumentException.class,
                () -> d42.readGroup(m, FixDictionary.NO_PARTY_IDS));   // original unchanged
    }

    @Test
    void parseAcceptsEachVersionAndRejectsUnknownBeginString() {
        for (FixVersion v : FixVersion.values()) {
            FixMessage m = roundTrip(v, FixTestKit.order("o"));
            assertEquals(v, m.version());
            assertEquals(v, FixVersion.fromBeginString(m.beginString()));
        }
        byte[] wire = FixTestKit.order("o").encode("A", "B", 1, "20261102-10:00:00.000");
        String s = new String(wire, StandardCharsets.ISO_8859_1).replace("FIX.4.4", "FIX.4.1");
        assertTrue(assertThrows(FixParseException.class, () -> FixMessage.parse(
                s.getBytes(StandardCharsets.ISO_8859_1))).garbled());
        assertEquals("FIX.4.4", FixMessage.parse(wire).beginString());   // 2.1 default
    }

    @Test
    void versionFieldDifferences() {
        assertEquals('F', FixVersion.FIX_4_2.normalizeExecType('2'));
        assertEquals('F', FixVersion.FIX_4_2.normalizeExecType('1'));
        assertEquals('2', FixVersion.FIX_4_4.normalizeExecType('2'));
        assertTrue(FixVersion.FIX_4_2.requiresExecTransType());
        assertFalse(FixVersion.FIX_4_2.hasParties());
        assertTrue(FixVersion.FIX_5_0_SP2.isFixt());
        assertEquals("9", FixVersion.FIX_5_0_SP2.applVerId());
        assertEquals(null, FixVersion.fromBeginString("FIX.4.3"));
    }

    @Test
    void groupDefRejectsDuplicateTags() {
        assertThrows(IllegalArgumentException.class, () -> FixGroupDef.of("x", 1, 2, 3, 3));
        assertThrows(IllegalArgumentException.class,
                () -> FixGroupDef.of("x", 1, 2, 802).withNested(FixDictionary.PARTY_SUB_IDS));
    }
}

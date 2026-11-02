package com.quantfinlib.fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repeating-group dictionary for one FIX version: which NoXxx count fields
 * exist, the delimiter each instance starts with, the member fields and the
 * nested groups. {@link #readGroup} and {@link #validate} use it to read
 * groups from a parsed {@link FixMessage} and to reject a malformed one;
 * {@link FixMessage.Builder#group} uses a {@link FixGroupDef} to write one.
 *
 * <p>Plain idea: {@link FixMessage} keeps fields as a flat list, which is
 * right for the session layer but loses the instance boundaries of a group.
 * The dictionary restores them, and catches the two faults a flat parser
 * cannot see: a count that disagrees with the instances present, and an
 * instance whose fields do not start with the delimiter.
 *
 * <p>Formal statement: a fault is reported as a {@link FixParseException}
 * with SessionRejectReason(373) 16 (IncorrectNumInGroupCountForRepeatingGroup)
 * for a count mismatch or a non-numeric count, and 15
 * (RepeatingGroupFieldsOutOfOrder) for an instance that does not start with
 * its delimiter or repeats a field. Reason codes from FIX 4.4 Vol 2 and
 * FIXT 1.1, field 373 (evidence class: published reference). The member lists
 * below are the subset of each group that the library's messages use, taken
 * from the FIX 4.2, 4.4 and 5.0 SP2 message tables; a field outside the list
 * ends the group, as it would on a counterparty engine with the same
 * dictionary.
 *
 * <p>Version differences encoded here: FIX 4.2 has no Parties component
 * (NoPartyIDs(453)) and no multileg NoLegs(555), both added in FIX 4.3;
 * FIX 5.0 SP2 adds MDPriceLevel(1023) to incremental market-data entries.
 * MarketDataIncrementalRefresh (35=X) entries start with MDUpdateAction(279),
 * snapshot (35=W) entries with MDEntryType(269), so NoMDEntries(268) is
 * defined per message type.
 *
 * <p>Example: {@code FixDictionary.forVersion(FixVersion.FIX_4_4)
 * .readGroup(m, FixDictionary.NO_PARTY_IDS)} on a message carrying
 * {@code 453=1|448=BRKR|447=D|452=1|} returns one {@link FixGroup} with
 * fields {448=BRKR, 447=D, 452=1}.
 */
public final class FixDictionary {

    public static final int NO_RELATED_SYM = 146;
    public static final int NO_MD_ENTRY_TYPES = 267;
    public static final int NO_MD_ENTRIES = 268;
    public static final int NO_PARTY_IDS = 453;
    public static final int NO_NESTED_PARTY_IDS = 539;
    public static final int NO_LEGS = 555;
    public static final int NO_PARTY_SUB_IDS = 802;

    /** SessionRejectReason 15: RepeatingGroupFieldsOutOfOrder. */
    public static final int REJECT_GROUP_FIELDS_OUT_OF_ORDER = 15;
    /** SessionRejectReason 16: IncorrectNumInGroupCountForRepeatingGroup. */
    public static final int REJECT_INCORRECT_NUM_IN_GROUP = 16;

    /** Party sub-ID group: PartySubID(523), PartySubIDType(803). */
    public static final FixGroupDef PARTY_SUB_IDS =
            FixGroupDef.of("NoPartySubIDs", NO_PARTY_SUB_IDS, 523, 803);
    /** Parties component: PartyID(448), PartyIDSource(447), PartyRole(452), nested sub-IDs. */
    public static final FixGroupDef PARTIES =
            FixGroupDef.of("NoPartyIDs", NO_PARTY_IDS, 448, 447, 452).withNested(PARTY_SUB_IDS);
    /** Nested parties inside a leg: NestedPartyID(524), source(525), role(538). */
    public static final FixGroupDef NESTED_PARTIES =
            FixGroupDef.of("NoNestedPartyIDs", NO_NESTED_PARTY_IDS, 524, 525, 538);
    /** Instrument legs: LegSymbol(600) first, nested NoNestedPartyIDs. */
    public static final FixGroupDef LEGS = FixGroupDef.of("NoLegs", NO_LEGS, 600,
            602, 603, 608, 610, 623, 624, 566, 654, 687).withNested(NESTED_PARTIES);
    /** MarketDataRequest instruments: Symbol(55) first. */
    public static final FixGroupDef RELATED_SYM = FixGroupDef.of("NoRelatedSym", NO_RELATED_SYM,
            55, 65, 48, 22, 167, 200, 207);
    /** MarketDataRequest entry types: MDEntryType(269). */
    public static final FixGroupDef MD_ENTRY_TYPES =
            FixGroupDef.of("NoMDEntryTypes", NO_MD_ENTRY_TYPES, 269);
    /** Snapshot (35=W) entries: MDEntryType(269) first. */
    public static final FixGroupDef MD_ENTRIES_SNAPSHOT = FixGroupDef.of("NoMDEntries",
            NO_MD_ENTRIES, 269, 270, 15, 271, 272, 273, 276, 277, 278, 290, 299, 336, 346, 58);
    /** Incremental (35=X) entries up to FIX 4.4: MDUpdateAction(279) first. */
    public static final FixGroupDef MD_ENTRIES_INCREMENTAL = FixGroupDef.of("NoMDEntries",
            NO_MD_ENTRIES, 279, 285, 269, 278, 280, 55, 48, 22, 270, 15, 271, 272, 273, 290,
            346, 58);
    /** Incremental (35=X) entries in FIX 5.0 SP2: adds MDPriceLevel(1023). */
    public static final FixGroupDef MD_ENTRIES_INCREMENTAL_50 = FixGroupDef.of("NoMDEntries",
            NO_MD_ENTRIES, 279, 285, 269, 278, 280, 55, 48, 22, 270, 15, 271, 272, 273, 290,
            346, 58, 1023);

    private static final String ANY = "*";
    private static final Map<FixVersion, FixDictionary> STANDARD = new EnumMap<>(FixVersion.class);

    static {
        for (FixVersion v : FixVersion.values()) {
            FixDictionary d = new FixDictionary(v);
            d.add(ANY, RELATED_SYM);
            d.add(ANY, MD_ENTRY_TYPES);
            d.add(ANY, MD_ENTRIES_SNAPSHOT);
            d.add("W", MD_ENTRIES_SNAPSHOT);
            d.add("X", v == FixVersion.FIX_5_0_SP2 ? MD_ENTRIES_INCREMENTAL_50
                    : MD_ENTRIES_INCREMENTAL);
            if (v.hasParties()) {
                d.add(ANY, PARTIES);
                d.add(ANY, LEGS);
            }
            STANDARD.put(v, d);
        }
    }

    private final FixVersion version;
    private final Map<String, Map<Integer, FixGroupDef>> groups = new HashMap<>();

    private FixDictionary(FixVersion version) {
        this.version = version;
    }

    /** The standard dictionary for {@code version}. */
    public static FixDictionary forVersion(FixVersion version) {
        return STANDARD.get(version);
    }

    /**
     * A copy of this dictionary with {@code def} added for {@code msgType}
     * ({@code null} for every message type), for venue-specific groups.
     */
    public FixDictionary with(String msgType, FixGroupDef def) {
        FixDictionary d = new FixDictionary(version);
        groups.forEach((k, v) -> d.groups.put(k, new HashMap<>(v)));
        d.add(msgType == null ? ANY : msgType, def);
        return d;
    }

    private void add(String msgType, FixGroupDef def) {
        groups.computeIfAbsent(msgType, k -> new HashMap<>()).put(def.countTag(), def);
    }

    public FixVersion version() {
        return version;
    }

    /** The group counted by {@code countTag} in {@code msgType}, or null when not defined. */
    public FixGroupDef group(String msgType, int countTag) {
        Map<Integer, FixGroupDef> forType = groups.get(msgType);
        FixGroupDef def = forType == null ? null : forType.get(countTag);
        if (def == null) {
            def = groups.getOrDefault(ANY, Map.of()).get(countTag);
        }
        return def;
    }

    /**
     * Reads the top-level group counted by {@code countTag}; an empty list
     * when the message does not carry it.
     *
     * @throws IllegalArgumentException when the dictionary has no such group
     * @throws FixParseException        for a count mismatch or fields out of order
     */
    public List<FixGroup> readGroup(FixMessage m, int countTag) {
        FixGroupDef def = group(m.msgType(), countTag);
        if (def == null) {
            throw new IllegalArgumentException("no repeating group " + countTag + " for "
                    + version + " 35=" + m.msgType());
        }
        int n = m.fieldCount();
        for (int i = 0; i < n; i++) {
            if (m.tagAt(i) == countTag) {
                List<FixGroup> out = new ArrayList<>();
                read(m, def, i, out);
                return Collections.unmodifiableList(out);
            }
        }
        return List.of();
    }

    /**
     * Checks every top-level group of {@code m} that this dictionary knows:
     * counts agree with instances, instances start with their delimiter,
     * no field repeats within an instance, and no count field appears twice.
     *
     * @throws FixParseException on the first fault
     */
    public void validate(FixMessage m) {
        int n = m.fieldCount();
        Set<Integer> seen = new HashSet<>();
        int i = 0;
        while (i < n) {
            int tag = m.tagAt(i);
            FixGroupDef def = group(m.msgType(), tag);
            if (def == null) {
                i++;
                continue;
            }
            if (!seen.add(tag)) {
                throw fault(m, REJECT_INCORRECT_NUM_IN_GROUP, tag,
                        def.name() + "(" + tag + ") appears twice");
            }
            i = read(m, def, i, new ArrayList<>());
        }
    }

    /** Reads the group whose count field is at index {@code at}; returns the index after it. */
    private static int read(FixMessage m, FixGroupDef def, int at, List<FixGroup> out) {
        int count = parseCount(m.valueAt(at));
        if (count < 0) {
            throw fault(m, REJECT_INCORRECT_NUM_IN_GROUP, def.countTag(),
                    def.name() + "(" + def.countTag() + ") count not numeric: " + m.valueAt(at));
        }
        int n = m.fieldCount();
        int i = at + 1;
        for (int k = 0; k < count; k++) {
            if (i >= n || m.tagAt(i) != def.delimiterTag()) {
                boolean inGroup = i < n && (def.isField(m.tagAt(i))
                        || def.nestedGroup(m.tagAt(i)) != null);
                if (inGroup) {
                    throw fault(m, REJECT_GROUP_FIELDS_OUT_OF_ORDER, m.tagAt(i),
                            def.name() + " instance " + (k + 1) + " starts with "
                                    + m.tagAt(i) + ", not delimiter " + def.delimiterTag());
                }
                throw fault(m, REJECT_INCORRECT_NUM_IN_GROUP, def.countTag(),
                        def.name() + "(" + def.countTag() + ")=" + count + " but "
                                + k + " instance(s) present");
            }
            FixGroup.Builder b = FixGroup.builder().field(def.delimiterTag(), m.valueAt(i));
            Set<Integer> used = new HashSet<>();
            used.add(def.delimiterTag());
            i++;
            while (i < n) {
                int t = m.tagAt(i);
                if (t == def.delimiterTag()) {
                    break;
                }
                FixGroupDef sub = def.nestedGroup(t);
                boolean plain = def.isField(t);
                if (!plain && sub == null) {
                    break;
                }
                if (!used.add(t)) {
                    throw fault(m, REJECT_GROUP_FIELDS_OUT_OF_ORDER, t, def.name()
                            + " instance " + (k + 1) + " repeats tag " + t);
                }
                if (plain) {
                    b.field(t, m.valueAt(i));
                    i++;
                } else {
                    List<FixGroup> inner = new ArrayList<>();
                    i = read(m, sub, i, inner);
                    b.group(t, inner);
                }
            }
            out.add(b.build());
        }
        if (i < n && m.tagAt(i) == def.delimiterTag()) {
            throw fault(m, REJECT_INCORRECT_NUM_IN_GROUP, def.countTag(),
                    def.name() + "(" + def.countTag() + ")=" + count
                            + " but more instances follow");
        }
        return i;
    }

    private static int parseCount(String s) {
        if (s.isEmpty() || s.length() > 6) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static FixParseException fault(FixMessage m, int reason, int tag, String text) {
        long seq = -1;
        try {
            seq = Long.parseLong(m.getString(FixMessage.MSG_SEQ_NUM, "-1"));
        } catch (NumberFormatException e) {
            seq = -1;   // the session layer has already checked MsgSeqNum
        }
        return FixParseException.field(reason, tag, text, seq, m.msgType());
    }
}

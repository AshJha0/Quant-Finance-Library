package com.quantfinlib.fix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The shape of one FIX repeating group.
 *
 * <p>Plain idea: a repeating group is a count field, NoXxx, followed by that
 * many instances. Each instance starts with the same delimiter field and then
 * carries any of its member fields, some of which may themselves be the count
 * field of a nested group. Without knowing the shape, a flat tag=value
 * parser cannot tell where one instance ends and the next begins.
 *
 * <p>Formal statement (FIX 4.4 Vol 1, "Repeating Groups"; same rule in
 * FIX 4.2 and FIXT 1.1; evidence class: published reference): the count
 * field gives the number of instances; the first field of each instance is
 * the delimiter and must be present; the other fields of an instance follow
 * the delimiter, at most once each, before the next delimiter or the first
 * field outside the group.
 *
 * <p>Example: NoPartyIDs(453) with delimiter PartyID(448) and members
 * PartyIDSource(447), PartyRole(452) reads
 * {@code 453=2|448=ABC|447=D|452=1|448=DEF|447=D|452=3|} as two instances.
 *
 * @param name         readable name, for messages
 * @param countTag     the NoXxx field
 * @param delimiterTag first field of every instance
 * @param memberTags   other plain fields an instance may carry, in canonical order
 * @param nested       groups that may appear inside an instance
 */
public record FixGroupDef(String name, int countTag, int delimiterTag, List<Integer> memberTags,
                          List<FixGroupDef> nested) {

    public FixGroupDef {
        Objects.requireNonNull(name, "name");
        memberTags = List.copyOf(memberTags);
        nested = List.copyOf(nested);
        Set<Integer> seen = new LinkedHashSet<>();
        seen.add(delimiterTag);
        for (int t : memberTags) {
            if (!seen.add(t)) {
                throw new IllegalArgumentException(name + ": tag " + t + " listed twice");
            }
        }
        for (FixGroupDef g : nested) {
            if (!seen.add(g.countTag())) {
                throw new IllegalArgumentException(name + ": nested count tag "
                        + g.countTag() + " clashes with a member");
            }
        }
    }

    /** A group without nested groups. */
    public static FixGroupDef of(String name, int countTag, int delimiterTag, int... members) {
        List<Integer> list = new ArrayList<>(members.length);
        for (int m : members) {
            list.add(m);
        }
        return new FixGroupDef(name, countTag, delimiterTag, list, List.of());
    }

    /** This group with {@code group} allowed inside each instance. */
    public FixGroupDef withNested(FixGroupDef group) {
        List<FixGroupDef> n = new ArrayList<>(nested);
        n.add(group);
        return new FixGroupDef(name, countTag, delimiterTag, memberTags, n);
    }

    /** The nested group whose count field is {@code tag}, or null. */
    public FixGroupDef nestedGroup(int tag) {
        for (FixGroupDef g : nested) {
            if (g.countTag() == tag) {
                return g;
            }
        }
        return null;
    }

    /** True when {@code tag} may appear directly in an instance (delimiter or member). */
    public boolean isField(int tag) {
        return tag == delimiterTag || memberTags.contains(tag);
    }
}

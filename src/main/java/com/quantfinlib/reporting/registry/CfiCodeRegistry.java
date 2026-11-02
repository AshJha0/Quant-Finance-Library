package com.quantfinlib.reporting.registry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The ISO 10962 CFI code table, position by position, for ESMA rule CON-430 ("the reported
 * CFI should be an allowed CFI according to the ISO 10962", ESMA65-8-2594 Annex 1 v1.7,
 * rule 222).
 *
 * <p>Plain idea: a CFI code is six letters. The first two name a category and group; each
 * of the last four is an attribute whose allowed letters depend on the group. A code is
 * allowed when its group exists and every attribute letter is one the standard lists for
 * that position of that group.
 *
 * <p>Source: the ISO 10962 code list workbook published by SIX, the ISO 10962 maintenance
 * agency (file {@code cfi-20210507-current.xlsx}, ISO 10962:2021). Its introduction sheet
 * says "Copyright© ISO 2021" and gives no redistribution licence, so the table is not
 * shipped: point {@link #fromSixXlsx(Path)} at your own downloaded copy. The loader reads
 * the workbook layout as published (published reference, checked against that file): one
 * sheet per group named {@code <category><group>XXXX} (for example {@code ESXXXX}, 78 in
 * that file). On each, a row whose column A reads {@code First attribute} (or Second, Third,
 * Fourth) opens a section, and each following row with a single capital letter in column B
 * is an allowed letter for that position, until column A holds other text. The code
 * patterns of column D are not used: in a few groups (ED, CM) they do not match the
 * letters listed.
 *
 * <p>The letter X ("not applicable/undefined") is accepted only where the list names it
 * for that position. {@link #withUndefinedAllowed()} accepts X in every attribute, for
 * data sources that use X for an unknown attribute; that leniency is the library's
 * choice, not the list's.
 *
 * <p>Worked example ({@code CfiCodeRegistryTest}, hand-made workbook in the SIX layout):
 * with group ES listing V/N/R/E, T/U, O/P/F and B/R/N/M, {@code ESVUFR} is allowed,
 * {@code ESXUFR} is not (X is not listed for the first attribute), and {@code EZVUFR} is not
 * (no group EZ).
 *
 * @since 3.0.0
 */
public final class CfiCodeRegistry implements Predicate<String> {
    private static final Pattern GROUP_SHEET = Pattern.compile("[A-Z]{2}XXXX");
    private static final Pattern SECTION = Pattern.compile("(First|Second|Third|Fourth) attribute.*");
    private static final List<String> ORDINALS = List.of("First", "Second", "Third", "Fourth");
    private static final long MAX_ENTRY_BYTES = 64L << 20;

    /** Group (two letters) to the allowed letters of attributes 1 to 4. */
    private final Map<String, List<Set<Character>>> groups;
    private final boolean undefinedAllowed;

    private CfiCodeRegistry(Map<String, List<Set<Character>>> groups, boolean undefinedAllowed) {
        this.groups = groups;
        this.undefinedAllowed = undefinedAllowed;
    }

    /**
     * Loads the SIX ISO 10962 workbook (.xlsx).
     *
     * @param file the workbook
     * @return the table
     * @throws IOException on a read error or when no group sheet is found
     */
    public static CfiCodeRegistry fromSixXlsx(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return fromSixXlsx(in);
        }
    }

    /**
     * Loads the SIX ISO 10962 workbook from a stream.
     *
     * @param in the .xlsx content
     * @return the table
     * @throws IOException on a read error or when no group sheet is found
     */
    public static CfiCodeRegistry fromSixXlsx(InputStream in) throws IOException {
        Map<String, byte[]> parts = unzip(Objects.requireNonNull(in, "in"));
        try {
            List<String> strings = sharedStrings(parts.get("xl/sharedStrings.xml"));
            Map<String, String> rels = rels(require(parts, "xl/_rels/workbook.xml.rels"));
            Map<String, List<Set<Character>>> groups = new HashMap<>();
            for (Map.Entry<String, String> sheet : sheets(require(parts, "xl/workbook.xml")).entrySet()) {
                String name = sheet.getKey();
                String target = rels.get(sheet.getValue());
                if (!GROUP_SHEET.matcher(name).matches() || target == null) {
                    continue;
                }
                String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                byte[] xml = parts.get(path);
                if (xml == null) {
                    throw new IOException("workbook names a missing sheet part: " + path);
                }
                String group = name.substring(0, 2);
                List<Set<Character>> attrs = groups.computeIfAbsent(group,
                        g -> List.of(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>()));
                int pos = -1;
                for (String[] ab : columnsAB(xml, strings)) {
                    String a = ab[0].trim();
                    String b = ab[1].trim();
                    if (!a.isEmpty()) {
                        Matcher m = SECTION.matcher(a);
                        pos = m.matches() ? ORDINALS.indexOf(m.group(1)) : -1;
                    } else if (pos >= 0 && b.length() == 1 && b.charAt(0) >= 'A' && b.charAt(0) <= 'Z') {
                        attrs.get(pos).add(b.charAt(0));
                    }
                }
            }
            if (groups.isEmpty()) {
                throw new IOException("no group sheets (named like ESXXXX): not the SIX ISO 10962 workbook");
            }
            Map<String, List<Set<Character>>> frozen = new HashMap<>();
            for (Map.Entry<String, List<Set<Character>>> e : groups.entrySet()) {
                List<Set<Character>> l = new ArrayList<>(4);
                for (Set<Character> s : e.getValue()) {
                    l.add(Set.copyOf(s));
                }
                frozen.put(e.getKey(), List.copyOf(l));
            }
            return new CfiCodeRegistry(Map.copyOf(frozen), false);
        } catch (XMLStreamException | NumberFormatException | IndexOutOfBoundsException e) {
            throw new IOException("ISO 10962 workbook parse error: " + e.getMessage(), e);
        }
    }

    /**
     * A copy that also accepts X in any attribute position (library choice, see class doc).
     *
     * @return the lenient table
     */
    public CfiCodeRegistry withUndefinedAllowed() {
        return new CfiCodeRegistry(groups, true);
    }

    /**
     * Number of groups loaded.
     *
     * @return the count
     */
    public int groupCount() {
        return groups.size();
    }

    /**
     * The letters the list allows for one attribute of a group.
     *
     * @param group category and group letters, for example {@code ES}
     * @param attribute 1 to 4
     * @return the letters, empty for an unknown group
     * @throws IllegalArgumentException when {@code attribute} is not 1 to 4
     */
    public Set<Character> allowed(String group, int attribute) {
        if (attribute < 1 || attribute > 4) {
            throw new IllegalArgumentException("attribute must be 1 to 4: " + attribute);
        }
        List<Set<Character>> a = groups.get(Objects.requireNonNull(group, "group"));
        return a == null ? Set.of() : a.get(attribute - 1);
    }

    /**
     * Why a code is not allowed, or empty when it is.
     *
     * @param cfi the code
     * @return the reason, or empty
     */
    public Optional<String> problem(String cfi) {
        if (cfi == null || cfi.length() != 6) {
            return Optional.of("a CFI code has six letters");
        }
        List<Set<Character>> a = groups.get(cfi.substring(0, 2));
        if (a == null) {
            return Optional.of("no ISO 10962 group " + cfi.substring(0, 2));
        }
        for (int i = 0; i < 4; i++) {
            char c = cfi.charAt(2 + i);
            if (!a.get(i).contains(c) && !(undefinedAllowed && c == 'X')) {
                return Optional.of("letter " + c + " is not allowed as attribute " + (i + 1) + " of group "
                        + cfi.substring(0, 2));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the code is allowed.
     *
     * @param cfi the code
     * @return true if allowed
     */
    @Override
    public boolean test(String cfi) {
        return problem(cfi).isEmpty();
    }

    private static byte[] require(Map<String, byte[]> parts, String name) throws IOException {
        byte[] b = parts.get(name);
        if (b == null) {
            throw new IOException("not an .xlsx workbook: missing " + name);
        }
        return b;
    }

    private static Map<String, byte[]> unzip(InputStream in) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                String n = e.getName();
                if (e.isDirectory() || !(n.startsWith("xl/") && (n.endsWith(".xml") || n.endsWith(".rels")))) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                for (int r = z.read(buf); r > 0; r = z.read(buf)) {
                    total += r;
                    if (total > MAX_ENTRY_BYTES) {
                        throw new IOException("workbook part too large: " + n);
                    }
                    out.write(buf, 0, r);
                }
                parts.put(n, out.toByteArray());
            }
        }
        return parts;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        XMLStreamReader r = reader(xml);
        StringBuilder cur = null;
        boolean inT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if ("si".equals(r.getLocalName())) {
                    cur = new StringBuilder();
                } else if ("t".equals(r.getLocalName())) {
                    inT = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inT && cur != null) {
                cur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if ("t".equals(r.getLocalName())) {
                    inT = false;
                } else if ("si".equals(r.getLocalName()) && cur != null) {
                    out.add(cur.toString());
                    cur = null;
                }
            }
        }
        r.close();
        return out;
    }

    /** Sheet name to relationship id, in workbook order. */
    private static Map<String, String> sheets(byte[] xml) throws XMLStreamException {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        XMLStreamReader r = reader(xml);
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                String name = null;
                String id = null;
                for (int i = 0; i < r.getAttributeCount(); i++) {
                    String ln = r.getAttributeLocalName(i);
                    if ("name".equals(ln)) {
                        name = r.getAttributeValue(i);
                    } else if ("id".equals(ln)) {
                        id = r.getAttributeValue(i);
                    }
                }
                if (name != null && id != null) {
                    out.put(name, id);
                }
            }
        }
        r.close();
        return out;
    }

    /** Relationship id to target. */
    private static Map<String, String> rels(byte[] xml) throws XMLStreamException {
        Map<String, String> out = new HashMap<>();
        XMLStreamReader r = reader(xml);
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                String id = r.getAttributeValue(null, "Id");
                String target = r.getAttributeValue(null, "Target");
                if (id != null && target != null) {
                    out.put(id, target);
                }
            }
        }
        r.close();
        return out;
    }

    /** Columns A and B of every row of a sheet, empty when absent. */
    private static List<String[]> columnsAB(byte[] xml, List<String> strings) throws XMLStreamException {
        List<String[]> out = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        String[] row = null;
        int col = -1;
        boolean shared = false;
        boolean inValue = false;
        StringBuilder text = new StringBuilder();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("row".equals(ln)) {
                    row = new String[] {"", ""};
                } else if ("c".equals(ln)) {
                    String ref = r.getAttributeValue(null, "r");
                    col = ref == null ? -1 : ref.matches("A[0-9]+") ? 0 : ref.matches("B[0-9]+") ? 1 : -1;
                    shared = "s".equals(r.getAttributeValue(null, "t"));
                    text.setLength(0);
                } else if (col >= 0 && ("v".equals(ln) || "t".equals(ln))) {
                    inValue = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inValue) {
                text.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if ("v".equals(ln) || "t".equals(ln)) {
                    inValue = false;
                } else if ("c".equals(ln) && col >= 0 && row != null) {
                    String v = text.toString();
                    row[col] = shared && !v.isEmpty() ? strings.get(Integer.parseInt(v.trim())) : v;
                    col = -1;
                } else if ("row".equals(ln) && row != null) {
                    out.add(row);
                    row = null;
                }
            }
        }
        r.close();
        return out;
    }
}

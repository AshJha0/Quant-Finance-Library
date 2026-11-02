package com.quantfinlib.regreporting.cat;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CAT Industry Member schema, and a dependency-free validator of JSON record lines against
 * it.
 *
 * <p><b>Plain idea.</b> CAT checks every submitted record against a schema file before it
 * looks at linkage: the record must be one line of JSON, every field must be one the event
 * defines, every required field must be there, and every value must have the right JSON type
 * and fit its CAT data type (a Price has at most 10 digits before and 8 after the point, a
 * Text (64) at most 64 printable characters, and so on). This class does that check on our
 * side, so a bad record is caught before the file goes out.</p>
 *
 * <p><b>Formal statement.</b> The schema layout is that of CAT Reporting Technical
 * Specifications for Industry Members v4.1.0 r14, section 6.1.5.2: {@code eventDefinitions}
 * (each an {@code eventName} and {@code fields}, each field a {@code name},
 * {@code dataType}, {@code JSONDataType}, {@code required} and {@code position}) as printed
 * in the section 6.1.5.3 example. The names of the other two top-level members are not
 * printed; this class reads {@code choices} (objects with {@code name}, {@code values} and
 * optionally {@code eventNames}) and {@code dataTypes} (objects with {@code dataType},
 * {@code maxLength}, {@code precision}, {@code scale}), which is the library's reading. Since
 * 3.2.0 it also reads the official file's layout (seen in version 4.2.0): {@code version},
 * {@code choices} as one object of name to values, and a dataType given as an array of
 * alternatives (destination, senderIMID), any of which a value may match. Data
 * types are checked by the rules of Table 3 whatever the file says; a {@code dataTypes}
 * entry can only narrow a length. {@link #rules()} lists every check with its citation.</p>
 *
 * <p>The official schema file is published on the CAT public website and is not bundled:
 * the site's legal notice reserves copying and redistribution. The nightly CI job fetches it
 * from its public URL with a pinned SHA-256 and compares this transcription with it
 * (scripts/reference-files.sha256, {@code CatOfficialSchemaTest}).
 * {@link #bundledSubset()} is the library's own transcription of the event tables
 * this package supports (Tables 16 to 139, see the resource's {@code _provenance}). Use
 * {@link #load(Path)} with the official file when you have it.</p>
 *
 * <p><b>Worked example</b> (section 6.1.2.2 rule 3c, printed): the MENO repair record
 * {@code {"actionType":"RPR","errorROEID":12345678,...,"negotiatedTradeFlag":false}} gives one
 * issue against the bundled subset, CAT-S05 for {@code representativeInd}: the printed example
 * leaves out a field that Table 16 seq 33 marks 'R' (an abbreviated example, or a stale one).
 * With {@code "representativeInd":"N"} added it passes; then {@code "price":10.010} still
 * passes (a Price may carry trailing zeros), {@code "quantity":500.0} fails CAT-S07 (Real
 * Quantity has no trailing zeros) and {@code "price":"10.01"} fails CAT-S06 (Price is a JSON
 * NUMBER). Own tests pin all five.</p>
 *
 * <p>Not checked: the content of Name/Value Pairs and of multi-dimensional arrays beyond
 * their JSON type (the attribute lists are in Appendix G), conditional-field logic, and
 * anything that needs reference data. Not certified.</p>
 *
 * @since 3.1.0
 */
public final class CatSchema {
    private static final String SPEC = "CAT IM Technical Specifications v4.1.0 r14, ";
    private static final String BUNDLED = "cat-im-schema-subset-4.1.0r14.json";
    /** Maximum bytes per line (section 6.1.2.1 rule 3). */
    public static final int MAX_LINE_BYTES = 8190;

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("CAT-S01", "a record is one line of at most 8190 bytes with no new-line inside",
                    SPEC + "section 6.1.2.1 rules 2-4, section 6.1.2.2 rule 3a", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S02", "a record is one well-formed JSON object (RFC 8259, no duplicate keys)",
                    SPEC + "section 6.1.2.2 rules 1 and 3; duplicate keys rejected is the library's reading",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S03", "type names an event defined in the schema", SPEC + "section 6.1.5.2 "
                    + "item 2 (Event Definitions)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S04", "every field name is one the event defines, matched case-sensitively",
                    SPEC + "section 2.5.1 ('Field Names ... are case sensitive'); rejecting an unknown name is the "
                    + "library's reading of section 6.1.5", Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-S05", "every field marked Required is present, except Booleans (absent means "
                    + "false) and except on a DEL record", SPEC + "section 6.1.5.2 item 2 (required), Table 3 "
                    + "Boolean, section 7.6.3 (DEL carries only actionType and an ROEID)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S06", "each value has the field's JSON type (STRING, NUMBER, BOOLEAN, OBJECT, "
                    + "ARRAY; Timestamp STRING or NUMBER)", SPEC + "Table 3 JSON Type column, section 6.1.5.2 "
                    + "(JSONDataType)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S07", "each value fits its data type: Numeric (p,s) with a digit before any "
                    + "point and no point when s is 0; Price (10,8); Real Quantity (12,6), not negative, no "
                    + "trailing zeros; Whole Quantity (12,0) not negative; Integer 64-bit signed; Unsigned 64-bit "
                    + "unsigned; Date YYYYMMDD; Timestamp 'YYYYMMDD HHMMSS[.fraction]' (space or T, at most 25 "
                    + "characters) or Unsigned; Text (n) printable ASCII 32-126 without comma, pipe, double quote "
                    + "or @, at most n characters after unescaping; Alphanumeric (n) [a-zA-Z0-9]; Symbol Text "
                    + "(22); Message Type Alphanumeric (5); CAT Reporter IMID and Exchange ID Alphanumeric (7); "
                    + "Industry Member ID Text (16)", SPEC + "Table 3 (examples printed and pinned in tests); "
                    + "minutes and seconds 00-59 is the library's reading, Table 3 prints 01-59 but its own "
                    + "examples use 00", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S08", "a Choice value is one of the schema's values for that field (and "
                    + "event, when the choice is scoped)", SPEC + "section 6.1.5.2 item 3 (Choices); Appendix G",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-S09", "a field without a value is omitted, not sent as an empty string or null",
                    SPEC + "section 6.1.2.2 rule 3b", Evidence.PUBLISHED_REFERENCE));

    /**
     * One schema problem in a record.
     *
     * @param line 1-based line number (1 for {@link #validateLine(String)})
     * @param field the field concerned, or an empty string for the whole record
     * @param ruleId CAT-S01 to CAT-S09
     * @param message what is wrong
     */
    public record Issue(int line, String field, String ruleId, String message) {
        /** Validates non-null components. */
        public Issue {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * One field definition (section 6.1.5.2 item 2).
     *
     * @param name field name
     * @param dataType CAT data type, for example {@code "Price"} or {@code "Text (64)"}; since 3.2.0
     *     alternatives given as a JSON array in the file are joined by {@code |}
     * @param jsonTypes allowed JSON types
     * @param required {@code Required}, {@code Conditional} or {@code Optional}
     * @param position CSV position
     */
    public record Field(String name, String dataType, List<String> jsonTypes, String required, int position) {
        /** Validates non-null components and copies the list. */
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dataType, "dataType");
            Objects.requireNonNull(required, "required");
            jsonTypes = List.copyOf(jsonTypes);
        }
    }

    private static final Pattern SIZED = Pattern.compile("(Text|Alphanumeric) \\((\\d+)\\)");
    private static final Pattern NUMERIC = Pattern.compile("Numeric \\((\\d+),(\\d+)\\)");
    private static final Pattern NUMBER_TEXT = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern TS_STRING = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})[ T](\\d{2})(\\d{2})(\\d{2})"
            + "(\\.\\d{1,9})?");
    private static final Pattern ALNUM = Pattern.compile("[a-zA-Z0-9]*");
    private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");
    private static final BigInteger I64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger I64_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final String version;
    private final Map<String, Map<String, Field>> events;
    private final Map<String, Set<String>> choices;
    private final Map<String, Integer> maxLengths;

    private CatSchema(String version, Map<String, Map<String, Field>> events, Map<String, Set<String>> choices,
            Map<String, Integer> maxLengths) {
        this.version = version;
        this.events = events;
        this.choices = choices;
        this.maxLengths = maxLengths;
    }

    /**
     * Every check {@link #validateLine(String)} makes, with citation and evidence class.
     *
     * @return the rule catalogue (immutable)
     */
    public static List<RuleProvenance> rules() {
        return RULES;
    }

    /**
     * The library's transcription of the event tables this package supports, bundled as a
     * resource. Not the official CAT schema file.
     *
     * @return the schema
     * @throws IllegalStateException if the resource is missing or malformed (a build fault)
     */
    public static CatSchema bundledSubset() {
        try (InputStream in = CatSchema.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + BUNDLED);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + BUNDLED, e);
        }
    }

    /**
     * Loads a schema file, such as the official one from the CAT public website.
     *
     * @param file the JSON file
     * @return the schema
     * @throws IOException if the file cannot be read or is not a schema in the section
     *     6.1.5.2 layout
     */
    public static CatSchema load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses schema JSON text.
     *
     * @param json the schema text
     * @return the schema
     * @throws IOException if the text is not a schema in the section 6.1.5.2 layout
     */
    public static CatSchema parse(String json) throws IOException {
        Object root;
        try {
            root = CatJson.parse(json);
        } catch (CatJson.ParseException e) {
            throw new IOException("schema is not JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> top)) {
            throw new IOException("schema root must be a JSON object");
        }
        Map<String, Map<String, Field>> events = new LinkedHashMap<>();
        for (Object o : list(top.get("eventDefinitions"), "eventDefinitions")) {
            Map<?, ?> ev = map(o, "event definition");
            String name = text(ev.get("eventName"), "eventName");
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Object fo : list(ev.get("fields"), "fields of " + name)) {
                Map<?, ?> f = map(fo, "field of " + name);
                String fname = text(f.get("name"), "field name");
                Object jt = f.get("JSONDataType");
                List<String> jts = new ArrayList<>();
                if (jt instanceof List<?> l) {
                    for (Object x : l) {
                        jts.add(text(x, "JSONDataType"));
                    }
                } else {
                    jts.add(text(jt, "JSONDataType of " + fname));
                }
                Object pos = f.get("position");
                int p;
                try {
                    p = pos == null ? 0 : Integer.parseInt(pos.toString());
                } catch (NumberFormatException e) {
                    throw new IOException("bad position for " + name + "." + fname, e);
                }
                fields.put(fname, new Field(fname, dataType(f.get("dataType"), fname), jts,
                        text(f.get("required"), "required of " + fname), p));
            }
            events.put(name, Collections.unmodifiableMap(fields));
        }
        if (events.isEmpty()) {
            throw new IOException("schema defines no events");
        }
        Map<String, Set<String>> choices = new LinkedHashMap<>();
        Object ch = top.get("choices");
        if (ch instanceof Map<?, ?> byName) {
            // the official file's layout: {"name": [values], ...}, not scoped to events
            for (Map.Entry<?, ?> c : byName.entrySet()) {
                String name = text(c.getKey(), "choice name");
                Set<String> values = new LinkedHashSet<>();
                for (Object v : list(c.getValue(), "values of " + name)) {
                    values.add(v instanceof String x ? x : v.toString());
                }
                choices.put("*." + name, Collections.unmodifiableSet(values));
            }
        } else if (ch != null) {
            for (Object o : list(ch, "choices")) {
                Map<?, ?> c = map(o, "choice");
                String name = text(c.get("name"), "choice name");
                Set<String> values = new LinkedHashSet<>();
                for (Object v : list(c.get("values"), "values of " + name)) {
                    values.add(text(v, "choice value"));
                }
                Object scope = c.get("eventNames");
                if (scope == null) {
                    choices.put("*." + name, Collections.unmodifiableSet(values));
                } else {
                    for (Object e : list(scope, "eventNames of " + name)) {
                        choices.put(text(e, "eventName") + "." + name, Collections.unmodifiableSet(values));
                    }
                }
            }
        }
        Map<String, Integer> maxLengths = new LinkedHashMap<>();
        Object dts = top.get("dataTypes");
        if (dts != null) {
            for (Object o : list(dts, "dataTypes")) {
                Map<?, ?> d = map(o, "data type");
                Object ml = d.get("maxLength");
                if (ml instanceof CatJson.Num n) {
                    try {
                        maxLengths.put(text(d.get("dataType"), "dataType"), Integer.parseInt(n.text()));
                    } catch (NumberFormatException e) {
                        throw new IOException("bad maxLength " + n, e);
                    }
                }
            }
        }
        Object v = top.get("schemaVersion");
        if (v == null) {
            // the official file published on the CAT website names it "version" (seen 2026-11-02)
            v = top.get("version");
        }
        return new CatSchema(v instanceof String s ? s : "", Collections.unmodifiableMap(events),
                Collections.unmodifiableMap(choices), Collections.unmodifiableMap(maxLengths));
    }

    /**
     * The schema version given in the file (section 6.1.5.1), or an empty string. Read from
     * {@code schemaVersion}, or since 3.2.0 from {@code version} when that is absent, which is
     * the name the official file uses.
     *
     * @return the version
     */
    public String version() {
        return version;
    }

    /**
     * The event names defined, in file order.
     *
     * @return the names (immutable)
     */
    public Set<String> eventNames() {
        return events.keySet();
    }

    /**
     * The field definitions of one event, in file order.
     *
     * @param eventName the message type, such as {@code MENO}
     * @return the fields (immutable), empty if the event is not defined
     */
    public List<Field> fields(String eventName) {
        Map<String, Field> m = events.get(eventName);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    /**
     * Validates one record line.
     *
     * @param line the line, without its terminating new-line
     * @return the issues, empty when the record conforms
     */
    public List<Issue> validateLine(String line) {
        return validate(1, line);
    }

    /**
     * Validates the lines of a data file.
     *
     * @param lines the lines, without their terminating new-lines
     * @return the issues of every line, in order
     */
    public List<Issue> validateLines(List<String> lines) {
        List<Issue> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            out.addAll(validate(i + 1, lines.get(i)));
        }
        return out;
    }

    private List<Issue> validate(int n, String line) {
        List<Issue> out = new ArrayList<>();
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            out.add(new Issue(n, "", "CAT-S01", "record longer than " + MAX_LINE_BYTES + " bytes"));
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            out.add(new Issue(n, "", "CAT-S01", "record contains a new-line"));
            return out;
        }
        Object parsed;
        try {
            parsed = CatJson.parse(line);
        } catch (CatJson.ParseException e) {
            out.add(new Issue(n, "", "CAT-S02", "not JSON: " + e.getMessage()));
            return out;
        }
        if (!(parsed instanceof Map<?, ?> rec)) {
            out.add(new Issue(n, "", "CAT-S02", "record must be a JSON object"));
            return out;
        }
        Object type = rec.get("type");
        Map<String, Field> defs = type instanceof String t ? events.get(t) : null;
        if (defs == null) {
            out.add(new Issue(n, "type", "CAT-S03", "type " + type + " is not an event in the schema"));
            return out;
        }
        String event = (String) type;
        boolean del = "DEL".equals(rec.get("actionType"));
        for (Map.Entry<?, ?> e : rec.entrySet()) {
            String name = (String) e.getKey();
            Field f = defs.get(name);
            if (f == null) {
                out.add(new Issue(n, name, "CAT-S04", name + " is not a field of " + event));
                continue;
            }
            checkValue(n, event, f, e.getValue(), out);
        }
        if (!del) {
            for (Field f : defs.values()) {
                if ("Required".equals(f.required()) && !"Boolean".equals(f.dataType())
                        && !rec.containsKey(f.name())) {
                    out.add(new Issue(n, f.name(), "CAT-S05", "missing required field " + f.name()));
                }
            }
        }
        return out;
    }

    private void checkValue(int n, String event, Field f, Object v, List<Issue> out) {
        String name = f.name();
        if (CatJson.NULL.equals(v) || "".equals(v)) {
            out.add(new Issue(n, name, "CAT-S09", name + " has no value; omit it"));
            return;
        }
        String jt = jsonType(v);
        if (!f.jsonTypes().contains(jt)) {
            out.add(new Issue(n, name, "CAT-S06", name + " must be JSON " + String.join(" or ", f.jsonTypes())
                    + ", not " + jt));
            return;
        }
        String problem = dataTypeProblem(f.dataType(), v);
        if (problem != null) {
            out.add(new Issue(n, name, "CAT-S07", name + " (" + f.dataType() + "): " + problem));
            return;
        }
        if ("Choice".equals(f.dataType())) {
            Set<String> allowed = choices.getOrDefault(event + "." + name, choices.get("*." + name));
            if (allowed != null && !allowed.contains((String) v)) {
                out.add(new Issue(n, name, "CAT-S08", name + " must be one of " + allowed));
            }
        }
    }

    private static String jsonType(Object v) {
        if (v instanceof String) {
            return "STRING";
        }
        if (v instanceof CatJson.Num) {
            return "NUMBER";
        }
        if (v instanceof Boolean) {
            return "BOOLEAN";
        }
        if (v instanceof Map) {
            return "OBJECT";
        }
        return "ARRAY";
    }

    /**
     * A field's dataType: a string, or (as in the official file, e.g. destination and
     * senderIMID) an array of alternatives, held joined by {@code |}.
     */
    private static String dataType(Object o, String fname) throws IOException {
        if (o instanceof List<?> l && !l.isEmpty()) {
            List<String> alts = new ArrayList<>();
            for (Object x : l) {
                alts.add(text(x, "dataType of " + fname));
            }
            return String.join("|", alts);
        }
        return text(o, "dataType of " + fname);
    }

    /** Returns null when {@code v} fits one of the {@code |}-separated alternatives. */
    private String dataTypeProblem(String dataType, Object v) {
        if (dataType.indexOf('|') < 0) {
            return singleDataTypeProblem(dataType, v);
        }
        String first = null;
        for (String alt : dataType.split("\\|")) {
            String p = singleDataTypeProblem(alt, v);
            if (p == null) {
                return null;
            }
            first = first == null ? p : first;
        }
        return first;
    }

    /** Returns null when {@code v} fits {@code dataType} (Table 3), else the reason. */
    private String singleDataTypeProblem(String dataType, Object v) {
        String s = v.toString();
        switch (dataType) {
            case "Price":
                return numeric(s, 10, 8);
            case "Real Quantity": {
                String p = numeric(s, 12, 6);
                if (p != null) {
                    return p;
                }
                if (s.startsWith("-")) {
                    return "must not be negative";
                }
                return s.contains(".") && s.endsWith("0") ? "no trailing zeros in the decimals" : null;
            }
            case "Whole Quantity": {
                String p = numeric(s, 12, 0);
                return p != null ? p : s.startsWith("-") ? "must not be negative" : null;
            }
            case "Integer":
                return integer(s, I64_MIN, I64_MAX);
            case "Unsigned", "CAT Submitter ID":
                return integer(s, BigInteger.ZERO, U64_MAX);
            case "Date":
                return s.matches("\\d{8}") && validDate(s.substring(4, 6), s.substring(6, 8)) ? null
                        : "must be an 8-digit YYYYMMDD";
            case "Timestamp":
                return v instanceof String ? timestamp(s) : integer(s, BigInteger.ZERO, U64_MAX);
            case "Boolean", "Name/Value Pairs", "Array":
                return null;
            case "Aggregated Orders", "Trade Side Details", "Fulfillment Side Details", "Leg Details":
                for (Object o : (List<?>) v) {
                    if (!(o instanceof Map)) {
                        return "each element must be a JSON object";
                    }
                }
                return null;
            case "Choice":
                return textProblem(s, length("Choice", Integer.MAX_VALUE));
            case "Symbol":
                return textProblem(s, length("Symbol", 22));
            case "Message Type":
                return alnum(s, length("Message Type", 5));
            case "CAT Reporter IMID", "Exchange ID":
                return alnum(s, length(dataType, 7));
            case "Industry Member ID":
                return textProblem(s, length(dataType, 16));
            default:
                break;
        }
        Matcher m = SIZED.matcher(dataType);
        if (m.matches()) {
            int len = length(dataType, Integer.parseInt(m.group(2)));
            return "Text".equals(m.group(1)) ? textProblem(s, len) : alnum(s, len);
        }
        m = NUMERIC.matcher(dataType);
        if (m.matches()) {
            return numeric(s, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return null;
    }

    private int length(String dataType, int dflt) {
        return Math.min(dflt, maxLengths.getOrDefault(dataType, dflt));
    }

    /**
     * Table 3, Numeric (before, after): digits with an optional point, a digit before any
     * point, no point when {@code after} is 0. Leading zeros of the whole part and trailing
     * zeros of the fraction do not count (Table 3 accepts 0999999.99990 as Numeric (6,4)).
     */
    static String numeric(String s, int before, int after) {
        if (!NUMBER_TEXT.matcher(s).matches()) {
            return "must be digits with an optional point and a digit before it";
        }
        String body = s.startsWith("-") ? s.substring(1) : s;
        int dot = body.indexOf('.');
        if (dot >= 0 && after == 0) {
            return "must not have a decimal point";
        }
        String whole = (dot < 0 ? body : body.substring(0, dot)).replaceFirst("^0+(?=\\d)", "");
        String frac = dot < 0 ? "" : body.substring(dot + 1).replaceFirst("0+$", "");
        if (whole.length() > before) {
            return "more than " + before + " digits before the point";
        }
        if (frac.length() > after) {
            return "more than " + after + " digits after the point";
        }
        return null;
    }

    private static String integer(String s, BigInteger min, BigInteger max) {
        if (!s.matches("-?\\d+")) {
            return "must be an integer";
        }
        BigInteger b = new BigInteger(s);
        return b.compareTo(min) < 0 || b.compareTo(max) > 0 ? "out of range [" + min + ", " + max + "]" : null;
    }

    static String timestamp(String s) {
        if (s.length() > 25) {
            return "at most 25 characters";
        }
        Matcher m = TS_STRING.matcher(s);
        if (!m.matches()) {
            return "must be 'YYYYMMDD HHMMSS.CCCNNNNNN' (space or T)";
        }
        int mon = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (mon > 12 || day > 31 || Integer.parseInt(m.group(4)) > 23 || Integer.parseInt(m.group(5)) > 59
                || Integer.parseInt(m.group(6)) > 59) {
            return "date or time field out of range";
        }
        return null;
    }

    private static boolean validDate(String mm, String dd) {
        int m = Integer.parseInt(mm);
        int d = Integer.parseInt(dd);
        return m <= 12 && d <= 31;
    }

    static String textProblem(String s, int max) {
        if (s.length() > max) {
            return "longer than " + max + " characters";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126 || c == ',' || c == '|' || c == '"' || c == '@') {
                return "character '" + (c < 32 || c > 126 ? String.format("\\u%04x", (int) c) : c)
                        + "' not allowed in Text";
            }
        }
        return null;
    }

    private static String alnum(String s, int max) {
        if (s.length() > max) {
            return "longer than " + max + " characters";
        }
        return ALNUM.matcher(s).matches() ? null : "must be Alphanumeric [a-zA-Z0-9]";
    }

    /**
     * Writes an event as one JSON record line with the JSON types of this schema, unlike
     * {@link CatEvent#toJson()}, which writes every value as a string. NUMBER fields are
     * written bare, BOOLEAN fields as {@code true}/{@code false}, Timestamp fields as strings,
     * OBJECT and ARRAY fields bare when the value is already JSON ({@code {...}} or
     * {@code [...]}). A Name/Value Pairs value in the CSV form of Table 3
     * ({@code DAY=20260302}, {@code AOK|DISP=10.00}, {@code DLVT=MM1@MM2}) is converted: a
     * bare name becomes {@code true}, a numeric value a NUMBER, a value with {@code @} an
     * ARRAY of strings, anything else a STRING (the conversion of values is the library's
     * reading). Empty values are left out (section 6.1.2.2 rule 3b). Fields the schema does
     * not define are written as strings.
     *
     * @param event the event
     * @return one line of JSON
     */
    public String toJson(CatEvent event) {
        Map<String, Field> defs = events.getOrDefault(event.fields().getOrDefault("type", ""), Map.of());
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : event.fields().entrySet()) {
            String v = e.getValue();
            if (v.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, e.getKey()).append(':');
            Field f = defs.get(e.getKey());
            List<String> jt = f == null ? List.of("STRING") : f.jsonTypes();
            if (jt.contains("STRING")) {
                quote(sb, v);
            } else if (jt.contains("NUMBER")) {
                if (NUMBER_TEXT.matcher(v).matches()) {
                    sb.append(v);
                } else {
                    quote(sb, v);
                }
            } else if (jt.contains("BOOLEAN")) {
                sb.append("true".equalsIgnoreCase(v) ? "true" : "false");
            } else if (v.startsWith("{") || v.startsWith("[")) {
                sb.append(v);
            } else if (jt.contains("OBJECT")) {
                nameValuePairs(sb, v);
            } else {
                quote(sb, v);
            }
        }
        return sb.append('}').toString();
    }

    private static void nameValuePairs(StringBuilder sb, String csv) {
        sb.append('{');
        String[] attrs = csv.split("\\|");
        for (int i = 0; i < attrs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String a = attrs[i];
            int eq = a.indexOf('=');
            if (eq < 0) {
                quote(sb, a).append(":true");
                continue;
            }
            quote(sb, a.substring(0, eq)).append(':');
            String val = a.substring(eq + 1);
            if (val.indexOf('@') >= 0) {
                sb.append('[');
                String[] parts = val.split("@");
                for (int j = 0; j < parts.length; j++) {
                    if (j > 0) {
                        sb.append(',');
                    }
                    quote(sb, parts[j]);
                }
                sb.append(']');
            } else if (NUMBER_TEXT.matcher(val).matches()) {
                sb.append(val);
            } else {
                quote(sb, val);
            }
        }
        sb.append('}');
    }

    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }

    private static List<?> list(Object o, String what) throws IOException {
        if (o instanceof List<?> l) {
            return l;
        }
        throw new IOException(what + " must be a JSON array");
    }

    private static Map<?, ?> map(Object o, String what) throws IOException {
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new IOException(what + " must be a JSON object");
    }

    private static String text(Object o, String what) throws IOException {
        if (o instanceof String s) {
            return s;
        }
        throw new IOException(what + " must be a JSON string");
    }
}

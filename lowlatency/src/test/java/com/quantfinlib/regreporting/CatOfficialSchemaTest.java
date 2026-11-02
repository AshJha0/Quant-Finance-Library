package com.quantfinlib.regreporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.cat.CatSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Compares the library's schema transcription ({@link CatSchema#bundledSubset()}) with the
 * official CAT Industry Member schema file. The file is published on catnmsplan.com
 * (Specifications, Industry Member) but its legal notice reserves copying and redistribution,
 * so it is not committed: the nightly job fetches it from its public URL, checks the SHA-256
 * pinned in scripts/reference-files.sha256 and passes the path in {@code QFL_CAT_SCHEMA}.
 * Locally the test is skipped unless that variable names the file.
 *
 * <p>For every event both files define, each field of the transcription must exist in the
 * official file with the same dataType, JSONDataType and required value. Differences the
 * library has looked at and accepted (for example a field the published version added or
 * changed after the v4.1.0 r14 tables) are listed in {@code cat-official-schema-deltas.txt}
 * with a reason; anything else fails, and the message lists every difference. Evidence class:
 * externally anchored.</p>
 */
class CatOfficialSchemaTest {

    private static List<String> accepted() throws IOException {
        try (InputStream in = CatOfficialSchemaTest.class.getResourceAsStream("cat-official-schema-deltas.txt")) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).map(l -> l.split("\\s+", 2)[0]).toList();
        }
    }

    /** The differences between {@code ours} and {@code official}, one key per line. */
    static List<String> differences(CatSchema ours, CatSchema official) {
        List<String> out = new ArrayList<>();
        for (String ev : ours.eventNames()) {
            if (!official.eventNames().contains(ev)) {
                out.add(ev + " missing-event");
                continue;
            }
            Map<String, CatSchema.Field> theirs = official.fields(ev).stream()
                    .collect(Collectors.toMap(CatSchema.Field::name, Function.identity(), (a, b) -> a));
            for (CatSchema.Field f : ours.fields(ev)) {
                CatSchema.Field o = theirs.get(f.name());
                String k = ev + "." + f.name();
                if (o == null) {
                    out.add(k + " missing-field");
                    continue;
                }
                if (!norm(f.dataType()).equals(norm(o.dataType()))) {
                    out.add(k + ".dataType ours=" + f.dataType() + " official=" + o.dataType());
                }
                if (!new TreeSet<>(f.jsonTypes()).equals(new TreeSet<>(o.jsonTypes()))) {
                    out.add(k + ".JSONDataType ours=" + f.jsonTypes() + " official=" + o.jsonTypes());
                }
                if (!f.required().equals(o.required())) {
                    out.add(k + ".required ours=" + f.required() + " official=" + o.required());
                }
            }
        }
        return out;
    }

    private static String norm(String dataType) {
        return dataType.replaceAll("\\s+", "");
    }

    @Test
    void transcriptionMatchesOfficialSchema() throws IOException {
        String p = System.getenv("QFL_CAT_SCHEMA");
        boolean present = p != null && Files.isRegularFile(Path.of(p));
        ReferenceFiles.require(present, "QFL_CAT_SCHEMA (official CAT IM schema JSON) not set or missing");
        CatSchema official = CatSchema.load(Path.of(p));
        assertTrue(official.version().startsWith("4."), official.version());
        Set<String> ok = Set.copyOf(accepted());
        List<String> diff = differences(CatSchema.bundledSubset(), official);
        List<String> unexplained = diff.stream().filter(d -> !ok.contains(d.split(" ", 2)[0])).toList();
        assertEquals(List.of(), unexplained, "official schema " + official.version() + " differs from the "
                + "v4.1.0 r14 transcription; review each line and either fix the transcription or add it, "
                + "with a reason, to cat-official-schema-deltas.txt");
    }

    /** The comparison itself, on a hand-made pair (own construction). */
    @Test
    void differencesFindsEachKind() throws IOException {
        CatSchema a = CatSchema.parse("{\"eventDefinitions\":[{\"eventName\":\"MENO\",\"fields\":["
                + "{\"name\":\"x\",\"dataType\":\"Text (4)\",\"JSONDataType\":\"STRING\",\"required\":\"Required\"},"
                + "{\"name\":\"y\",\"dataType\":\"Price\",\"JSONDataType\":\"NUMBER\",\"required\":\"Optional\"},"
                + "{\"name\":\"z\",\"dataType\":\"Price\",\"JSONDataType\":\"NUMBER\",\"required\":\"Optional\"}]},"
                + "{\"eventName\":\"MEOR\",\"fields\":[]}]}");
        CatSchema b = CatSchema.parse("{\"version\":\"4.2.0\",\"eventDefinitions\":[{\"eventName\":\"MENO\","
                + "\"fields\":[{\"name\":\"x\",\"dataType\":\"Text(4)\",\"JSONDataType\":\"STRING\","
                + "\"required\":\"Conditional\"},{\"name\":\"y\",\"dataType\":\"Real Quantity\","
                + "\"JSONDataType\":[\"NUMBER\",\"STRING\"],\"required\":\"Optional\"}]}]}");
        assertEquals("4.2.0", b.version());
        assertEquals(List.of("MENO.x.required ours=Required official=Conditional",
                "MENO.y.dataType ours=Price official=Real Quantity",
                "MENO.y.JSONDataType ours=[NUMBER] official=[NUMBER, STRING]", "MENO.z missing-field",
                "MEOR missing-event"), differences(a, b));
        assertTrue(differences(a, a).isEmpty());
        assertFalse(accepted().contains(""));
    }
}

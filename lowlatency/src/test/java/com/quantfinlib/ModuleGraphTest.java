package com.quantfinlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * The 4.0 module graph, checked from the compiled descriptors of all four
 * reactor modules (this module is built last): the library modules form an
 * acyclic graph, each package lives in exactly one module, the whole
 * {@code marketdata} hierarchy is in this module, and jdeps finds no
 * {@code java.net.http} use in the stable module's classes.
 */
class ModuleGraphTest {

    private static final List<String> DIRS = List.of("core", "io", "research", "lowlatency");

    private static Path classes(String dir) {
        return Path.of("..", dir, "target", "classes");
    }

    private static Map<String, ModuleDescriptor> descriptors() throws IOException {
        Map<String, ModuleDescriptor> all = new TreeMap<>();
        for (String dir : DIRS) {
            try (InputStream in = Files.newInputStream(classes(dir).resolve("module-info.class"))) {
                ModuleDescriptor d = ModuleDescriptor.read(in);
                all.put(d.name(), d);
            }
        }
        return all;
    }

    @Test
    void libraryModulesFormAnAcyclicGraph() throws IOException {
        Map<String, ModuleDescriptor> all = descriptors();
        assertEquals(Set.of("com.quantfinlib", "com.quantfinlib.io", "com.quantfinlib.research",
                "com.quantfinlib.lowlatency"), all.keySet());
        Map<String, Integer> state = new HashMap<>();
        for (String m : all.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            assertFalse(cycleFrom(m, all, state, path), "module cycle through " + path);
        }
        assertTrue(all.get("com.quantfinlib").requires().stream()
                .noneMatch(r -> all.containsKey(r.name())), "the stable module requires no library module");
    }

    private static boolean cycleFrom(String m, Map<String, ModuleDescriptor> all,
                                     Map<String, Integer> state, Deque<String> path) {
        Integer s = state.get(m);
        if (s != null) {
            return s == 1;
        }
        state.put(m, 1);
        path.push(m);
        for (ModuleDescriptor.Requires r : all.get(m).requires()) {
            if (all.containsKey(r.name()) && cycleFrom(r.name(), all, state, path)) {
                return true;
            }
        }
        path.pop();
        state.put(m, 2);
        return false;
    }

    @Test
    void eachPackageLivesInOneModule() throws IOException {
        Map<String, String> owner = new HashMap<>();
        for (ModuleDescriptor d : descriptors().values()) {
            for (String p : d.packages()) {
                String prev = owner.put(p, d.name());
                assertTrue(prev == null, p + " is in " + prev + " and " + d.name());
            }
        }
        Set<String> owners = new HashSet<>();
        owner.forEach((p, m) -> {
            if (p.startsWith("com.quantfinlib.marketdata")) {
                owners.add(m);
            }
        });
        assertEquals(Set.of("com.quantfinlib.lowlatency"), owners, "owners of the marketdata hierarchy");
    }

    @Test
    void jdepsFindsNoJavaNetHttpInTheStableModule() {
        ToolProvider jdeps = ToolProvider.findFirst("jdeps").orElseThrow();
        StringWriter out = new StringWriter();
        int rc = jdeps.run(new PrintWriter(out), new PrintWriter(out),
                "--ignore-missing-deps", "-summary", classes("core").toString());
        assertEquals(0, rc, out.toString());
        assertFalse(out.toString().contains("java.net.http"), out.toString());
    }
}

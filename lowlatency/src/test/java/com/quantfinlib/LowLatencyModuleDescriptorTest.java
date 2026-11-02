package com.quantfinlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The low-latency module descriptor, checked directly because the tests run
 * on the class path: every lane package is exported (the {@code examples}
 * package never is), and the stable module is required transitively so a lane
 * user needs one {@code requires} line.
 */
class LowLatencyModuleDescriptorTest {

    private static ModuleDescriptor descriptor() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("target", "classes", "module-info.class"))) {
            return ModuleDescriptor.read(in);
        }
    }

    private static Set<String> sourcePackages() throws IOException {
        Path root = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(root.resolve("com"))) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p.getParent()).toString().replace('\\', '.').replace('/', '.'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void exportsEveryLanePackageButExamples() throws IOException {
        ModuleDescriptor d = descriptor();
        assertEquals("com.quantfinlib.lowlatency", d.name());
        Set<String> expected = sourcePackages();
        assertTrue(expected.remove("com.quantfinlib.examples"));
        expected.removeIf(p -> p.startsWith("com.quantfinlib.examples."));
        // The other two examples-jar packages (4.0): compiled here, never exported.
        assertTrue(expected.remove("com.quantfinlib.cli"));
        assertTrue(expected.remove("com.quantfinlib.backtest.strategies"));
        Set<String> exported = d.exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, exported);
        assertEquals(Set.of("com.quantfinlib.backtest.tick", "com.quantfinlib.entitlements",
                "com.quantfinlib.feed", "com.quantfinlib.fix", "com.quantfinlib.marketdata",
                "com.quantfinlib.marketdata.tick", "com.quantfinlib.rfq", "com.quantfinlib.orderbook", "com.quantfinlib.regreporting",
                "com.quantfinlib.regreporting.cat", "com.quantfinlib.regreporting.emir",
                "com.quantfinlib.regreporting.registry", "com.quantfinlib.sbe", "com.quantfinlib.trading",
                "com.quantfinlib.util"), exported);
    }

    @Test
    void requiresTheStableModuleTransitively() throws IOException {
        Set<String> requires = new TreeSet<>();
        for (ModuleDescriptor.Requires r : descriptor().requires()) {
            if (r.name().equals("com.quantfinlib")) {
                assertTrue(r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE));
            }
            if (r.name().equals("org.jspecify") || r.name().equals("com.quantfinlib.io")
                    || r.name().equals("com.quantfinlib.research")) {
                assertTrue(r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC));
            }
            requires.add(r.name());
        }
        assertEquals(Set.of("java.base", "com.quantfinlib", "com.quantfinlib.io", "com.quantfinlib.research",
                "java.net.http", "java.xml", "jdk.httpserver", "org.jspecify"), requires);
    }
}

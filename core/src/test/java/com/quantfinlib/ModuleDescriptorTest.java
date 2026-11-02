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
import java.util.Set;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The tests run on the class path, so the module descriptor is checked here
 * directly: every source package except {@code internal} is exported,
 * {@code internal} only to the low-latency module, and the only requirements
 * are {@code java.base} and static JSpecify (4.0 dropped {@code java.net.http}). Since 3.0 the stable module
 * must not need {@code jdk.httpserver}; {@link #jdepsFindsNoHttpDependency}
 * checks the compiled classes with {@code jdeps}, not just the descriptor.
 */
class ModuleDescriptorTest {

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
    void exportsEveryLibraryPackageButInternal() throws IOException {
        ModuleDescriptor d = descriptor();
        assertEquals("com.quantfinlib", d.name());
        Set<String> expected = sourcePackages();
        assertTrue(expected.remove("com.quantfinlib.internal"));
        Set<String> exported = d.exports().stream()
                .filter(e -> !e.isQualified())
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, exported);
        Set<String> qualified = d.exports().stream()
                .filter(ModuleDescriptor.Exports::isQualified)
                .map(e -> e.source() + " to " + e.targets())
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of("com.quantfinlib.internal to [com.quantfinlib.lowlatency]"), qualified);
    }

    @Test
    void jdepsFindsNoHttpDependency() {
        ToolProvider jdeps = ToolProvider.findFirst("jdeps").orElseThrow();
        StringWriter out = new StringWriter();
        int rc = jdeps.run(new PrintWriter(out), new PrintWriter(out),
                "--ignore-missing-deps", "-summary", Path.of("target", "classes").toString());
        assertEquals(0, rc, out.toString());
        // 4.0: java.net.http moved out with io.HttpBarFetcher (docs/MIGRATION_4.0.md row 7).
        assertFalse(out.toString().contains("java.net.http"), out.toString());
        assertFalse(out.toString().contains("jdk.httpserver"), out.toString());
    }

    @Test
    void requiresOnlyJdkModulesAndStaticJspecify() throws IOException {
        Set<String> requires = new TreeSet<>();
        for (ModuleDescriptor.Requires r : descriptor().requires()) {
            if (r.name().equals("org.jspecify")) {
                assertTrue(r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC));
            }
            requires.add(r.name());
        }
        assertEquals(Set.of("java.base", "org.jspecify"), requires);
    }
}

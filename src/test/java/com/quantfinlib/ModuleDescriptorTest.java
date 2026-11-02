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
 * The tests run on the class path, so the module descriptor is checked here
 * directly: every source package except {@code examples} is exported, and
 * the only requirements are the two JDK modules and static JSpecify.
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
    void exportsEveryLibraryPackageButExamples() throws IOException {
        ModuleDescriptor d = descriptor();
        assertEquals("com.quantfinlib", d.name());
        Set<String> expected = sourcePackages();
        assertTrue(expected.remove("com.quantfinlib.examples"));
        Set<String> exported = d.exports().stream()
                .filter(e -> !e.isQualified())
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, exported);
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
        assertEquals(Set.of("java.base", "java.net.http", "jdk.httpserver", "org.jspecify"), requires);
    }
}

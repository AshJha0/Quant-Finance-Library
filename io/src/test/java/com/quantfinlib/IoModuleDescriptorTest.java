package com.quantfinlib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The io module descriptor: two exports, the stable module transitively, and {@code java.net.http}. */
class IoModuleDescriptorTest {

    @Test
    void descriptorIsAsDocumented() throws IOException {
        ModuleDescriptor d;
        try (InputStream in = Files.newInputStream(Path.of("target", "classes", "module-info.class"))) {
            d = ModuleDescriptor.read(in);
        }
        assertEquals("com.quantfinlib.io", d.name());
        assertEquals(Set.of("com.quantfinlib.io", "com.quantfinlib.report"), d.exports().stream()
                .map(ModuleDescriptor.Exports::source).collect(Collectors.toCollection(TreeSet::new)));
        assertEquals(Set.of("com.quantfinlib [transitive]", "java.base [mandated]", "java.net.http []",
                        "org.jspecify [static]"),
                d.requires().stream()
                        .map(r -> r.name() + " " + r.modifiers().stream()
                                .map(m -> m.name().toLowerCase(Locale.ROOT)).sorted().toList())
                        .collect(Collectors.toCollection(TreeSet::new)));
    }
}

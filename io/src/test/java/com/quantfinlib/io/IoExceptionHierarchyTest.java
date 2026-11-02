package com.quantfinlib.io;

import com.quantfinlib.core.MalformedDataException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The io loaders throw {@code MalformedDataException} (moved from core {@code ExceptionHierarchyTest} in 4.0). */
class IoExceptionHierarchyTest {

    @Test
    void csvLoadersThrowMalformedData() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of(), "X"));
        assertInstanceOf(MalformedDataException.class, e);
        e = assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of("foo,bar", "1,2"), "X"));
        assertInstanceOf(MalformedDataException.class, e);
        e = assertThrows(IllegalArgumentException.class, () -> UniverseCsvLoader.parse(List.of()));
        assertInstanceOf(MalformedDataException.class, e);
    }

}

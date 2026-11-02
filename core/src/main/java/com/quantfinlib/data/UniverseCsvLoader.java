package com.quantfinlib.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a {@link PointInTimeUniverse} from a CSV file in the format described
 * by {@link com.quantfinlib.io.UniverseCsvLoader}.
 *
 * @deprecated since 3.2.0: use {@link com.quantfinlib.io.UniverseCsvLoader},
 *     which has the same methods and behaviour. This class delegates to it
 *     and is removed in 4.0, where {@code com.quantfinlib.io} ships in the
 *     {@code quant-finance-library-io} artifact. See docs/MIGRATION_4.0.md.
 */
@Deprecated(since = "3.2.0", forRemoval = true)
public final class UniverseCsvLoader {

    private UniverseCsvLoader() {
    }

    /**
     * Loads a universe file.
     *
     * @param file the file
     * @return the universe
     * @throws IOException if the file cannot be read
     */
    public static PointInTimeUniverse load(Path file) throws IOException {
        return com.quantfinlib.io.UniverseCsvLoader.load(file);
    }

    /**
     * Parses universe lines, header first.
     *
     * @param lines the lines
     * @return the universe
     */
    public static PointInTimeUniverse parse(List<String> lines) {
        return com.quantfinlib.io.UniverseCsvLoader.parse(lines);
    }
}

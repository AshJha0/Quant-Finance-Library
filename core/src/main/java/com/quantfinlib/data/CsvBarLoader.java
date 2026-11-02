package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * CSV market data I/O for OHLCV bars.
 *
 * @deprecated since 3.2.0: use {@link com.quantfinlib.io.CsvBarLoader},
 *     which has the same methods and behaviour. This class delegates to it
 *     and is removed in 4.0, where {@code com.quantfinlib.io} ships in the
 *     {@code quant-finance-library-io} artifact. See docs/MIGRATION_4.0.md.
 */
@Deprecated(since = "3.2.0", forRemoval = true)
public final class CsvBarLoader {

    private CsvBarLoader() {
    }

    /**
     * Loads a CSV bar file.
     *
     * @param path   the file
     * @param symbol symbol of the returned series
     * @return the parsed series
     * @throws IOException if the file cannot be read
     */
    public static BarSeries load(Path path, String symbol) throws IOException {
        return com.quantfinlib.io.CsvBarLoader.load(path, symbol);
    }

    /**
     * Parses CSV bar lines, header first.
     *
     * @param lines  the lines
     * @param symbol symbol of the returned series
     * @return the parsed series
     */
    public static BarSeries parse(List<String> lines, String symbol) {
        return com.quantfinlib.io.CsvBarLoader.parse(lines, symbol);
    }

    /**
     * Saves a series as CSV bars.
     *
     * @param series the series
     * @param path   the target file
     * @throws IOException if the file cannot be written
     */
    public static void save(BarSeries series, Path path) throws IOException {
        com.quantfinlib.io.CsvBarLoader.save(series, path);
    }
}

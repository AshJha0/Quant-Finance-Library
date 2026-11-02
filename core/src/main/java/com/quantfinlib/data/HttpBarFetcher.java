package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Fetches OHLCV data over HTTP from any endpoint serving CSV bars and parses
 * it as CSV bars.
 *
 * @deprecated since 3.2.0: use {@link com.quantfinlib.io.HttpBarFetcher},
 *     which has the same constructors, method and behaviour. This class
 *     delegates to it and is removed in 4.0, where {@code com.quantfinlib.io}
 *     ships in the {@code quant-finance-library-io} artifact and core no
 *     longer requires {@code java.net.http}. See docs/MIGRATION_4.0.md.
 */
@Deprecated(since = "3.2.0", forRemoval = true)
public final class HttpBarFetcher {

    private final com.quantfinlib.io.HttpBarFetcher delegate;

    /** A fetcher with a 20 s connect and request timeout. */
    public HttpBarFetcher() {
        this.delegate = new com.quantfinlib.io.HttpBarFetcher();
    }

    /**
     * A fetcher with the given connect and request timeout.
     *
     * @param timeout connect and request timeout
     */
    public HttpBarFetcher(Duration timeout) {
        this.delegate = new com.quantfinlib.io.HttpBarFetcher(timeout);
    }

    /**
     * Downloads a CSV document and parses it into a series.
     *
     * @param uri    the CSV endpoint
     * @param symbol symbol of the returned series
     * @return the parsed series
     * @throws IOException on non-200 responses or malformed CSV
     * @throws InterruptedException if the request is interrupted
     */
    public BarSeries fetchCsv(URI uri, String symbol) throws IOException, InterruptedException {
        return delegate.fetchCsv(uri, symbol);
    }
}

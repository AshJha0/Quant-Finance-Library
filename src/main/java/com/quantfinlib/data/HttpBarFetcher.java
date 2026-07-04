package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Fetches OHLCV data over HTTP from any endpoint serving CSV bars (most free
 * market data APIs offer a CSV export) and parses it with
 * {@link CsvBarLoader}. Pure JDK {@code java.net.http} — no dependencies.
 */
public final class HttpBarFetcher {

    private final HttpClient client;
    private final Duration timeout;

    public HttpBarFetcher() {
        this(Duration.ofSeconds(20));
    }

    public HttpBarFetcher(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Downloads a CSV document and parses it into a series.
     *
     * @throws IOException on non-200 responses or malformed CSV
     */
    public BarSeries fetchCsv(URI uri, String symbol) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "quantfinlib/1.0")
                .header("Accept", "text/csv, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        List<String> lines = response.body().lines().toList();
        return CsvBarLoader.parse(lines, symbol);
    }
}

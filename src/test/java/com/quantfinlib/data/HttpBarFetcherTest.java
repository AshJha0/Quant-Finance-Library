package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpBarFetcherTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bars.csv", exchange -> {
            byte[] body = ("date,open,high,low,close,volume\n"
                    + "2024-01-02,100,102,99,101.5,1000\n"
                    + "2024-01-03,101.5,103,101,102.5,1100\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/csv");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void fetchesAndParsesCsvOverHttp() throws Exception {
        BarSeries series = new HttpBarFetcher()
                .fetchCsv(URI.create(base() + "/bars.csv"), "TEST");
        assertEquals(2, series.size());
        assertEquals(101.5, series.close(0), 0.0);
        assertEquals(102.5, series.lastClose(), 0.0);
        assertEquals(1_100, series.volume(1), 0.0);
    }

    @Test
    void nonOkResponsesFailLoudly() {
        HttpBarFetcher fetcher = new HttpBarFetcher();
        IOException e = assertThrows(IOException.class,
                () -> fetcher.fetchCsv(URI.create(base() + "/missing"), "TEST"));
        assertEquals(true, e.getMessage().contains("HTTP 404"));
    }
}

package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.util.LatencyRecorder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingDashboardTest {

    @Test
    void servesAccountStateAndLatencyAsJsonAndHtml() throws Exception {
        PaperTradingGateway gateway = new PaperTradingGateway(1_000_000);
        gateway.onQuote("EURUSD", 1.0848, 1.0852);
        gateway.submitMarket("EURUSD", Side.BUY, 10_000);

        LatencyRecorder recorder = new LatencyRecorder();
        recorder.record(204);
        recorder.record(408);

        try (TradingDashboard dashboard = new TradingDashboard(gateway, 0)
                .attachLatency("tickToSignal", recorder)) {
            dashboard.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + dashboard.port();

            HttpResponse<String> status = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            String json = status.body();
            assertTrue(json.contains("\"equity\":"), json);
            assertTrue(json.contains("\"EURUSD\":10000.0000"), json);
            assertTrue(json.contains("\"tickToSignal\":{\"count\":2"), json);
            assertTrue(json.contains("\"rejections\":0"), json);

            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("<html"));
            assertTrue(page.body().contains("live paper trading"));
        }
    }

    @Test
    void positionsSnapshotOmitsFlatSymbols() {
        PaperTradingGateway gateway = new PaperTradingGateway(100_000);
        gateway.onQuote("A", 100, 100);
        gateway.onQuote("B", 50, 50);
        gateway.submitMarket("A", Side.BUY, 10);
        gateway.submitMarket("B", Side.BUY, 5);
        gateway.submitMarket("B", Side.SELL, 5);   // flat again

        var snapshot = gateway.positionsSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals(10, snapshot.get("A"), 1e-9);
    }
}

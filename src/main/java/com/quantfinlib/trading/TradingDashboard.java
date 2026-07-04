package com.quantfinlib.trading;

import com.quantfinlib.util.LatencyRecorder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Zero-dependency live trading dashboard (JDK {@code com.sun.net.httpserver}):
 * serves a self-refreshing HTML page and a JSON status endpoint with the
 * paper-trading account (cash, equity, realized P&L, positions, rejections)
 * and any attached latency histograms — the whole live loop, observable in a
 * browser.
 */
public final class TradingDashboard implements AutoCloseable {

    private final HttpServer server;
    private final PaperTradingGateway gateway;
    private final Map<String, LatencyRecorder> latencyRecorders = new LinkedHashMap<>();

    /** @param port 0 = pick an ephemeral port (see {@link #port()}) */
    public TradingDashboard(PaperTradingGateway gateway, int port) throws IOException {
        this.gateway = gateway;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::servePage);
        server.createContext("/api/status", this::serveStatus);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trading-dashboard");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Attaches a latency histogram to the status payload (call before or after start). */
    public synchronized TradingDashboard attachLatency(String name, LatencyRecorder recorder) {
        latencyRecorders.put(name, recorder);
        return this;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------

    private void serveStatus(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder(512);
        json.append(String.format(Locale.ROOT,
                "{\"cash\":%.2f,\"equity\":%.2f,\"realizedPnl\":%.2f,\"rejections\":%d,",
                gateway.cash(), gateway.equity(), gateway.realizedPnl(),
                gateway.rejectionLog().size()));
        json.append("\"positions\":{");
        boolean first = true;
        for (Map.Entry<String, Double> p : gateway.positionsSnapshot().entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(escape(p.getKey())).append("\":")
                    .append(String.format(Locale.ROOT, "%.4f", p.getValue()));
            first = false;
        }
        json.append("},\"latency\":{");
        synchronized (this) {
            first = true;
            for (Map.Entry<String, LatencyRecorder> e : latencyRecorders.entrySet()) {
                LatencyRecorder r = e.getValue();
                if (!first) {
                    json.append(',');
                }
                json.append('"').append(escape(e.getKey())).append("\":")
                        .append(String.format(Locale.ROOT,
                                "{\"count\":%d,\"p50\":%d,\"p99\":%d,\"max\":%d}",
                                r.count(), r.percentile(0.50), r.percentile(0.99), r.max()));
                first = false;
            }
        }
        json.append("}}");
        respond(exchange, 200, "application/json", json.toString());
    }

    private void servePage(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/html; charset=utf-8", PAGE);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><title>quantfinlib dashboard</title>
            <style>
            body{font-family:'Segoe UI',Arial,sans-serif;margin:2rem;background:#f7f8fa;color:#1a1a2e}
            h1{border-bottom:3px solid #16a085;padding-bottom:.4rem}
            .cards{display:flex;gap:1rem;flex-wrap:wrap;margin:1rem 0}
            .card{background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.12);padding:.8rem 1.4rem;min-width:9rem}
            .card .label{color:#667;font-size:.8rem;text-transform:uppercase}
            .card .value{font-size:1.5rem;font-weight:600}
            table{border-collapse:collapse;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.12);margin-top:1rem}
            th{background:#16697a;color:#fff;text-align:left;padding:.4rem .9rem}
            td{padding:.35rem .9rem;border-bottom:1px solid #e3e6ea}
            </style></head><body>
            <h1>quantfinlib &mdash; live paper trading</h1>
            <div class="cards">
              <div class="card"><div class="label">Equity</div><div class="value" id="equity">&ndash;</div></div>
              <div class="card"><div class="label">Cash</div><div class="value" id="cash">&ndash;</div></div>
              <div class="card"><div class="label">Realized P&amp;L</div><div class="value" id="pnl">&ndash;</div></div>
              <div class="card"><div class="label">Rejections</div><div class="value" id="rejections">&ndash;</div></div>
            </div>
            <table id="positions"><tr><th>Symbol</th><th>Position</th></tr></table>
            <table id="latency"><tr><th>Path</th><th>count</th><th>p50 (ns)</th><th>p99 (ns)</th><th>max (ns)</th></tr></table>
            <script>
            const fmt = v => v.toLocaleString(undefined, {maximumFractionDigits: 2});
            async function refresh() {
              try {
                const s = await (await fetch('/api/status')).json();
                document.getElementById('equity').textContent = fmt(s.equity);
                document.getElementById('cash').textContent = fmt(s.cash);
                document.getElementById('pnl').textContent = fmt(s.realizedPnl);
                document.getElementById('rejections').textContent = s.rejections;
                const pos = document.getElementById('positions');
                pos.innerHTML = '<tr><th>Symbol</th><th>Position</th></tr>' +
                  Object.entries(s.positions).map(([k,v]) => `<tr><td>${k}</td><td>${fmt(v)}</td></tr>`).join('');
                const lat = document.getElementById('latency');
                lat.innerHTML = '<tr><th>Path</th><th>count</th><th>p50 (ns)</th><th>p99 (ns)</th><th>max (ns)</th></tr>' +
                  Object.entries(s.latency).map(([k,v]) =>
                    `<tr><td>${k}</td><td>${v.count}</td><td>${v.p50}</td><td>${v.p99}</td><td>${v.max}</td></tr>`).join('');
              } catch (e) { /* server stopping */ }
            }
            refresh(); setInterval(refresh, 1000);
            </script></body></html>""";
}

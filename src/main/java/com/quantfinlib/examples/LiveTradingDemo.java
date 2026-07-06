package com.quantfinlib.examples;

import com.quantfinlib.feed.BinanceTradeParser;
import com.quantfinlib.feed.WebSocketFeed;
import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.PaperTradingGateway;
import com.quantfinlib.trading.TradingDashboard;
import com.quantfinlib.util.LatencyRecorder;

import java.io.IOException;
import java.time.Duration;

/**
 * The five-minute live demo: real market data → streaming strategy → paper
 * execution → live dashboard, in one command.
 *
 * <pre>
 *   java -cp target/classes com.quantfinlib.examples.LiveTradingDemo
 *   → open http://localhost:8080
 * </pre>
 *
 * <p>What it wires (public crypto data — no keys, no cost, no license):</p>
 * <ol>
 *   <li>Binance trade stream (BTC + ETH) → {@link WebSocketFeed} →
 *       {@link HftMarketDataBus};</li>
 *   <li>a streaming EMA(20/60) crossover per symbol on the bus consumer
 *       thread, with tick-to-decision latency recorded;</li>
 *   <li>signals → {@link PaperTradingGateway} market orders ($100k paper
 *       account, real commission accounting);</li>
 *   <li>positions/P&amp;L/fills/latency live at
 *       <a href="http://localhost:8080">localhost:8080</a> via
 *       {@link TradingDashboard}.</li>
 * </ol>
 *
 * <p>This is the SIMULATION lane end to end on live data: convenient
 * String-keyed paper venue, synchronized accounting — the point is seeing
 * the whole loop run, not nanoseconds. The measured hot lane is the same
 * shape with {@code HftOrderGateway}/{@code HftQuoter} instead of the paper
 * venue (see the benchmarks). Runs until Ctrl-C; prints a session summary
 * on exit. No network? It says so and exits politely.</p>
 */
public final class LiveTradingDemo {

    private static final String[] SYMBOLS = {"BTCUSDT", "ETHUSDT"};
    private static final long TRADE_QTY = 1;         // paper units per signal
    private static final double SYNTHETIC_SPREAD = 0.0002; // paper venue bid/ask

    public static void main(String[] args) throws Exception {
        PaperTradingGateway paper = new PaperTradingGateway(100_000);
        LatencyRecorder decisionLatency = new LatencyRecorder();

        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 8, false);
             TradingDashboard dashboard = new TradingDashboard(paper, 8080)) {
            dashboard.attachLatency("tick-to-decision", decisionLatency);

            // One streaming strategy per symbol, wired on the bus consumer.
            for (String symbol : SYMBOLS) {
                int id = bus.registerSymbol(symbol);
                StreamingIndicators.Ema fast = new StreamingIndicators.Ema(20);
                StreamingIndicators.Ema slow = new StreamingIndicators.Ema(60);
                // -1 flat/short-signal, +1 long-signal: trade only on flips.
                int[] stance = {0};
                bus.subscribe(id, (sym, price, size, tsNanos) -> {
                    // Feed the paper venue a synthetic top-of-book around
                    // the trade print so limit/market fills are realistic.
                    double half = price * SYNTHETIC_SPREAD / 2;
                    paper.onQuote(symbol, price - half, price + half);

                    double f = fast.update(price);
                    double s = slow.update(price);
                    if (Double.isNaN(s)) {
                        return; // warm-up
                    }
                    int want = f > s ? 1 : -1;
                    if (want != stance[0]) {
                        stance[0] = want;
                        // Flip the paper position: close + reverse.
                        double pos = paper.position(symbol);
                        long qty = TRADE_QTY + (long) Math.abs(pos);
                        paper.submitMarket(symbol, want > 0 ? Side.BUY : Side.SELL, qty);
                        System.out.printf("%s %s x%d @ ~%.2f (fast %.2f / slow %.2f)%n",
                                symbol, want > 0 ? "BUY" : "SELL", qty, price, f, s);
                    }
                    decisionLatency.record(System.nanoTime() - tsNanos);
                });
            }

            try (WebSocketFeed feed = new WebSocketFeed(
                    BinanceTradeParser.streamUri(SYMBOLS), new BinanceTradeParser(), bus)
                    .withReconnect(10, 1_000)) {

                bus.start();
                dashboard.start();
                try {
                    feed.start(Duration.ofSeconds(10));
                } catch (IOException e) {
                    System.out.println("Could not reach the Binance stream (" + e.getMessage()
                            + ") — check connectivity/firewall and retry.");
                    return;
                }
                System.out.println("Live: streaming " + String.join(", ", SYMBOLS)
                        + " — dashboard at http://localhost:" + dashboard.port()
                        + "  (Ctrl-C to stop)");

                // Run until interrupted; print a heartbeat once a minute.
                while (true) {
                    Thread.sleep(60_000);
                    System.out.printf("ticks=%d  equity=%.2f  realizedPnl=%.2f  latency: %s%n",
                            bus.processedCount(), paper.equity(), paper.realizedPnl(),
                            decisionLatency.summary());
                }
            }
        } finally {
            System.out.printf("Session summary: equity=%.2f realizedPnl=%.2f cash=%.2f%n",
                    paper.equity(), paper.realizedPnl(), paper.cash());
        }
    }
}

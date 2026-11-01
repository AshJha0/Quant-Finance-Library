package com.quantfinlib.backtest;

import com.quantfinlib.backtest.tick.TickBacktester;
import com.quantfinlib.backtest.tick.TickStrategy;
import com.quantfinlib.backtest.tick.TickTradingContext;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.TickFileReader;
import com.quantfinlib.data.TickFileWriter;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.PaperTradingGateway;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One driver for a bar {@link TradingStrategy} on three engines: the bar
 * {@link Backtester}, the tick-level {@link TickBacktester} and the
 * {@link PaperTradingGateway} used for paper and pre-production runs. The
 * same strategy code, on the same tape, should produce the same orders and
 * fills everywhere; a difference points at an engine, not at the
 * strategy.
 *
 * <p><b>Tape.</b> A {@link BarSeries}; for the tick engines it is written as
 * a QFLT tick file ({@link #writeTape}) with one print per bar at the
 * close, timestamp equal to the bar index, size equal to the bar volume.
 * The paper gateway sees each print as a zero-spread quote
 * {@code bid = ask = close}.</p>
 *
 * <p><b>Parity semantics.</b> The strategy is asked once per bar, after the
 * close, with the quantity held. A {@code BUY} while flat buys with all
 * cash, a {@code SELL} while long sells everything, both at that close; an
 * open position is sold at the last close. These are the {@link Backtester}
 * rules under {@link FillPolicy#CLOSE} with no costs, the only setting in
 * which the three engines share a fill model: the bar engine buys
 * {@code cash/price} shares (fractional), while the tick and paper engines
 * trade whole shares, {@code floor(cash/price)}. Fills agree exactly when
 * every entry's {@code cash/price} is whole; otherwise they differ by the
 * fractional remainder, and the comparison is of order times, sides and
 * prices. Costs, spreads, limit orders and queue models are engine-specific
 * and are not part of the parity contract.</p>
 */
public final class StrategyRunner {

    /** The engine to drive. */
    public enum Engine {
        BAR_BACKTESTER, TICK_BACKTESTER, PAPER_GATEWAY
    }

    /**
     * One fill.
     *
     * @param index    bar index of the fill
     * @param side     buy or sell
     * @param price    fill price
     * @param quantity shares (fractional only on the bar engine)
     */
    public record Fill(int index, Side side, double price, double quantity) {
        public Fill {
            Objects.requireNonNull(side, "side");
        }
    }

    /** The fills of one run, in time order, and the final equity. */
    public static final class Run {
        private final Engine engine;
        private final List<Fill> fills;
        private final double finalEquity;

        Run(Engine engine, List<Fill> fills, double finalEquity) {
            this.engine = engine;
            this.fills = List.copyOf(fills);
            this.finalEquity = finalEquity;
        }

        public Engine engine() {
            return engine;
        }

        public List<Fill> fills() {
            return fills;
        }

        public double finalEquity() {
            return finalEquity;
        }

        @Override
        public String toString() {
            return "Run[" + engine + ", fills=" + fills.size() + ", equity=" + finalEquity + "]";
        }
    }

    private StrategyRunner() {
    }

    /** Writes {@code series} as a single-symbol QFLT tape (symbol id 0). */
    public static void writeTape(BarSeries series, Path file) throws IOException {
        try (TickFileWriter w = new TickFileWriter(file)) {
            w.defineSymbol(0, series.symbol());
            for (int i = 0; i < series.size(); i++) {
                w.write(0, series.close(i), series.volume(i), i);
            }
        }
    }

    /**
     * Runs {@code strategy} on {@code tape} with {@code capital} on the
     * chosen engine. The tick engines need {@code workDir} for the tape
     * file (ignored by the bar engine).
     */
    public static Run run(Engine engine, TradingStrategy strategy, BarSeries tape,
                          double capital, Path workDir) throws IOException {
        if (!(capital > 0) || tape.size() == 0) {
            throw new IllegalArgumentException("need capital > 0 and a non-empty tape");
        }
        return switch (engine) {
            case BAR_BACKTESTER -> runBars(strategy, tape, capital);
            case TICK_BACKTESTER -> runTicks(strategy, tape, capital, tapeFile(tape, workDir));
            case PAPER_GATEWAY -> runPaper(strategy, tape, capital, tapeFile(tape, workDir));
        };
    }

    private static Path tapeFile(BarSeries tape, Path workDir) throws IOException {
        Path file = workDir.resolve("tape-" + tape.symbol() + ".qflt");
        writeTape(tape, file);
        return file;
    }

    private static Run runBars(TradingStrategy strategy, BarSeries tape, double capital) {
        BacktestConfig cfg = new BacktestConfig(capital, 0, 0, 0, 0, 252, FillPolicy.CLOSE);
        BacktestResult r = Backtester.run(withoutStops(strategy), tape, cfg);
        List<Fill> fills = new ArrayList<>();
        for (Trade t : r.trades()) {
            fills.add(new Fill(t.entryIndex(), Side.BUY, t.entryPrice(), t.quantity()));
            fills.add(new Fill(t.exitIndex(), Side.SELL, t.exitPrice(), t.quantity()));
        }
        // stable: an entry and an exit on the same bar keep buy-then-sell order
        fills.sort(Comparator.comparingInt(Fill::index));
        return new Run(Engine.BAR_BACKTESTER, fills, r.equityCurve(r.equityCurveLength() - 1));
    }

    /** The parity contract has no stops: they are an engine feature of the bar backtester. */
    private static TradingStrategy withoutStops(TradingStrategy s) {
        if (s.stopLossPct() != 0 || s.takeProfitPct() != 0) {
            throw new IllegalArgumentException("stops are not part of the parity contract");
        }
        return s;
    }

    /** Shared decision logic for the tick engines: signal, then an order sized like the bar engine. */
    private abstract static class Driver {
        final TradingStrategy strategy;
        final int last;

        Driver(TradingStrategy strategy, BarSeries tape) {
            this.strategy = strategy;
            this.last = tape.size() - 1;
            withoutStops(strategy).init(tape);
        }

        abstract double position();

        abstract double cash();

        abstract void market(Side side, long quantity);

        void onBar(int index, double price) {
            double pos = position();
            Signal sig = strategy.onBar(index, pos);
            if (sig == Signal.BUY && pos == 0) {
                long q = (long) Math.floor(cash() / price);
                if (q > 0) {
                    market(Side.BUY, q);
                }
            } else if (sig == Signal.SELL && pos > 0) {
                market(Side.SELL, Math.round(pos));
            }
            if (index == last && position() > 0) {
                market(Side.SELL, Math.round(position()));
            }
        }
    }

    private static Run runTicks(TradingStrategy strategy, BarSeries tape, double capital,
                                Path file) throws IOException {
        TickStrategy adapter = new TickStrategy() {
            Driver driver;

            @Override
            public String name() {
                return strategy.name();
            }

            @Override
            public void init(TickTradingContext ctx) {
                driver = new Driver(strategy, tape) {
                    @Override
                    double position() {
                        return ctx.position(0);
                    }

                    @Override
                    double cash() {
                        return ctx.cash();
                    }

                    @Override
                    void market(Side side, long quantity) {
                        ctx.submitMarket(0, side, quantity);
                    }
                };
            }

            @Override
            public void onTick(int symbolId, double price, double size, long timestampNanos) {
                driver.onBar((int) timestampNanos, price);
            }
        };
        TickBacktester.Config cfg = new TickBacktester.Config(capital, 0, 0, 0, 1, 0, null);
        TickBacktester.TickBacktestResult r = TickBacktester.run(adapter, file, cfg);
        List<Fill> fills = new ArrayList<>();
        for (Execution e : r.fills()) {
            fills.add(new Fill((int) e.timestampNanos(), e.side(), e.price(), e.quantity()));
        }
        return new Run(Engine.TICK_BACKTESTER, fills, r.finalEquity());
    }

    private static Run runPaper(TradingStrategy strategy, BarSeries tape, double capital,
                                Path file) throws IOException {
        PaperTradingGateway gw = new PaperTradingGateway(capital);
        String symbol = tape.symbol();
        List<Fill> fills = new ArrayList<>();
        int[] now = new int[1];
        gw.addExecutionListener((id, sym, side, price, qty, ts) ->
                fills.add(new Fill(now[0], side, price, qty)));
        Driver driver = new Driver(strategy, tape) {
            @Override
            double position() {
                return gw.position(symbol);
            }

            @Override
            double cash() {
                return gw.cash();
            }

            @Override
            void market(Side side, long quantity) {
                gw.submitMarket(symbol, side, quantity);
            }
        };
        TickFileReader.replay(file, new TickFileReader.ReplayHandler() {
            @Override
            public void onSymbol(int symbolId, String name) {
                // single-symbol tape: the gateway is keyed by the series symbol
            }

            @Override
            public void onTick(int symbolId, double price, double size, long timestampNanos) {
                now[0] = (int) timestampNanos;
                gw.onQuote(symbol, price, price);
                driver.onBar(now[0], price);
            }
        });
        return new Run(Engine.PAPER_GATEWAY, fills, gw.equity());
    }
}

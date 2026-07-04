package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.risk.PreTradeLimitChecker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperTradingTest {

    @Test
    void marketOrdersFillAtTheTouch() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000);
        gw.onQuote("EURUSD", 1.0848, 1.0852);

        AtomicInteger fills = new AtomicInteger();
        gw.addExecutionListener((id, sym, side, px, qty, ts) -> {
            fills.incrementAndGet();
            assertEquals(1.0852, px, 1e-12);   // buy pays the ask
        });
        long id = gw.submitMarket("EURUSD", Side.BUY, 10_000);

        assertEquals(OrderStatus.FILLED, gw.status(id));
        assertEquals(1, fills.get());
        assertEquals(10_000, gw.position("EURUSD"), 1e-9);
        assertEquals(100_000 - 10_000 * 1.0852, gw.cash(), 1e-6);
        // Equity marked at mid.
        assertEquals(gw.cash() + 10_000 * 1.0850, gw.equity(), 1e-6);
    }

    @Test
    void limitOrdersRestAndFillWhenMarketCrosses() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000);
        gw.onQuote("AAPL", 99.98, 100.02);

        long id = gw.submitLimit("AAPL", Side.BUY, 100, 99.50);   // passive
        assertEquals(OrderStatus.NEW, gw.status(id));
        assertEquals(0, gw.position("AAPL"), 1e-9);

        gw.onQuote("AAPL", 99.30, 99.40);                          // market drops through
        assertEquals(OrderStatus.FILLED, gw.status(id));
        assertEquals(100, gw.position("AAPL"), 1e-9);

        // Marketable limit fills immediately at the (better) touch.
        long id2 = gw.submitLimit("AAPL", Side.BUY, 50, 101);
        assertEquals(OrderStatus.FILLED, gw.status(id2));
    }

    @Test
    void cancelOnlyWorksBeforeFill() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000);
        gw.onQuote("X", 99, 101);
        long resting = gw.submitLimit("X", Side.BUY, 10, 95);
        assertTrue(gw.cancel(resting));
        assertEquals(OrderStatus.CANCELED, gw.status(resting));
        assertFalse(gw.cancel(resting));

        long filled = gw.submitMarket("X", Side.BUY, 10);
        assertFalse(gw.cancel(filled));
        // Canceled resting order must not fill later.
        gw.onQuote("X", 90, 91);
        assertEquals(0 + 10, gw.position("X"), 1e-9);
    }

    @Test
    void roundTripRealizesPnlWithAverageCosting() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000);
        gw.onQuote("X", 99.99, 100.01);
        gw.submitMarket("X", Side.BUY, 100);      // buy at 100.01
        gw.onQuote("X", 109.99, 110.01);
        gw.submitMarket("X", Side.SELL, 100);     // sell at 109.99

        assertEquals(0, gw.position("X"), 1e-9);
        assertEquals(100 * (109.99 - 100.01), gw.realizedPnl(), 1e-6);
        assertEquals(100_000 + gw.realizedPnl(), gw.cash(), 1e-6);
    }

    @Test
    void shortsAndPositionFlipsAccountCorrectly() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000);
        gw.onQuote("X", 100, 100);                // zero spread for clean numbers
        gw.submitMarket("X", Side.SELL, 50);      // open short 50 @ 100
        assertEquals(-50, gw.position("X"), 1e-9);

        gw.onQuote("X", 90, 90);
        gw.submitMarket("X", Side.BUY, 80);       // close 50 (+10*50 pnl), flip long 30 @ 90
        assertEquals(30, gw.position("X"), 1e-9);
        assertEquals(500, gw.realizedPnl(), 1e-6);
    }

    @Test
    void riskGateRejectsBeforeTheMarket() {
        PreTradeLimitChecker checker = new PreTradeLimitChecker()
                .maxOrderQuantity(1_000)
                .restrictSymbol("BANNED");
        PaperTradingGateway gw = new PaperTradingGateway(100_000, 0, checker);
        gw.onQuote("OK", 99, 101);
        gw.onQuote("BANNED", 99, 101);

        long tooBig = gw.submitMarket("OK", Side.BUY, 5_000);
        assertEquals(OrderStatus.REJECTED, gw.status(tooBig));
        long banned = gw.submitMarket("BANNED", Side.BUY, 10);
        assertEquals(OrderStatus.REJECTED, gw.status(banned));
        long fine = gw.submitMarket("OK", Side.BUY, 500);
        assertEquals(OrderStatus.FILLED, gw.status(fine));

        assertEquals(2, gw.rejectionLog().size());
        assertEquals(0, gw.position("BANNED"), 1e-9);
    }

    @Test
    void marketOrderWithoutQuoteIsRejected() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000);
        long id = gw.submitMarket("UNKNOWN", Side.BUY, 10);
        assertEquals(OrderStatus.REJECTED, gw.status(id));
    }

    @Test
    void commissionReducesCashOnly() {
        PaperTradingGateway gw = new PaperTradingGateway(100_000, 0.001, null);
        gw.onQuote("X", 100, 100);
        gw.submitMarket("X", Side.BUY, 100);
        assertEquals(100_000 - 10_000 - 10, gw.cash(), 1e-6);   // notional + 10bps fee
    }
}

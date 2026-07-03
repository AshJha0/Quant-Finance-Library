package com.quantfinlib.orderbook;

import java.util.List;

/**
 * Spread, depth and liquidity analytics over an {@link OrderBook}: quoted
 * spread, size-weighted microprice, depth imbalance, depth-within-bps, and
 * non-destructive book sweep simulation (VWAP-to-fill and impact of a large
 * marketable order).
 */
public final class BookAnalytics {

    /** Result of simulating a sweep: what a marketable order of that size would pay. */
    public record SweepResult(long filledQty, double avgPrice, double impactBps, int levelsConsumed) {
    }

    private BookAnalytics() {
    }

    public static double spreadBps(OrderBook book) {
        double mid = book.mid();
        return Double.isNaN(mid) || mid == 0 ? Double.NaN : book.spread() / mid * 1e4;
    }

    /**
     * Size-weighted microprice: {@code I*ask + (1-I)*bid} with
     * {@code I = bidSize / (bidSize + askSize)} — a better short-horizon fair
     * value than the mid when the book is imbalanced.
     */
    public static double microprice(OrderBook book) {
        return microprice(book.bestBid(), book.bestAsk(), book.bestBidSize(), book.bestAskSize());
    }

    public static double microprice(double bid, double ask, double bidSize, double askSize) {
        double total = bidSize + askSize;
        if (total <= 0 || Double.isNaN(bid) || Double.isNaN(ask)) {
            return Double.NaN;
        }
        double i = bidSize / total;
        return i * ask + (1 - i) * bid;
    }

    /** Depth imbalance in [-1, 1] over the top {@code levels}: +1 = all bid. */
    public static double imbalance(OrderBook book, int levels) {
        double bid = book.depth(Side.BUY, levels);
        double ask = book.depth(Side.SELL, levels);
        double total = bid + ask;
        return total == 0 ? 0 : (bid - ask) / total;
    }

    /** Resting quantity on a side priced within {@code bps} of the mid. */
    public static long depthWithinBps(OrderBook book, Side side, double bps) {
        double mid = book.mid();
        if (Double.isNaN(mid)) {
            return 0;
        }
        long total = 0;
        for (double[] level : book.levels(side, Integer.MAX_VALUE)) {
            if (Math.abs(level[0] - mid) / mid * 1e4 <= bps) {
                total += (long) level[1];
            }
        }
        return total;
    }

    /**
     * Simulates (without mutating the book) sweeping {@code quantity} with a
     * marketable order on {@code takerSide}: returns achievable fill, VWAP
     * fill price, and impact versus the pre-sweep mid.
     */
    public static SweepResult sweep(OrderBook book, Side takerSide, long quantity) {
        double mid = book.mid();
        List<double[]> liquidity = book.levels(takerSide.opposite(), Integer.MAX_VALUE);
        long remaining = quantity;
        double notional = 0;
        int levelsUsed = 0;
        for (double[] level : liquidity) {
            if (remaining <= 0) {
                break;
            }
            long take = Math.min(remaining, (long) level[1]);
            notional += take * level[0];
            remaining -= take;
            levelsUsed++;
        }
        long filled = quantity - remaining;
        if (filled == 0) {
            return new SweepResult(0, Double.NaN, Double.NaN, 0);
        }
        double avg = notional / filled;
        double impact = Double.isNaN(mid) ? Double.NaN : takerSide.sign() * (avg - mid) / mid * 1e4;
        return new SweepResult(filled, avg, impact, levelsUsed);
    }
}

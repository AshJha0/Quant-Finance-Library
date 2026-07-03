package com.quantfinlib.microstructure;

import com.quantfinlib.orderbook.Side;

import java.util.List;

/**
 * Transaction Cost Analysis: benchmarks matched trades against the arrival
 * mid, the interval market VWAP, and the prevailing mid at each fill
 * (effective spread). All costs are signed so positive = cost to the trader,
 * for both buys and sells.
 */
public final class TransactionCostAnalyzer {

    public record TcaReport(
            long totalQuantity,
            double avgExecutionPrice,
            double arrivalMid,
            double marketVwap,
            double implementationShortfallBps,
            double slippageVsVwapBps,
            double avgEffectiveSpreadBps) {
    }

    private TransactionCostAnalyzer() {
    }

    /**
     * @param fills     the child fills of one parent order (all same side)
     * @param arrivalMid market mid when the parent order was created
     * @param marketVwap market VWAP over the execution interval (or a
     *                   synthetic forward benchmark for FX forwards/swaps)
     * @param midAtFill  prevailing mid at each fill, aligned with {@code fills}
     */
    public static TcaReport analyze(List<Execution> fills, double arrivalMid,
                                    double marketVwap, double[] midAtFill) {
        if (fills.isEmpty()) {
            throw new IllegalArgumentException("no fills");
        }
        if (midAtFill.length != fills.size()) {
            throw new IllegalArgumentException("midAtFill must align with fills");
        }
        Side side = fills.getFirst().side();
        int sign = side.sign();

        long qty = 0;
        double notional = 0;
        double effSpreadWeighted = 0;
        for (int i = 0; i < fills.size(); i++) {
            Execution f = fills.get(i);
            qty += f.quantity();
            notional += f.notional();
            effSpreadWeighted += 2.0 * sign * (f.price() - midAtFill[i]) / midAtFill[i] * 1e4 * f.quantity();
        }
        double vwapExec = notional / qty;

        return new TcaReport(
                qty,
                vwapExec,
                arrivalMid,
                marketVwap,
                sign * (vwapExec - arrivalMid) / arrivalMid * 1e4,
                sign * (vwapExec - marketVwap) / marketVwap * 1e4,
                effSpreadWeighted / qty);
    }
}

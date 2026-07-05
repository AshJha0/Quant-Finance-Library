/**
 * Market microstructure analytics:
 * {@link com.quantfinlib.microstructure.MarketImpactModel} (square-root law
 * + Almgren-Chriss temporary/permanent decomposition),
 * {@link com.quantfinlib.microstructure.AlmgrenChriss} (closed-form optimal
 * execution trajectories on the cost/risk frontier),
 * {@link com.quantfinlib.microstructure.QueueModel} (fill probability from
 * queue position and latency advantage) and
 * {@link com.quantfinlib.microstructure.TransactionCostAnalyzer}
 * (implementation shortfall, slippage vs VWAP, effective spread per fill).
 */
package com.quantfinlib.microstructure;

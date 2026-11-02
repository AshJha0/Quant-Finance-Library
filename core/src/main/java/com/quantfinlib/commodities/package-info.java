/**
 * Commodity futures curves: roll yield, implied carry and curve shape.
 * {@link com.quantfinlib.commodities.CommodityCurve} holds a spot +
 * futures pillar curve and computes annualized roll yield (positive for
 * a long in backwardation, negative in contango),
 * the market-implied storage-minus-convenience carry from the
 * cash-and-carry relation {@code F = S e^{(r+u-y)t}}, and strict
 * whole-curve shape tests. Pairs with
 * {@code pricing.ExchangeOption.kirkSpreadCall} for spread options
 * (crack/calendar) and {@code execution.FuturesRollAlgo} for
 * rolling the position. Research lane.
 */
@NullMarked
package com.quantfinlib.commodities;

import org.jspecify.annotations.NullMarked;

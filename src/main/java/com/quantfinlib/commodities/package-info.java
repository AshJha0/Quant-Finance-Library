/**
 * Commodities: the asset class where the CURVE is the trade.
 * {@link com.quantfinlib.commodities.CommodityCurve} holds a spot +
 * futures pillar curve and produces the numbers that actually drive
 * commodity P&amp;L — annualized roll yield (positive in backwardation,
 * the long's silent tailwind; negative in contango, the silent tax),
 * the market-implied storage-minus-convenience carry from the
 * cash-and-carry relation {@code F = S e^{(r+u-y)t}}, and strict
 * whole-curve shape tests. Pairs with
 * {@code pricing.ExchangeOption.kirkSpreadCall} for spread options
 * (crack/calendar) and {@code execution.FuturesRollAlgo} for actually
 * rolling the position. Research lane.
 */
package com.quantfinlib.commodities;

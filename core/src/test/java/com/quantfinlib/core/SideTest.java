package com.quantfinlib.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link Side} (was {@code orderbook.Side} up to 2.4): sign and opposite. */
class SideTest {

    @Test
    void signIsPlusOneForBuyAndMinusOneForSell() {
        assertEquals(1, Side.BUY.sign());
        assertEquals(-1, Side.SELL.sign());
    }

    @Test
    void oppositeSwapsTheSide() {
        assertEquals(Side.SELL, Side.BUY.opposite());
        assertEquals(Side.BUY, Side.SELL.opposite());
    }
}

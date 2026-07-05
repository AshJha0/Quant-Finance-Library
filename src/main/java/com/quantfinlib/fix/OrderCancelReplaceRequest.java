package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;

/**
 * Typed view of a FIX OrderCancelReplaceRequest (35=G) — amend a working
 * order's quantity and/or price. {@code price} is NaN for market (40=1).
 */
public record OrderCancelReplaceRequest(String clOrdId, String origClOrdId, String symbol,
                                        Side side, long quantity, char ordType,
                                        double price, char timeInForce) {

    public boolean isMarket() {
        return ordType == NewOrderSingle.ORD_TYPE_MARKET;
    }

    static OrderCancelReplaceRequest fromMessage(FixMessage m) {
        return new OrderCancelReplaceRequest(
                m.getString(FixMessage.CL_ORD_ID),
                m.getString(FixMessage.ORIG_CL_ORD_ID),
                m.getString(FixMessage.SYMBOL),
                m.getChar(FixMessage.SIDE) == '1' ? Side.BUY : Side.SELL,
                m.getLong(FixMessage.ORDER_QTY),
                m.getChar(FixMessage.ORD_TYPE),
                m.getDouble(FixMessage.PRICE, Double.NaN),
                m.has(FixMessage.TIME_IN_FORCE)
                        ? m.getChar(FixMessage.TIME_IN_FORCE) : NewOrderSingle.TIF_DAY);
    }
}

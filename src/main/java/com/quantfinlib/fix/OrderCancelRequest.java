package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;

/** Typed view of a FIX OrderCancelRequest (35=F). */
public record OrderCancelRequest(String clOrdId, String origClOrdId, String symbol,
                                 Side side, long quantity) {

    static OrderCancelRequest fromMessage(FixMessage m) {
        return new OrderCancelRequest(
                m.getString(FixMessage.CL_ORD_ID),
                m.getString(FixMessage.ORIG_CL_ORD_ID),
                m.getString(FixMessage.SYMBOL),
                m.getChar(FixMessage.SIDE) == '1' ? Side.BUY : Side.SELL,
                m.getLong(FixMessage.ORDER_QTY));
    }
}

package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;

/**
 * Typed view of a FIX NewOrderSingle (35=D). {@code price} is NaN for market
 * orders (40=1); limit orders carry 40=2 and a price.
 */
public record NewOrderSingle(String clOrdId, String symbol, Side side, long quantity,
                             char ordType, double price, char timeInForce) {

    public static final char ORD_TYPE_MARKET = '1';
    public static final char ORD_TYPE_LIMIT = '2';
    public static final char TIF_DAY = '0';
    public static final char TIF_IOC = '3';

    public boolean isMarket() {
        return ordType == ORD_TYPE_MARKET;
    }

    static NewOrderSingle fromMessage(FixMessage m) {
        return new NewOrderSingle(
                m.getString(FixMessage.CL_ORD_ID),
                m.getString(FixMessage.SYMBOL),
                m.getChar(FixMessage.SIDE) == '1' ? Side.BUY : Side.SELL,
                m.getLong(FixMessage.ORDER_QTY),
                m.getChar(FixMessage.ORD_TYPE),
                m.getDouble(FixMessage.PRICE, Double.NaN),
                m.has(FixMessage.TIME_IN_FORCE) ? m.getChar(FixMessage.TIME_IN_FORCE) : TIF_DAY);
    }
}

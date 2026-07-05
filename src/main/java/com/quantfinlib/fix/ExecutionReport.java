package com.quantfinlib.fix;

import com.quantfinlib.orderbook.Side;

/**
 * Typed view of a FIX ExecutionReport (35=8) — the venue's answer to an
 * order: acknowledgement, fill, or rejection.
 */
public record ExecutionReport(String orderId, String execId, char execType, char ordStatus,
                              String clOrdId, String symbol, Side side,
                              double lastQty, double lastPrice,
                              double cumQty, double leavesQty, double avgPrice) {

    public static final char EXEC_TYPE_NEW = '0';
    public static final char EXEC_TYPE_CANCELED = '4';
    public static final char EXEC_TYPE_REPLACED = '5';
    public static final char EXEC_TYPE_TRADE = 'F';
    public static final char EXEC_TYPE_REJECTED = '8';
    public static final char ORD_STATUS_NEW = '0';
    public static final char ORD_STATUS_PARTIALLY_FILLED = '1';
    public static final char ORD_STATUS_FILLED = '2';
    public static final char ORD_STATUS_CANCELED = '4';
    public static final char ORD_STATUS_REPLACED = '5';
    public static final char ORD_STATUS_REJECTED = '8';

    public boolean isFill() {
        return execType == EXEC_TYPE_TRADE;
    }

    /** Venue-side convenience: acknowledge a new order. */
    public static ExecutionReport accepted(NewOrderSingle order, String orderId, String execId) {
        return new ExecutionReport(orderId, execId, EXEC_TYPE_NEW, ORD_STATUS_NEW,
                order.clOrdId(), order.symbol(), order.side(),
                0, 0, 0, order.quantity(), 0);
    }

    /** Venue-side convenience: full fill at one price. */
    public static ExecutionReport filled(NewOrderSingle order, String orderId, String execId,
                                         double fillPrice) {
        return new ExecutionReport(orderId, execId, EXEC_TYPE_TRADE, ORD_STATUS_FILLED,
                order.clOrdId(), order.symbol(), order.side(),
                order.quantity(), fillPrice, order.quantity(), 0, fillPrice);
    }

    /** Venue-side convenience: confirm a cancel ({@code cumQty} already executed). */
    public static ExecutionReport canceled(OrderCancelRequest request, String orderId,
                                           String execId, double cumQty) {
        return new ExecutionReport(orderId, execId, EXEC_TYPE_CANCELED, ORD_STATUS_CANCELED,
                request.clOrdId(), request.symbol(), request.side(),
                0, 0, cumQty, 0, 0);
    }

    /** Venue-side convenience: confirm a cancel/replace with the new working quantity. */
    public static ExecutionReport replaced(OrderCancelReplaceRequest request, String orderId,
                                           String execId, double cumQty) {
        return new ExecutionReport(orderId, execId, EXEC_TYPE_REPLACED, ORD_STATUS_REPLACED,
                request.clOrdId(), request.symbol(), request.side(),
                0, 0, cumQty, request.quantity() - cumQty, 0);
    }

    static ExecutionReport fromMessage(FixMessage m) {
        return new ExecutionReport(
                m.getString(FixMessage.ORDER_ID),
                m.getString(FixMessage.EXEC_ID),
                m.getChar(FixMessage.EXEC_TYPE),
                m.getChar(FixMessage.ORD_STATUS),
                m.getString(FixMessage.CL_ORD_ID, ""),
                m.getString(FixMessage.SYMBOL),
                m.getChar(FixMessage.SIDE) == '1' ? Side.BUY : Side.SELL,
                m.getDouble(FixMessage.LAST_QTY, 0),
                m.getDouble(FixMessage.LAST_PX, 0),
                m.getDouble(FixMessage.CUM_QTY, 0),
                m.getDouble(FixMessage.LEAVES_QTY, 0),
                m.getDouble(FixMessage.AVG_PX, 0));
    }

    FixMessage.Builder toBuilder() {
        return FixMessage.builder(FixMessage.EXECUTION_REPORT)
                .field(FixMessage.ORDER_ID, orderId)
                .field(FixMessage.EXEC_ID, execId)
                .field(FixMessage.EXEC_TYPE, execType)
                .field(FixMessage.ORD_STATUS, ordStatus)
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, symbol)
                .field(FixMessage.SIDE, side == Side.BUY ? '1' : '2')
                .field(FixMessage.LAST_QTY, lastQty)
                .field(FixMessage.LAST_PX, lastPrice)
                .field(FixMessage.CUM_QTY, cumQty)
                .field(FixMessage.LEAVES_QTY, leavesQty)
                .field(FixMessage.AVG_PX, avgPrice);
    }
}

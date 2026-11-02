package com.quantfinlib.fix;

import com.quantfinlib.core.Side;

/**
 * Typed view of a FIX ExecutionReport (35=8) — the venue's answer to an
 * order: acknowledgement, fill, or rejection. {@code possDup} is true when
 * the report arrived as a resend replay (PossDupFlag(43)=Y) or with
 * PossResend(97)=Y: it may already have been processed, so deduplicate on
 * {@code execId} before applying a fill.
 */
public record ExecutionReport(String orderId, String execId, char execType, char ordStatus,
                              String clOrdId, String symbol, Side side,
                              double lastQty, double lastPrice,
                              double cumQty, double leavesQty, double avgPrice,
                              boolean possDup) {

    /** Report without the PossDup marker (what a venue sends first time). */
    public ExecutionReport(String orderId, String execId, char execType, char ordStatus,
                           String clOrdId, String symbol, Side side,
                           double lastQty, double lastPrice,
                           double cumQty, double leavesQty, double avgPrice) {
        this(orderId, execId, execType, ordStatus, clOrdId, symbol, side, lastQty, lastPrice,
                cumQty, leavesQty, avgPrice, false);
    }

    public static final char EXEC_TYPE_NEW = '0';
    public static final char EXEC_TYPE_CANCELED = '4';
    public static final char EXEC_TYPE_REPLACED = '5';
    public static final char EXEC_TYPE_TRADE = 'F';
    public static final char EXEC_TYPE_REJECTED = '8';
    /**
     * ExecType H, trade cancel (bust): the execution named by ExecRefID (19)
     * did not happen. FIX 4.4 and later; in FIX 4.2 the same value is sent in
     * ExecTransType (20) = 1.
     *
     * @since 2.3.0
     */
    public static final char EXEC_TYPE_TRADE_CANCEL = 'H';
    /**
     * ExecType G, trade correct: the execution named by ExecRefID (19) is
     * replaced by this report's LastQty and LastPx.
     *
     * @since 2.3.0
     */
    public static final char EXEC_TYPE_TRADE_CORRECT = 'G';
    public static final char ORD_STATUS_NEW = '0';
    public static final char ORD_STATUS_PARTIALLY_FILLED = '1';
    public static final char ORD_STATUS_FILLED = '2';
    public static final char ORD_STATUS_CANCELED = '4';
    public static final char ORD_STATUS_REPLACED = '5';
    public static final char ORD_STATUS_REJECTED = '8';

    public boolean isFill() {
        return execType == EXEC_TYPE_TRADE;
    }

    /** True for ExecType H (trade cancel, a bust). @since 2.3.0 */
    public boolean isTradeCancel() {
        return isTradeCancel(execType);
    }

    /** True for ExecType G (trade correct). @since 2.3.0 */
    public boolean isTradeCorrect() {
        return isTradeCorrect(execType);
    }

    /** True when {@code execType} (tag 150 value) is H, trade cancel. @since 2.3.0 */
    public static boolean isTradeCancel(int execType) {
        return execType == EXEC_TYPE_TRADE_CANCEL;
    }

    /** True when {@code execType} (tag 150 value) is G, trade correct. @since 2.3.0 */
    public static boolean isTradeCorrect(int execType) {
        return execType == EXEC_TYPE_TRADE_CORRECT;
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
                m.getDouble(FixMessage.AVG_PX, 0),
                m.isFlagSet(FixMessage.POSS_DUP_FLAG) || m.isFlagSet(FixMessage.POSS_RESEND));
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

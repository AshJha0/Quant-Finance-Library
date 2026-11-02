package com.quantfinlib.fix;

import com.quantfinlib.core.Side;

/**
 * Builders for the messages {@link FixSession} sends: the initiator Logon,
 * session Reject and the three order-entry requests. Pure functions of their
 * arguments; the session adds header fields, sequence numbers and the store
 * write. Package-private; split out of the session in 3.1 with no change in
 * the bytes on the wire.
 */
final class FixSessionMessages {

    private FixSessionMessages() {
    }

    /** Initiator Logon (35=A) for this configuration and version. */
    static FixMessage.Builder logon(FixSession.Config config, FixVersion version,
                                    boolean useNextExpected, long expectedIncomingSeq) {
        FixMessage.Builder logon = FixMessage.builder(FixMessage.LOGON)
                .field(FixMessage.ENCRYPT_METHOD, "0")
                .field(FixMessage.HEART_BT_INT, config.heartbeatSeconds());
        if (version.isFixt()) {
            logon.field(FixVersion.DEFAULT_APPL_VER_ID, version.applVerId());
        }
        if (config.resetOnLogon()) {
            logon.field(FixMessage.RESET_SEQ_NUM_FLAG, "Y");
        }
        if (config.username() != null) {
            logon.field(FixMessage.USERNAME, config.username());
        }
        if (config.password() != null) {
            logon.field(FixMessage.PASSWORD, config.password());
        }
        if (useNextExpected) {
            logon.field(FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM, expectedIncomingSeq);
        }
        return logon;
    }

    /** NewOrderSingle (35=D); {@code limitPrice = NaN} is a market order. */
    static FixMessage.Builder newOrderSingle(String clOrdId, String symbol, Side side, long quantity,
                                             double limitPrice, char timeInForce,
                                             String transactTime) {
        FixMessage.Builder b = FixMessage.builder(FixMessage.NEW_ORDER_SINGLE)
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, symbol)
                .field(FixMessage.SIDE, side == Side.BUY ? '1' : '2')
                .field(FixMessage.TRANSACT_TIME, transactTime)
                .field(FixMessage.ORDER_QTY, quantity);
        if (Double.isNaN(limitPrice)) {
            b.field(FixMessage.ORD_TYPE, NewOrderSingle.ORD_TYPE_MARKET);
        } else {
            b.field(FixMessage.ORD_TYPE, NewOrderSingle.ORD_TYPE_LIMIT)
                    .field(FixMessage.PRICE, limitPrice);
        }
        b.field(FixMessage.TIME_IN_FORCE, timeInForce);
        return b;
    }

    /** OrderCancelRequest (35=F). */
    static FixMessage.Builder cancelRequest(String clOrdId, String origClOrdId, String symbol,
                                            Side side, long quantity, String transactTime) {
        return FixMessage.builder(FixMessage.ORDER_CANCEL_REQUEST)
                .field(FixMessage.ORIG_CL_ORD_ID, origClOrdId)
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, symbol)
                .field(FixMessage.SIDE, side == Side.BUY ? '1' : '2')
                .field(FixMessage.TRANSACT_TIME, transactTime)
                .field(FixMessage.ORDER_QTY, quantity);
    }

    /** OrderCancelReplaceRequest (35=G); {@code limitPrice = NaN} is a market order. */
    static FixMessage.Builder cancelReplace(String clOrdId, String origClOrdId, String symbol,
                                            Side side, long quantity, double limitPrice,
                                            char timeInForce, String transactTime) {
        FixMessage.Builder b = FixMessage.builder(FixMessage.ORDER_CANCEL_REPLACE_REQUEST)
                .field(FixMessage.ORIG_CL_ORD_ID, origClOrdId)
                .field(FixMessage.CL_ORD_ID, clOrdId)
                .field(FixMessage.SYMBOL, symbol)
                .field(FixMessage.SIDE, side == Side.BUY ? '1' : '2')
                .field(FixMessage.TRANSACT_TIME, transactTime)
                .field(FixMessage.ORDER_QTY, quantity);
        if (Double.isNaN(limitPrice)) {
            b.field(FixMessage.ORD_TYPE, NewOrderSingle.ORD_TYPE_MARKET);
        } else {
            b.field(FixMessage.ORD_TYPE, NewOrderSingle.ORD_TYPE_LIMIT)
                    .field(FixMessage.PRICE, limitPrice);
        }
        b.field(FixMessage.TIME_IN_FORCE, timeInForce);
        return b;
    }

    /**
     * Session Reject (35=3); {@code refMsgType} null and {@code refTagId} 0
     * omit those tags, a negative reason omits 373, and SOH in the text is
     * replaced by a space.
     */
    static FixMessage.Builder reject(long refSeqNum, String refMsgType, int reason, int refTagId,
                                     String text) {
        FixMessage.Builder b = FixMessage.builder(FixMessage.REJECT)
                .field(FixMessage.REF_SEQ_NUM, refSeqNum);
        if (refTagId > 0) {
            b.field(FixMessage.REF_TAG_ID, refTagId);
        }
        if (refMsgType != null && !refMsgType.isEmpty()) {
            b.field(FixMessage.REF_MSG_TYPE, refMsgType);
        }
        if (reason >= 0) {
            b.field(FixMessage.SESSION_REJECT_REASON, reason);
        }
        if (text != null && !text.isEmpty()) {
            b.field(FixMessage.TEXT, text.replace(FixMessage.SOH, ' '));
        }
        return b;
    }
}

package com.quantfinlib.trading;

/**
 * Order lifecycle transitions shared by the fast lane
 * ({@link HftOrderGateway}) and the simulation lane
 * ({@link PaperTradingGateway}). States are small ints so the fast lane can
 * keep them in a primitive array; {@link #toStatus} maps them to
 * {@link OrderStatus}.
 *
 * <pre>
 *   PENDING_NEW --ack--------------&gt; NEW
 *   PENDING_NEW --reject-----------&gt; REJECTED
 *   PENDING_NEW, NEW, PARTIAL --fill (leaves &gt; 0)--&gt; PARTIALLY_FILLED
 *   PENDING_NEW, NEW, PARTIAL --fill (leaves = 0)--&gt; FILLED
 *   PENDING_NEW, NEW, PARTIAL --cancelled-------&gt; CANCELED
 *   NEW --ack--&gt; NEW                 (duplicate ack, ignored)
 *   FILLED, CANCELED, REJECTED: terminal, every event is illegal
 * </pre>
 *
 * <p>A fill before the ack is legal: several venues send the execution
 * before, or instead of, a separate acknowledgement for marketable orders.
 * A cancel from {@code PENDING_NEW} covers IOC remainders and unsolicited
 * venue cancels. Conventions follow the FIX 4.4 specification, Volume 4,
 * "Order State Change Matrices".</p>
 */
public final class OrderStateMachine {

    public static final int EMPTY = 0;
    public static final int PENDING_NEW = 1;
    public static final int NEW = 2;
    public static final int PARTIALLY_FILLED = 3;
    public static final int FILLED = 4;
    public static final int CANCELED = 5;
    public static final int REJECTED = 6;

    public static final int EVENT_ACK = 0;
    public static final int EVENT_REJECT = 1;
    public static final int EVENT_PARTIAL_FILL = 2;
    public static final int EVENT_FULL_FILL = 3;
    public static final int EVENT_CANCELED = 4;

    /** Returned by {@link #next} for an illegal transition. */
    public static final int ILLEGAL = -1;

    private static final OrderStatus[] STATUS = {
        null, OrderStatus.PENDING_NEW, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED,
        OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED
    };

    private OrderStateMachine() {
    }

    /** The state after {@code event}, or {@link #ILLEGAL}. */
    public static int next(int state, int event) {
        return switch (state) {
            case PENDING_NEW -> switch (event) {
                case EVENT_ACK -> NEW;
                case EVENT_REJECT -> REJECTED;
                case EVENT_PARTIAL_FILL -> PARTIALLY_FILLED;
                case EVENT_FULL_FILL -> FILLED;
                case EVENT_CANCELED -> CANCELED;
                default -> ILLEGAL;
            };
            case NEW -> switch (event) {
                case EVENT_ACK -> NEW;
                case EVENT_PARTIAL_FILL -> PARTIALLY_FILLED;
                case EVENT_FULL_FILL -> FILLED;
                case EVENT_CANCELED -> CANCELED;
                default -> ILLEGAL;
            };
            case PARTIALLY_FILLED -> switch (event) {
                case EVENT_ACK -> PARTIALLY_FILLED;
                case EVENT_PARTIAL_FILL -> PARTIALLY_FILLED;
                case EVENT_FULL_FILL -> FILLED;
                case EVENT_CANCELED -> CANCELED;
                default -> ILLEGAL;
            };
            default -> ILLEGAL;
        };
    }

    /** True for orders that can still trade (pending, acknowledged or partially filled). */
    public static boolean isLive(int state) {
        return state == PENDING_NEW || state == NEW || state == PARTIALLY_FILLED;
    }

    public static boolean isTerminal(int state) {
        return state == FILLED || state == CANCELED || state == REJECTED;
    }

    /** The {@link OrderStatus} for a state; null for {@link #EMPTY} or unknown values. */
    public static OrderStatus toStatus(int state) {
        return state >= 0 && state < STATUS.length ? STATUS[state] : null;
    }

    public static int fromStatus(OrderStatus status) {
        return status.ordinal() + 1;
    }
}

package com.quantfinlib.trading;

/**
 * Lifecycle state of an order, following FIX 4.4 OrdStatus(39):
 * {@code PENDING_NEW} (A) sent but not yet acknowledged, {@code NEW} (0)
 * acknowledged and working, {@code PARTIALLY_FILLED} (1), {@code FILLED}
 * (2), {@code CANCELED} (4) and {@code REJECTED} (8). The transition rules
 * live in {@link OrderStateMachine}.
 */
public enum OrderStatus {
    PENDING_NEW, NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED;

    /** True for FILLED, CANCELED and REJECTED: no further fills can arrive. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED;
    }
}

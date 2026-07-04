package com.quantfinlib.trading;

/** Lifecycle state of a gateway order. */
public enum OrderStatus {
    NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED
}

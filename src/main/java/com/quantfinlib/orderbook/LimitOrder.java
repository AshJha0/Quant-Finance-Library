package com.quantfinlib.orderbook;

/** A resting limit order. Quantity is the remaining (unfilled) amount. */
public final class LimitOrder {

    private final long id;
    private final Side side;
    private final double price;
    private final long timestampNanos;
    long quantity;   // mutated by the book on fills (package-private)

    LimitOrder(long id, Side side, double price, long quantity, long timestampNanos) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.timestampNanos = timestampNanos;
    }

    public long id()             { return id; }
    public Side side()           { return side; }
    public double price()        { return price; }
    public long quantity()       { return quantity; }
    public long timestampNanos() { return timestampNanos; }
}

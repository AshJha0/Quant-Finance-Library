package com.quantfinlib.trading;

import com.quantfinlib.marketdata.StridedSequences;
import com.quantfinlib.orderbook.Side;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Fast-lane order gateway: signal to venue with no allocation, no locks and
 * no map lookups on the submit path. {@link #submit} runs the
 * {@link HftRiskGate} checks, the optional {@link OrderThrottle}, claims a
 * slot in the {@link OrderStateTable}, reserves exposure in the gate and
 * publishes into an {@link OrderRingBuffer}. A venue thread drains the ring
 * to the registered {@link OrderListener}s (the venue adapters).
 *
 * <h2>Order lifecycle</h2>
 * <p>Every accepted order is tracked through {@link OrderStateMachine}:
 * PENDING_NEW until the venue adapter reports {@link #onAck}, then NEW,
 * PARTIALLY_FILLED and finally FILLED, CANCELED or REJECTED. The adapter
 * reports on its own thread (the venue-report thread) through
 * {@link #onAck}, {@link #onReject}, {@link #onFill}, {@link #onCanceled},
 * {@link #onReplaced} and {@link #onCancelReject}; these update positions
 * in the gate and release the reserved exposure. The trading thread can
 * {@link #cancel}, {@link #replace} and {@link #cancelAll}, and should call
 * {@link #checkAckTimeouts} periodically: an order still PENDING_NEW after
 * the ack timeout is flagged, counted and sent a cancel, and its exposure
 * stays reserved until the venue answers.</p>
 *
 * <h2>Return codes of {@link #submit}</h2>
 * <p>Positive: the accepted order id. Negative: {@code -reason} with an
 * {@link HftRiskGate} reason code, including
 * {@link HftRiskGate#REJECT_GATEWAY_DOWN} when the venue thread has died,
 * {@link HftRiskGate#REJECT_THROTTLED} and
 * {@link HftRiskGate#REJECT_TOO_MANY_OPEN}. Zero: the ring is full
 * (backpressure; nothing was reserved).</p>
 *
 * <h2>Kill switch and failures</h2>
 * <p>When the gate's kill switch is engaged ({@link HftRiskGate#kill}),
 * the venue thread sends {@link OrderListener#onCancelAll} for every symbol
 * and suppresses any new order still queued in the ring (it is marked
 * REJECTED and its exposure released), so no new order reaches the venue
 * after the cancel-all. A listener exception is contained: it is counted,
 * the first one is kept in {@link #failure()}, the gate is killed (which
 * triggers the cancel-all to the remaining listeners) and the venue thread
 * keeps running. If the venue thread dies anyway, an uncaught-exception
 * handler marks the gateway down, kills the gate, and every later submit
 * returns {@code -REJECT_GATEWAY_DOWN}.</p>
 *
 * <h2>Threading</h2>
 * <p>One trading thread calls submit, cancel, replace, cancelAll and
 * checkAckTimeouts (single producer). One venue thread drains the ring.
 * One venue-report thread (often the venue thread itself, for in-process
 * venues) calls the report methods. Counters are published in opaque mode
 * so monitoring threads see current values.</p>
 */
public final class HftOrderGateway implements AutoCloseable {

    private static final OrderListener[] NO_LISTENERS = new OrderListener[0];

    // Trading-thread counters.
    private static final int SUBMITTED = 0;
    private static final int RING_FULL = 1;
    private static final int CANCELS = 2;
    private static final int REPLACES = 3;
    private static final int ACK_TIMEOUTS = 4;
    private static final int NEXT_ID = 5;

    private final OrderRingBuffer ring;
    private final HftRiskGate riskGate;
    private final OrderStateTable orders;
    private final boolean busySpin;
    private final OrderThrottle throttle;
    private final long ackTimeoutNanos;
    private volatile OrderListener[] listeners = NO_LISTENERS;
    private final Dispatcher dispatcher = new Dispatcher();

    private final StridedSequences tradingCounters = new StridedSequences(6);
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong listenerErrors = new AtomicLong();
    private final AtomicLong suppressedWhileKilled = new AtomicLong();
    private final AtomicLong cancelAllsSent = new AtomicLong();
    private final AtomicLong acks = new AtomicLong();
    private final AtomicLong fills = new AtomicLong();
    private final AtomicLong rejects = new AtomicLong();
    private final AtomicLong cancels = new AtomicLong();
    private final AtomicLong unknownReports = new AtomicLong();
    private final AtomicLong illegalTransitions = new AtomicLong();

    private volatile boolean running;
    private volatile boolean venueDead;
    private volatile Throwable failure;
    private long seenKillEpoch;         // venue thread
    private Thread venueThread;

    /**
     * @param ringCapacity    order ring size (rounded up to a power of two)
     * @param riskGate        pre-trade gate; also receives fills and exposure
     * @param busySpin        venue thread spins (latency) or parks (CPU)
     * @param maxOpenOrders   order-state table size (rounded up to a power of two)
     * @param throttle        message-rate throttle for new orders and replaces, or null
     * @param ackTimeoutNanos PENDING_NEW age after which {@link #checkAckTimeouts}
     *                        flags and cancels an order ({@code Long.MAX_VALUE} = never)
     */
    public HftOrderGateway(int ringCapacity, HftRiskGate riskGate, boolean busySpin,
                           int maxOpenOrders, OrderThrottle throttle, long ackTimeoutNanos) {
        if (riskGate == null) {
            throw new IllegalArgumentException("riskGate is required");
        }
        if (ackTimeoutNanos <= 0) {
            throw new IllegalArgumentException("ackTimeoutNanos must be > 0");
        }
        this.ring = new OrderRingBuffer(ringCapacity);
        this.riskGate = riskGate;
        this.busySpin = busySpin;
        this.orders = new OrderStateTable(maxOpenOrders);
        this.throttle = throttle;
        this.ackTimeoutNanos = ackTimeoutNanos;
        this.seenKillEpoch = riskGate.killEpoch();
        tradingCounters.setPlain(NEXT_ID, 1);
    }

    /** No throttle, 64K-order state table, ack timeout of one second. */
    public HftOrderGateway(int ringCapacity, HftRiskGate riskGate, boolean busySpin) {
        this(ringCapacity, riskGate, busySpin, 1 << 16, null, 1_000_000_000L);
    }

    /** Parked venue thread, 8K ring. */
    public HftOrderGateway(HftRiskGate riskGate) {
        this(1 << 13, riskGate, false);
    }

    // ------------------------------------------------------------------
    // Setup (cold path)
    // ------------------------------------------------------------------

    public synchronized void addOrderListener(OrderListener listener) {
        OrderListener[] next = Arrays.copyOf(listeners, listeners.length + 1);
        next[next.length - 1] = listener;
        listeners = next;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        venueThread = new Thread(this::venueLoop, "hft-order-venue");
        venueThread.setDaemon(true);
        venueThread.setUncaughtExceptionHandler((t, e) -> {
            venueDead = true;
            recordFailure(e);
        });
        venueThread.start();
    }

    /** Stops the venue thread after draining queued messages. */
    public synchronized void stop() {
        running = false;
        if (venueThread != null) {
            try {
                venueThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            venueThread = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------
    // Trading thread
    // ------------------------------------------------------------------

    /** Submits a day limit order; see {@link #submit(int, Side, long, double, int, long)}. */
    public long submit(int symbolId, Side side, long quantity, double price, long timestampNanos) {
        return submit(symbolId, side, quantity, price, OrderListener.FLAG_NONE, timestampNanos);
    }

    /**
     * Risk-checks, reserves and publishes one new order. Zero allocation.
     *
     * @param flags {@link OrderListener#FLAG_IOC} or {@link OrderListener#FLAG_NONE}
     * @return positive order id if accepted; {@code -reason} if rejected; 0 if the ring is full
     */
    public long submit(int symbolId, Side side, long quantity, double price, int flags,
                       long timestampNanos) {
        if (venueDead) {
            riskGate.countRejection(HftRiskGate.REJECT_GATEWAY_DOWN);
            return -HftRiskGate.REJECT_GATEWAY_DOWN;
        }
        int code = riskGate.check(symbolId, side, quantity, price, timestampNanos);
        if (code != HftRiskGate.OK) {
            return -code;
        }
        long id = tradingCounters.getPlain(NEXT_ID);
        int slot = orders.open(id, symbolId, side, quantity, price, timestampNanos);
        if (slot < 0) {
            riskGate.countRejection(HftRiskGate.REJECT_TOO_MANY_OPEN);
            return -HftRiskGate.REJECT_TOO_MANY_OPEN;
        }
        if (throttle != null && !throttle.tryAcquire(timestampNanos)) {
            orders.abandon(slot);
            riskGate.countRejection(HftRiskGate.REJECT_THROTTLED);
            return -HftRiskGate.REJECT_THROTTLED;
        }
        // Reserve before publishing: an in-process venue may fill the order
        // before publish() even returns, and the fill releases exposure.
        riskGate.reserve(symbolId, side, quantity);
        if (!ring.publish(OrderRingBuffer.NEW, flags, id, symbolId, side, quantity, price,
                timestampNanos)) {
            riskGate.release(symbolId, side, quantity);
            orders.abandon(slot);
            tradingCounters.incrementOpaque(RING_FULL);
            return 0;
        }
        riskGate.recordAccepted(symbolId, side, quantity, price, timestampNanos);
        tradingCounters.setOpaque(NEXT_ID, id + 1);
        tradingCounters.incrementOpaque(SUBMITTED);
        return id;
    }

    /**
     * Requests cancellation of a working order. Returns false if the id is
     * unknown, already terminal, already pending cancel, or the ring is
     * full. Cancels are never throttled: pulling risk must not wait for
     * message-rate headroom.
     */
    public boolean cancel(long orderId, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0 || !OrderStateMachine.isLive(orders.state(slot))
                || (orders.flags(slot) & OrderStateTable.PENDING_CANCEL) != 0) {
            return false;
        }
        if (!ring.publish(OrderRingBuffer.CANCEL, 0, orderId, orders.symbols[slot],
                orders.side(slot), 0, Double.NaN, timestampNanos)) {
            tradingCounters.incrementOpaque(RING_FULL);
            return false;
        }
        orders.setFlag(slot, OrderStateTable.PENDING_CANCEL);
        tradingCounters.incrementOpaque(CANCELS);
        return true;
    }

    /**
     * Requests a cancel/replace to a new total quantity and price. The new
     * terms pass the risk gate ({@link HftRiskGate#checkReplace}); any
     * extra working quantity is reserved now. Returns 0 on success and
     * {@code -reason} on a risk or throttle rejection. Returns
     * {@link #REPLACE_NOT_LIVE} if the order is unknown, terminal or has a
     * cancel or replace already pending, and {@link #REPLACE_RING_FULL} on
     * backpressure.
     */
    public int replace(long orderId, long newQuantity, double newPrice, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0 || !OrderStateMachine.isLive(orders.state(slot))
                || (orders.flags(slot)
                        & (OrderStateTable.PENDING_CANCEL | OrderStateTable.PENDING_REPLACE)) != 0) {
            return REPLACE_NOT_LIVE;
        }
        int symbolId = orders.symbols[slot];
        Side side = orders.side(slot);
        long wantedLeaves = newQuantity - orders.filled(slot);
        if (wantedLeaves <= 0) {
            return REPLACE_NOT_LIVE;
        }
        long extra = wantedLeaves - orders.reserved(slot);
        int code = riskGate.checkReplace(symbolId, side, newQuantity, extra, newPrice);
        if (code != HftRiskGate.OK) {
            return -code;
        }
        if (throttle != null && !throttle.tryAcquire(timestampNanos)) {
            riskGate.countRejection(HftRiskGate.REJECT_THROTTLED);
            return -HftRiskGate.REJECT_THROTTLED;
        }
        if (extra > 0) {
            riskGate.reserve(symbolId, side, extra);
            orders.addReserved(slot, extra);
        }
        orders.setFlag(slot, OrderStateTable.PENDING_REPLACE);
        if (!ring.publish(OrderRingBuffer.REPLACE, 0, orderId, symbolId, side, newQuantity,
                newPrice, timestampNanos)) {
            orders.clearFlag(slot, OrderStateTable.PENDING_REPLACE);
            if (extra > 0) {
                riskGate.release(symbolId, side, orders.releaseUpTo(slot, extra));
            }
            tradingCounters.incrementOpaque(RING_FULL);
            return REPLACE_RING_FULL;
        }
        if (extra > 0) {
            riskGate.addDailyNotional(extra * newPrice);
        }
        tradingCounters.incrementOpaque(REPLACES);
        return 0;
    }

    /** {@link #replace} result: order unknown, terminal or already pending cancel/replace. */
    public static final int REPLACE_NOT_LIVE = -1_000;
    /** {@link #replace} result: the ring was full. */
    public static final int REPLACE_RING_FULL = -1_001;

    /**
     * Sends a mass cancel for one symbol, or for every symbol with
     * {@link OrderListener#ALL_SYMBOLS}, and marks matching live orders
     * pending cancel. Scans the state table (cold path). Returns false if
     * the ring is full.
     */
    public boolean cancelAll(int symbolId, long timestampNanos) {
        if (!ring.publish(OrderRingBuffer.CANCEL_ALL, 0, 0, symbolId, Side.BUY, 0, Double.NaN,
                timestampNanos)) {
            tradingCounters.incrementOpaque(RING_FULL);
            return false;
        }
        markPendingCancel(symbolId);
        return true;
    }

    private void markPendingCancel(int symbolId) {
        for (int s = 0; s < orders.capacity(); s++) {
            if (OrderStateMachine.isLive(orders.state(s))
                    && (symbolId == OrderListener.ALL_SYMBOLS || orders.symbols[s] == symbolId)) {
                orders.setFlag(s, OrderStateTable.PENDING_CANCEL);
            }
        }
    }

    /**
     * Flags every order that has been PENDING_NEW for longer than the ack
     * timeout, counts it once and sends it a cancel. Scans the state table:
     * call it from the trading thread at a coarse interval (for example
     * every millisecond), not per tick. Returns the number newly flagged.
     */
    public int checkAckTimeouts(long nowNanos) {
        int flagged = 0;
        for (int s = 0; s < orders.capacity(); s++) {
            if (orders.state(s) == OrderStateMachine.PENDING_NEW
                    && (orders.flags(s) & OrderStateTable.ACK_TIMED_OUT) == 0
                    && nowNanos - orders.sentNanos[s] > ackTimeoutNanos) {
                orders.setFlag(s, OrderStateTable.ACK_TIMED_OUT);
                tradingCounters.incrementOpaque(ACK_TIMEOUTS);
                flagged++;
                cancel(orders.ids[s], nowNanos);
            }
        }
        return flagged;
    }

    // ------------------------------------------------------------------
    // Venue-report thread: execution reports from the venue adapter
    // ------------------------------------------------------------------

    /** Venue acknowledged the order. Returns false for an unknown id or illegal transition. */
    public boolean onAck(long orderId, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0) {
            unknownReports.incrementAndGet();
            return false;
        }
        if (!transition(slot, OrderStateMachine.EVENT_ACK)) {
            return false;
        }
        acks.incrementAndGet();
        return true;
    }

    /** Venue rejected the new order: terminal, exposure released. */
    public boolean onReject(long orderId, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0) {
            unknownReports.incrementAndGet();
            return false;
        }
        if (!transition(slot, OrderStateMachine.EVENT_REJECT)) {
            return false;
        }
        releaseAll(slot);
        rejects.incrementAndGet();
        return true;
    }

    /**
     * Venue reported an execution of {@code quantity} at {@code price}. The
     * position is applied in the gate first and the reservation released
     * after, so a concurrent check never understates exposure.
     */
    public boolean onFill(long orderId, long quantity, double price, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0 || quantity <= 0) {
            unknownReports.incrementAndGet();
            return false;
        }
        int state = orders.state(slot);
        long cum = orders.filled(slot) + quantity;
        long total = orders.quantity(slot);
        int event = cum >= total ? OrderStateMachine.EVENT_FULL_FILL
                : OrderStateMachine.EVENT_PARTIAL_FILL;
        int next = OrderStateMachine.next(state, event);
        if (next == OrderStateMachine.ILLEGAL) {
            illegalTransitions.incrementAndGet();
            return false;
        }
        int symbolId = orders.symbols[slot];
        Side side = orders.side(slot);
        riskGate.onFill(symbolId, side, quantity);
        orders.addFilled(slot, quantity);
        riskGate.release(symbolId, side, orders.releaseUpTo(slot, quantity));
        if (next == OrderStateMachine.FILLED) {
            releaseAll(slot);
        }
        orders.setState(slot, next);
        fills.incrementAndGet();
        return true;
    }

    /** Venue confirmed a cancel (requested, IOC remainder, or unsolicited). */
    public boolean onCanceled(long orderId, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0) {
            unknownReports.incrementAndGet();
            return false;
        }
        if (!transition(slot, OrderStateMachine.EVENT_CANCELED)) {
            return false;
        }
        releaseAll(slot);
        orders.clearFlag(slot, (byte) (OrderStateTable.PENDING_CANCEL
                | OrderStateTable.PENDING_REPLACE));
        cancels.incrementAndGet();
        return true;
    }

    /**
     * Venue confirmed a cancel/replace to {@code newQuantity} (new total)
     * at {@code newPrice}. Any reservation above the new leaves quantity is
     * released.
     */
    public boolean onReplaced(long orderId, long newQuantity, double newPrice,
                              long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0 || !OrderStateMachine.isLive(orders.state(slot))) {
            unknownReports.incrementAndGet();
            return false;
        }
        orders.setQuantity(slot, newQuantity);
        orders.setPrice(slot, newPrice);
        long leaves = Math.max(0, newQuantity - orders.filled(slot));
        long excess = orders.reserved(slot) - leaves;
        if (excess > 0) {
            riskGate.release(orders.symbols[slot], orders.side(slot),
                    orders.releaseUpTo(slot, excess));
        }
        orders.clearFlag(slot, OrderStateTable.PENDING_REPLACE);
        if (leaves == 0) {
            releaseAll(slot);
            orders.setState(slot, OrderStateMachine.FILLED);
        }
        return true;
    }

    /** Venue refused a cancel or replace: clears the pending flags, drops any extra reservation. */
    public boolean onCancelReject(long orderId, long timestampNanos) {
        int slot = orders.slotOf(orderId);
        if (slot < 0) {
            unknownReports.incrementAndGet();
            return false;
        }
        long leaves = Math.max(0, orders.quantity(slot) - orders.filled(slot));
        long excess = orders.reserved(slot) - leaves;
        if (excess > 0) {
            riskGate.release(orders.symbols[slot], orders.side(slot),
                    orders.releaseUpTo(slot, excess));
        }
        orders.clearFlag(slot, (byte) (OrderStateTable.PENDING_CANCEL
                | OrderStateTable.PENDING_REPLACE));
        return true;
    }

    private boolean transition(int slot, int event) {
        int next = OrderStateMachine.next(orders.state(slot), event);
        if (next == OrderStateMachine.ILLEGAL) {
            illegalTransitions.incrementAndGet();
            return false;
        }
        orders.setState(slot, next);
        return true;
    }

    private void releaseAll(int slot) {
        long r = orders.releaseUpTo(slot, Long.MAX_VALUE);
        if (r > 0) {
            riskGate.release(orders.symbols[slot], orders.side(slot), r);
        }
    }

    // ------------------------------------------------------------------
    // Order queries (any thread; the trading thread is the usual caller)
    // ------------------------------------------------------------------

    /** Lifecycle status of an order, or null if the id is unknown or its slot was reused. */
    public OrderStatus status(long orderId) {
        int slot = orders.slotOf(orderId);
        return slot < 0 ? null : OrderStateMachine.toStatus(orders.state(slot));
    }

    /** Raw {@link OrderStateMachine} state; {@link OrderStateMachine#EMPTY} if unknown. */
    public int state(long orderId) {
        int slot = orders.slotOf(orderId);
        return slot < 0 ? OrderStateMachine.EMPTY : orders.state(slot);
    }

    /** True while the order can still trade (pending, acknowledged or partially filled). */
    public boolean isLive(long orderId) {
        return OrderStateMachine.isLive(state(orderId));
    }

    /** True if a cancel or replace has been requested and not yet confirmed or refused. */
    public boolean isPendingChange(long orderId) {
        int slot = orders.slotOf(orderId);
        return slot >= 0 && (orders.flags(slot)
                & (OrderStateTable.PENDING_CANCEL | OrderStateTable.PENDING_REPLACE)) != 0;
    }

    /** Quantity still working (0 if unknown or terminal). */
    public long leavesQuantity(long orderId) {
        int slot = orders.slotOf(orderId);
        if (slot < 0 || !OrderStateMachine.isLive(orders.state(slot))) {
            return 0;
        }
        return Math.max(0, orders.quantity(slot) - orders.filled(slot));
    }

    public long filledQuantity(long orderId) {
        int slot = orders.slotOf(orderId);
        return slot < 0 ? 0 : orders.filled(slot);
    }

    /** Current limit price of a tracked order; NaN if unknown. */
    public double orderPrice(long orderId) {
        int slot = orders.slotOf(orderId);
        return slot < 0 ? Double.NaN : orders.price(slot);
    }

    // ------------------------------------------------------------------
    // Venue thread
    // ------------------------------------------------------------------

    private final class Dispatcher implements OrderListener {
        @Override
        public void onOrder(long orderId, int symbolId, Side side, long quantity, double price,
                            long timestampNanos) {
            onNewOrder(orderId, symbolId, side, quantity, price, FLAG_NONE, timestampNanos);
        }

        @Override
        public void onNewOrder(long orderId, int symbolId, Side side, long quantity, double price,
                               int flags, long timestampNanos) {
            if (riskGate.isKilled()) {
                // Queued before the kill: never let it reach the venue.
                int slot = orders.slotOf(orderId);
                if (slot >= 0 && transition(slot, OrderStateMachine.EVENT_REJECT)) {
                    releaseAll(slot);
                }
                suppressedWhileKilled.incrementAndGet();
                return;
            }
            for (OrderListener l : listeners) {
                try {
                    l.onNewOrder(orderId, symbolId, side, quantity, price, flags, timestampNanos);
                } catch (RuntimeException e) {
                    contain(e);
                }
            }
        }

        @Override
        public void onCancel(long orderId, int symbolId, long timestampNanos) {
            for (OrderListener l : listeners) {
                try {
                    l.onCancel(orderId, symbolId, timestampNanos);
                } catch (RuntimeException e) {
                    contain(e);
                }
            }
        }

        @Override
        public void onReplace(long orderId, int symbolId, Side side, long newQuantity,
                              double newPrice, long timestampNanos) {
            if (riskGate.isKilled()) {
                suppressedWhileKilled.incrementAndGet();
                return;
            }
            for (OrderListener l : listeners) {
                try {
                    l.onReplace(orderId, symbolId, side, newQuantity, newPrice, timestampNanos);
                } catch (RuntimeException e) {
                    contain(e);
                }
            }
        }

        @Override
        public void onCancelAll(int symbolId, long timestampNanos) {
            for (OrderListener l : listeners) {
                try {
                    l.onCancelAll(symbolId, timestampNanos);
                } catch (RuntimeException e) {
                    contain(e);
                }
            }
        }
    }

    private void contain(RuntimeException e) {
        listenerErrors.incrementAndGet();
        recordFailure(e);
    }

    private void recordFailure(Throwable e) {
        if (failure == null) {
            failure = e;
        }
        riskGate.kill(true);
    }

    private void checkKill() {
        long epoch = riskGate.killEpoch();
        if (epoch != seenKillEpoch) {
            seenKillEpoch = epoch;
            cancelAllsSent.incrementAndGet();
            dispatcher.onCancelAll(OrderListener.ALL_SYMBOLS, System.nanoTime());
        }
    }

    private void venueLoop() {
        while (true) {
            checkKill();
            int n = ring.drainTo(dispatcher, 256);
            if (n > 0) {
                delivered.setRelease(delivered.getPlain() + n);
            } else {
                if (!running && ring.isEmpty()) {
                    checkKill();
                    return;
                }
                if (busySpin) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(200);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Observability (any thread)
    // ------------------------------------------------------------------

    public HftRiskGate riskGate() {
        return riskGate;
    }

    /** False once a venue listener has thrown or the venue thread has died. */
    public boolean isHealthy() {
        return failure == null && !venueDead;
    }

    /** True if the venue thread terminated abnormally; submits are then refused. */
    public boolean isVenueThreadDead() {
        return venueDead;
    }

    /** The first listener or venue-thread failure, or null. */
    public Throwable failure() {
        return failure;
    }

    public long submittedCount() {
        return tradingCounters.getOpaque(SUBMITTED);
    }

    /** Ring messages (orders, cancels, replaces, mass cancels) consumed by the venue thread. */
    public long deliveredCount() {
        return delivered.get();
    }

    public long ringFullCount() {
        return tradingCounters.getOpaque(RING_FULL);
    }

    public long cancelRequestCount() {
        return tradingCounters.getOpaque(CANCELS);
    }

    public long replaceRequestCount() {
        return tradingCounters.getOpaque(REPLACES);
    }

    public long ackTimeoutCount() {
        return tradingCounters.getOpaque(ACK_TIMEOUTS);
    }

    public long listenerErrorCount() {
        return listenerErrors.get();
    }

    /** New orders and replaces dropped on the venue thread because the gate was killed. */
    public long suppressedWhileKilledCount() {
        return suppressedWhileKilled.get();
    }

    /** Cancel-alls sent to the venue listeners in response to kill-switch engagements. */
    public long killCancelAllCount() {
        return cancelAllsSent.get();
    }

    public long ackCount() {
        return acks.get();
    }

    public long fillCount() {
        return fills.get();
    }

    public long venueRejectCount() {
        return rejects.get();
    }

    public long canceledCount() {
        return cancels.get();
    }

    /** Execution reports for ids the table does not hold (unknown, reused or never sent). */
    public long unknownReportCount() {
        return unknownReports.get();
    }

    /** Reports that would break the state machine (for example a fill after a cancel). */
    public long illegalTransitionCount() {
        return illegalTransitions.get();
    }
}

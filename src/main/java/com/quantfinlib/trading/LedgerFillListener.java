package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
 * Opt-in wiring of a {@link PositionLedger} to gateway fills, so the ledger
 * (and through it {@link DropCopyReconciler}) sees every execution without
 * hand-written glue.
 *
 * <p>The plain idea: the gateway publishes each accepted execution, this
 * adapter books it under one {@code (account, strategy)} allocation. Partial
 * fills arrive as separate executions and are booked separately, which is
 * what a drop copy reports. Usage:</p>
 * <pre>{@code
 * PositionLedger ledger = new PositionLedger(...);
 * gateway.addFillListener(new LedgerFillListener(ledger, account, strategy));
 * }</pre>
 *
 * <p>For {@link PaperTradingGateway}, whose listener speaks symbol names and
 * carries no execution id, {@link #forPaper} maps names to ids and numbers
 * executions from 1.</p>
 *
 * <p>Busts and corrections are not modelled: neither gateway reports them
 * and the ledger has no reversal entry. A bust must be booked by the caller
 * as an offsetting fill, which a drop copy will show as a difference.</p>
 *
 * <p>The ledger is single-writer: register this adapter on a gateway whose
 * reports come from one thread, or synchronise externally. A fill the
 * ledger refuses is counted in {@link #rejectedCount()}.</p>
 *
 * @since 2.2.0
 */
public final class LedgerFillListener implements FillListener {

    private final PositionLedger ledger;
    private final int accountId;
    private final int strategyId;
    private final AtomicLong rejected = new AtomicLong();

    public LedgerFillListener(PositionLedger ledger, int accountId, int strategyId) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.accountId = accountId;
        this.strategyId = strategyId;
    }

    @Override
    public void onFill(long execId, long orderId, int symbolId, Side side, long quantity,
                       double price, long timestampNanos) {
        if (!ledger.onFill(execId, accountId, strategyId, symbolId, side, quantity, price)) {
            rejected.incrementAndGet();
        }
    }

    /** Fills the ledger refused (bad ids, full lot queue). */
    public long rejectedCount() {
        return rejected.get();
    }

    /**
     * An execution listener for {@link OrderGateway} implementations that
     * books into {@code ledger}; {@code symbolIds} maps a symbol name to the
     * ledger's dense id. Execution ids are 1, 2, 3, ... in arrival order.
     */
    public static OrderGateway.ExecutionListener forPaper(PositionLedger ledger, int accountId,
                                                          int strategyId,
                                                          ToIntFunction<String> symbolIds) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(symbolIds, "symbolIds");
        AtomicLong seq = new AtomicLong();
        return (orderId, symbol, side, price, quantity, ts) -> ledger.onFill(seq.incrementAndGet(),
                accountId, strategyId, symbolIds.applyAsInt(symbol), side, quantity, price);
    }
}

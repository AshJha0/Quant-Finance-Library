package com.quantfinlib.trading;

import com.quantfinlib.core.Side;

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
 * <p>Busts and corrections reported through
 * {@link HftOrderGateway#onTradeBust} and {@link HftOrderGateway#onTradeCorrect}
 * reach {@link PositionLedger#onBust} and {@link PositionLedger#onCorrect};
 * one the ledger refuses (unknown ExecRefID, for example) is counted in
 * {@link #rejectedCount()}. {@link PaperTradingGateway} can simulate busts
 * and corrections ({@link PaperTradingGateway#bustFill}); book them with
 * {@link #forPaperTrades}, which uses the gateway's execution ids.</p>
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

    /** @since 2.3.0 */
    @Override
    public void onBust(long execId, long execRefId, long orderId, int symbolId, Side side,
                       long quantity, long timestampNanos) {
        if (!ledger.onBust(execId, execRefId)) {
            rejected.incrementAndGet();
        }
    }

    /** @since 2.3.0 */
    @Override
    public void onCorrect(long execId, long execRefId, long orderId, int symbolId, Side side,
                          long quantity, double price, long timestampNanos) {
        if (!ledger.onCorrect(execId, execRefId, quantity, price)) {
            rejected.incrementAndGet();
        }
    }

    /** Fills, busts and corrections the ledger refused (bad ids, full lot queue, unknown ExecRefID). */
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

    /**
     * A {@link PaperTradingGateway.TradeListener} that books fills, busts and
     * corrections into {@code ledger} under the gateway's own execution ids,
     * so a simulated drop copy can be reconciled with
     * {@link DropCopyReconciler}. Refusals are not counted here; check the
     * ledger's return values through {@link PositionLedger} if needed.
     *
     * @since 2.4.0
     */
    public static PaperTradingGateway.TradeListener forPaperTrades(PositionLedger ledger,
                                                                   int accountId, int strategyId,
                                                                   ToIntFunction<String> symbolIds) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(symbolIds, "symbolIds");
        return new PaperTradingGateway.TradeListener() {
            @Override
            public void onFill(long execId, long orderId, String symbol, Side side, long quantity,
                               double price, long timestampNanos) {
                ledger.onFill(execId, accountId, strategyId, symbolIds.applyAsInt(symbol), side,
                        quantity, price);
            }

            @Override
            public void onBust(long execId, long execRefId, long orderId, String symbol,
                               Side side, long quantity, long timestampNanos) {
                ledger.onBust(execId, execRefId);
            }

            @Override
            public void onCorrect(long execId, long execRefId, long orderId, String symbol,
                                  Side side, long quantity, double price, long timestampNanos) {
                ledger.onCorrect(execId, execRefId, quantity, price);
            }
        };
    }
}

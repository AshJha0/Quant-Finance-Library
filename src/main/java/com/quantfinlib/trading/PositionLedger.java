package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.util.Objects;

/**
 * Positions and P&amp;L built from fills only: one ledger per process is the
 * single source of truth that risk, hedging and reporting read.
 *
 * <p>The plain idea: every fill moves a position, and P&amp;L is what the
 * fills cost against what the position is worth now. Realised P&amp;L is
 * locked in when a fill reduces a position; unrealised P&amp;L is the open
 * position marked to a price. How much is "locked in" depends on which
 * earlier purchase a sale is matched against, so the ledger keeps both
 * conventions side by side:</p>
 * <ul>
 *   <li>{@link CostMethod#AVERAGE_COST}: one weighted-average entry price per
 *       position. Adding {@code q} at {@code p} to {@code n} held at
 *       {@code a} gives {@code a' = (n a + q p) / (n + q)}; closing {@code q}
 *       realises {@code q (p - a) sgn(n) m}.</li>
 *   <li>{@link CostMethod#FIFO}: a queue of open lots; a closing fill
 *       consumes the oldest lots first and realises
 *       {@code sum_k q_k (p - p_k) sgn(n) m}.</li>
 * </ul>
 * <p>Here {@code m} is the symbol's contract multiplier and P&amp;L is in the
 * price currency. A fill larger than the open position closes it and opens
 * the remainder on the other side at the fill price. Unrealised P&amp;L at
 * mark {@code M} is {@code n (M - a) m} (average cost) or
 * {@code sum_k sgn(n) q_k (M - p_k) m} (FIFO). Both conventions give the same
 * total, realised plus unrealised, equal to the cash-flow identity
 * {@code m (n M - sum_i s_i q_i p_i)} with {@code s_i = +1} for a buy; the
 * tests pin this. References: CFA Institute, <i>Financial Reporting and
 * Analysis</i> (inventory cost-flow assumptions, FIFO and weighted average);
 * IAS 2 paragraph 25.</p>
 *
 * <p>Worked example: buy 100 at 10, buy 100 at 12, sell 150 at 11. Average
 * cost is 11, realised 0, 50 left at 11. FIFO realises
 * {@code 100 (11 - 10) + 50 (11 - 12) = 50} and keeps 50 at 12. Marked at
 * 13, average-cost unrealised is {@code 50 (13 - 11) = 100} and FIFO
 * unrealised is {@code 50 (13 - 12) = 50}: total 100 either way.</p>
 *
 * <p>Books are keyed by dense ids, {@code (account, strategy, symbol)}, all
 * fixed at construction. {@link #onFill} does no allocation: it updates
 * preallocated arrays and appends to a bounded fill log kept for
 * {@link DropCopyReconciler}. A fill that cannot be applied (id out of range,
 * non-positive quantity, non-finite price, FIFO lot queue full) changes
 * nothing and returns false; the reason is counted. Fees are accumulated per
 * book and reported apart from realised P&amp;L.</p>
 *
 * <p>Not thread-safe: one writer thread applies fills, and readers either run
 * on that thread or take their own synchronisation.</p>
 */
public final class PositionLedger {

    /** Wildcard id for the aggregate queries: every account, strategy or symbol. */
    public static final int ANY = -1;

    /** Matching convention for realised P&amp;L. */
    public enum CostMethod {
        /** One weighted-average entry price per position. */
        AVERAGE_COST,
        /** Oldest open lot closed first. */
        FIFO
    }

    /** Mark price per dense symbol id; NaN when no price is available. */
    @FunctionalInterface
    public interface PriceSource {
        double mark(int symbolId);
    }

    private final int symbols;
    private final int strategies;
    private final int accounts;
    private final int maxLots;
    private final double[] multiplier;

    // per book
    private final long[] position;
    private final double[] avgCost;
    private final double[] realisedAvg;
    private final double[] realisedFifo;
    private final double[] fees;
    private final long[] boughtQty;
    private final long[] soldQty;
    private final int[] lotHead;
    private final int[] lotCount;
    private final long[] lotQty;     // book * maxLots + ring index
    private final double[] lotPx;

    // fill log
    private final long[] logExecId;
    private final int[] logAccount;
    private final int[] logStrategy;
    private final int[] logSymbol;
    private final byte[] logSide;
    private final long[] logQty;
    private final double[] logPx;
    private int logSize;
    private long logOverflow;

    private long fillCount;
    private long invalidFillCount;
    private long lotOverflowCount;

    /**
     * @param symbols         number of dense symbol ids
     * @param strategies      number of dense strategy ids
     * @param accounts        number of dense account ids
     * @param maxLotsPerBook  FIFO lots one book can hold open
     * @param fillLogCapacity fills retained for drop-copy reconciliation
     */
    public PositionLedger(int symbols, int strategies, int accounts, int maxLotsPerBook,
                          int fillLogCapacity) {
        if (symbols < 1 || strategies < 1 || accounts < 1) {
            throw new IllegalArgumentException("symbols, strategies and accounts must be >= 1");
        }
        if (maxLotsPerBook < 1 || fillLogCapacity < 0) {
            throw new IllegalArgumentException("maxLotsPerBook must be >= 1, fillLogCapacity >= 0");
        }
        long books = (long) symbols * strategies * accounts;
        if (books * maxLotsPerBook > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("books x maxLotsPerBook too large: " + books * maxLotsPerBook);
        }
        int n = (int) books;
        this.symbols = symbols;
        this.strategies = strategies;
        this.accounts = accounts;
        this.maxLots = maxLotsPerBook;
        this.multiplier = new double[symbols];
        java.util.Arrays.fill(multiplier, 1.0);
        this.position = new long[n];
        this.avgCost = new double[n];
        this.realisedAvg = new double[n];
        this.realisedFifo = new double[n];
        this.fees = new double[n];
        this.boughtQty = new long[n];
        this.soldQty = new long[n];
        this.lotHead = new int[n];
        this.lotCount = new int[n];
        this.lotQty = new long[n * maxLotsPerBook];
        this.lotPx = new double[n * maxLotsPerBook];
        this.logExecId = new long[fillLogCapacity];
        this.logAccount = new int[fillLogCapacity];
        this.logStrategy = new int[fillLogCapacity];
        this.logSymbol = new int[fillLogCapacity];
        this.logSide = new byte[fillLogCapacity];
        this.logQty = new long[fillLogCapacity];
        this.logPx = new double[fillLogCapacity];
    }

    /** Sets the contract multiplier of a symbol (default 1); call before its first fill. */
    public void setMultiplier(int symbolId, double m) {
        Objects.checkIndex(symbolId, symbols);
        if (!(m > 0) || !Double.isFinite(m)) {
            throw new IllegalArgumentException("multiplier must be finite and > 0: " + m);
        }
        multiplier[symbolId] = m;
    }

    public double multiplier(int symbolId) {
        return multiplier[Objects.checkIndex(symbolId, symbols)];
    }

    /** As {@link #onFill(long, int, int, int, Side, long, double, double)} with no fee. */
    public boolean onFill(long execId, int accountId, int strategyId, int symbolId, Side side,
                          long quantity, double price) {
        return onFill(execId, accountId, strategyId, symbolId, side, quantity, price, 0.0);
    }

    /**
     * Applies one execution. Allocation-free.
     *
     * @param execId  the venue's execution id, mapped to a long by the caller;
     *                used only by drop-copy reconciliation
     * @param price   any finite price (negative prices occur in spreads)
     * @param fee     fee in price currency, added to {@link #fees}; finite
     * @return false, with nothing changed, when the fill is invalid or the
     *         FIFO lot queue of the book is full
     */
    public boolean onFill(long execId, int accountId, int strategyId, int symbolId, Side side,
                          long quantity, double price, double fee) {
        if (accountId < 0 || accountId >= accounts || strategyId < 0 || strategyId >= strategies
                || symbolId < 0 || symbolId >= symbols || side == null || quantity <= 0
                || !Double.isFinite(price) || !Double.isFinite(fee)) {
            invalidFillCount++;
            return false;
        }
        int b = book(accountId, strategyId, symbolId);
        int dir = side.sign();
        long pos = position[b];
        boolean opening = pos == 0 || Long.signum(pos) == dir;
        if (opening && lotCount[b] == maxLots) {
            lotOverflowCount++;
            return false;
        }
        double m = multiplier[symbolId];
        if (opening) {
            long held = Math.abs(pos);
            avgCost[b] = (held * avgCost[b] + quantity * price) / (held + quantity);
            pushLot(b, quantity, price);
        } else {
            int sgn = Long.signum(pos);
            long held = Math.abs(pos);
            long closed = Math.min(quantity, held);
            realisedAvg[b] += closed * (price - avgCost[b]) * sgn * m;
            long remaining = quantity;
            double fifo = 0.0;
            while (remaining > 0 && lotCount[b] > 0) {
                int idx = b * maxLots + lotHead[b];
                long take = Math.min(lotQty[idx], remaining);
                fifo += take * (price - lotPx[idx]) * sgn;
                lotQty[idx] -= take;
                remaining -= take;
                if (lotQty[idx] == 0) {
                    lotHead[b] = lotHead[b] + 1 == maxLots ? 0 : lotHead[b] + 1;
                    lotCount[b]--;
                }
            }
            realisedFifo[b] += fifo * m;
            if (remaining > 0) {             // crossed through flat
                pushLot(b, remaining, price);
                avgCost[b] = price;
            } else if (closed == held) {
                avgCost[b] = 0.0;
            }
        }
        position[b] = pos + dir * quantity;
        fees[b] += fee;
        if (dir > 0) {
            boughtQty[b] += quantity;
        } else {
            soldQty[b] += quantity;
        }
        fillCount++;
        appendLog(execId, accountId, strategyId, symbolId, side, quantity, price);
        return true;
    }

    private void pushLot(int b, long qty, double px) {
        int tail = lotHead[b] + lotCount[b];
        if (tail >= maxLots) {
            tail -= maxLots;
        }
        int idx = b * maxLots + tail;
        lotQty[idx] = qty;
        lotPx[idx] = px;
        lotCount[b]++;
    }

    private void appendLog(long execId, int a, int t, int s, Side side, long q, double p) {
        if (logSize == logExecId.length) {
            logOverflow++;
            return;
        }
        int i = logSize++;
        logExecId[i] = execId;
        logAccount[i] = a;
        logStrategy[i] = t;
        logSymbol[i] = s;
        logSide[i] = (byte) side.ordinal();
        logQty[i] = q;
        logPx[i] = p;
    }

    private int book(int a, int t, int s) {
        return (a * strategies + t) * symbols + s;
    }

    private int checkedBook(int a, int t, int s) {
        return book(Objects.checkIndex(a, accounts), Objects.checkIndex(t, strategies),
                Objects.checkIndex(s, symbols));
    }

    // ------------------------------------------------------------------
    // Per-book queries
    // ------------------------------------------------------------------

    /** Signed position (long positive) of one book. */
    public long position(int accountId, int strategyId, int symbolId) {
        return position[checkedBook(accountId, strategyId, symbolId)];
    }

    /** Weighted-average entry price of one book's open position; 0 when flat. */
    public double averageCost(int accountId, int strategyId, int symbolId) {
        return avgCost[checkedBook(accountId, strategyId, symbolId)];
    }

    /** Open FIFO lots of one book. */
    public int openLots(int accountId, int strategyId, int symbolId) {
        return lotCount[checkedBook(accountId, strategyId, symbolId)];
    }

    /** Quantity of the {@code k}-th oldest open lot (unsigned). */
    public long lotQuantity(int accountId, int strategyId, int symbolId, int k) {
        int b = checkedBook(accountId, strategyId, symbolId);
        int i = lotHead[b] + Objects.checkIndex(k, lotCount[b]);
        return lotQty[b * maxLots + (i >= maxLots ? i - maxLots : i)];
    }

    /** Entry price of the {@code k}-th oldest open lot. */
    public double lotPrice(int accountId, int strategyId, int symbolId, int k) {
        int b = checkedBook(accountId, strategyId, symbolId);
        int i = lotHead[b] + Objects.checkIndex(k, lotCount[b]);
        return lotPx[b * maxLots + (i >= maxLots ? i - maxLots : i)];
    }

    // ------------------------------------------------------------------
    // Aggregates ({@link #ANY} matches every id)
    // ------------------------------------------------------------------

    /**
     * Net position summed over the matching books. {@code symbolId} must be a
     * real id: quantities of different symbols do not add.
     */
    public long netPosition(int accountId, int strategyId, int symbolId) {
        Objects.checkIndex(symbolId, symbols);
        long sum = 0;
        for (int a = lo(accountId, accounts); a < hi(accountId, accounts); a++) {
            for (int t = lo(strategyId, strategies); t < hi(strategyId, strategies); t++) {
                sum += position[book(a, t, symbolId)];
            }
        }
        return sum;
    }

    /** Realised P&amp;L over the matching books, in price currency, before fees. */
    public double realised(CostMethod method, int accountId, int strategyId, int symbolId) {
        double[] src = method == CostMethod.FIFO ? realisedFifo : realisedAvg;
        double sum = 0;
        for (int a = lo(accountId, accounts); a < hi(accountId, accounts); a++) {
            for (int t = lo(strategyId, strategies); t < hi(strategyId, strategies); t++) {
                for (int s = lo(symbolId, symbols); s < hi(symbolId, symbols); s++) {
                    sum += src[book(a, t, s)];
                }
            }
        }
        return sum;
    }

    /** Fees over the matching books. */
    public double fees(int accountId, int strategyId, int symbolId) {
        double sum = 0;
        for (int a = lo(accountId, accounts); a < hi(accountId, accounts); a++) {
            for (int t = lo(strategyId, strategies); t < hi(strategyId, strategies); t++) {
                for (int s = lo(symbolId, symbols); s < hi(symbolId, symbols); s++) {
                    sum += fees[book(a, t, s)];
                }
            }
        }
        return sum;
    }

    /**
     * Unrealised (mark-to-market) P&amp;L over the matching books. A flat book
     * contributes 0 whatever its mark; an open book whose mark is not finite
     * makes the result NaN, so a missing price is visible rather than read
     * as zero. {@link #unpricedBooks} counts such books.
     */
    public double unrealised(CostMethod method, int accountId, int strategyId, int symbolId,
                             PriceSource marks) {
        Objects.requireNonNull(marks, "marks");
        double sum = 0;
        for (int a = lo(accountId, accounts); a < hi(accountId, accounts); a++) {
            for (int t = lo(strategyId, strategies); t < hi(strategyId, strategies); t++) {
                for (int s = lo(symbolId, symbols); s < hi(symbolId, symbols); s++) {
                    int b = book(a, t, s);
                    if (position[b] == 0) {
                        continue;
                    }
                    double mark = marks.mark(s);
                    if (!Double.isFinite(mark)) {
                        return Double.NaN;
                    }
                    sum += unrealisedBook(method, b, s, mark);
                }
            }
        }
        return sum;
    }

    /** Open books among the matching ones whose mark is not finite. */
    public int unpricedBooks(int accountId, int strategyId, int symbolId, PriceSource marks) {
        int n = 0;
        for (int a = lo(accountId, accounts); a < hi(accountId, accounts); a++) {
            for (int t = lo(strategyId, strategies); t < hi(strategyId, strategies); t++) {
                for (int s = lo(symbolId, symbols); s < hi(symbolId, symbols); s++) {
                    if (position[book(a, t, s)] != 0 && !Double.isFinite(marks.mark(s))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Realised plus unrealised, before fees; NaN under the same rule as {@link #unrealised}. */
    public double totalPnl(CostMethod method, int accountId, int strategyId, int symbolId,
                           PriceSource marks) {
        return realised(method, accountId, strategyId, symbolId)
                + unrealised(method, accountId, strategyId, symbolId, marks);
    }

    private double unrealisedBook(CostMethod method, int b, int s, double mark) {
        double m = multiplier[s];
        long pos = position[b];
        if (method == CostMethod.AVERAGE_COST) {
            return pos * (mark - avgCost[b]) * m;
        }
        int sgn = Long.signum(pos);
        double sum = 0;
        for (int k = 0; k < lotCount[b]; k++) {
            int i = lotHead[b] + k;
            int idx = b * maxLots + (i >= maxLots ? i - maxLots : i);
            sum += sgn * lotQty[idx] * (mark - lotPx[idx]);
        }
        return sum * m;
    }

    private static int lo(int id, int n) {
        if (id == ANY) {
            return 0;
        }
        return Objects.checkIndex(id, n);
    }

    private static int hi(int id, int n) {
        return id == ANY ? n : id + 1;
    }

    // ------------------------------------------------------------------
    // Counters and fill log
    // ------------------------------------------------------------------

    public int symbols() {
        return symbols;
    }

    public int strategies() {
        return strategies;
    }

    public int accounts() {
        return accounts;
    }

    /** Fills applied. */
    public long fillCount() {
        return fillCount;
    }

    /** Fills refused for an id out of range, a non-positive quantity or a non-finite price or fee. */
    public long invalidFillCount() {
        return invalidFillCount;
    }

    /** Fills refused because the book's FIFO lot queue was full. */
    public long lotOverflowCount() {
        return lotOverflowCount;
    }

    /** Applied fills that did not fit in the fill log (reconciliation is then incomplete). */
    public long fillLogOverflow() {
        return logOverflow;
    }

    /** Fills held in the log, oldest first. */
    public int loggedFills() {
        return logSize;
    }

    /** The {@code i}-th logged fill as a value (allocates; for reconciliation and reports). */
    public DropCopyReconciler.Fill loggedFill(int i) {
        Objects.checkIndex(i, logSize);
        return new DropCopyReconciler.Fill(logExecId[i], logAccount[i], logStrategy[i],
                logSymbol[i], logSide[i] == 0 ? Side.BUY : Side.SELL, logQty[i], logPx[i]);
    }

    /** Total quantity bought in one book. */
    public long boughtQuantity(int accountId, int strategyId, int symbolId) {
        return boughtQty[checkedBook(accountId, strategyId, symbolId)];
    }

    /** Total quantity sold in one book. */
    public long soldQuantity(int accountId, int strategyId, int symbolId) {
        return soldQty[checkedBook(accountId, strategyId, symbolId)];
    }
}

package com.quantfinlib.execution;

import com.quantfinlib.microstructure.EwmaCovariance;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.util.MathUtils;

/**
 * True multi-symbol portfolio-level scheduling: a basket (rebalance,
 * transition, program trade) executed as one coordinated schedule rather
 * than N independent parents. Each symbol keeps its own
 * {@link BenchmarkExecutor} child — its benchmark, curve and per-symbol
 * shaping stay intact — and the portfolio layer applies the two overlays
 * that only exist at basket level:
 *
 * <ol>
 *   <li><b>Leg balance</b> — the defining constraint of a two-sided
 *       transition: the buy leg and the sell leg must stay in step, or the
 *       basket carries unintended net market exposure mid-flight. When the
 *       projected net filled notional (buys − sells, plus this interval's
 *       dues) would breach {@code maxNetNotional}, the interval throttles
 *       the leg that is ahead. It never accelerates the lagging leg —
 *       pushing a child past its own schedule would break the benchmark it
 *       is measured against;</li>
 *   <li><b>Capacity allocation</b> — {@code maxIntervalNotional} caps the
 *       basket's total demand per interval (participation budget, cash
 *       constraint). When it binds, capacity goes to the symbols carrying
 *       the most residual risk. By default that is the diagonal
 *       approximation of multi-asset Almgren-Chriss — weight ∝
 *       (1 + volatility regime) × due notional. Plug in a streaming
 *       {@link EwmaCovariance} via {@link #useRiskModel} and it becomes
 *       the real thing: weight ∝ (1 + marginal contribution to BASKET
 *       variance) × due notional, so two correlated legs are recognized
 *       as one concentrated risk and a natural hedge earns no urgency.</li>
 * </ol>
 *
 * <p>Both overlays only ever <em>reduce</em> a child's own due quantity,
 * so per-symbol benchmark integrity holds by construction, and anything
 * deferred reappears through each child's behind-schedule catch-up next
 * interval. A binding cap can therefore leave a residual at the horizon —
 * that is the constraint's honest meaning, not a bug.</p>
 *
 * <p>Usage: {@link #add} each child once (buys and sells mixed freely),
 * then each interval call {@link #decide} with per-symbol
 * {@link BenchmarkExecutor.MarketState} snapshots and route the returned
 * dues; report fills via {@link #onFill} (which also maintains the net
 * ledger). Notional arithmetic needs a price: the layer remembers the
 * last finite mid per symbol (and fill prices); a symbol that has never
 * shown a price passes through unscaled — the caps cannot see what they
 * cannot price. Cross-asset (notional is just quantity × price), zero
 * allocation per decide, single writer.</p>
 */
public final class PortfolioExecutor {

    /**
     * @param maxNetNotional      leg-balance band: |filled buys − filled
     *                            sells| (projected through this interval)
     *                            stays within this; +∞ disables
     * @param maxIntervalNotional total basket demand per interval; +∞
     *                            disables
     */
    public record Config(double maxNetNotional, double maxIntervalNotional) {
        public Config {
            if (!(maxNetNotional > 0) || !(maxIntervalNotional > 0)) {
                throw new IllegalArgumentException(
                        "need maxNetNotional > 0 and maxIntervalNotional > 0 (+Inf disables)");
            }
        }

        /** No portfolio constraints: children pass through untouched. */
        public static Config unconstrained() {
            return new Config(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
    }

    private final Config config;
    private final BenchmarkExecutor[] children;
    private final double[] lastMid;        // last finite price seen per symbol
    private final double[] dueNotional;    // scratch, valid within decide()
    private final double[] signedRemaining;// scratch: signed remaining notional
    private final double[] riskFactor;     // scratch: capacity weight multiplier
    private int count;

    private EwmaCovariance riskModel;      // optional: upgrades the risk weights

    private double buyFilledNotional;
    private double sellFilledNotional;

    public PortfolioExecutor(int maxSymbols, Config config) {
        if (maxSymbols < 1) {
            throw new IllegalArgumentException("need maxSymbols >= 1");
        }
        this.config = config;
        this.children = new BenchmarkExecutor[maxSymbols];
        this.lastMid = new double[maxSymbols];
        this.dueNotional = new double[maxSymbols];
        this.signedRemaining = new double[maxSymbols];
        this.riskFactor = new double[maxSymbols];
    }

    /**
     * Upgrades the capacity allocation from the diagonal approximation to
     * true basket risk: with a covariance model, a binding
     * {@code maxIntervalNotional} flows to the symbols whose REMAINING
     * position contributes most to portfolio variance
     * ({@link EwmaCovariance#marginalContribution}) — two correlated buys
     * carry more joint timing risk than their individual vols admit, and a
     * natural hedge carries less. Handle {@code i} maps to covariance
     * symbol {@code i}; feed the model one return vector per interval on
     * your own clock. Without a model (or before it has learned), the
     * weight falls back to the per-symbol volatility regime.
     */
    public void useRiskModel(EwmaCovariance model) {
        if (model.symbols() != children.length) {
            throw new IllegalArgumentException("risk model covers " + model.symbols()
                    + " symbols; this portfolio was sized for " + children.length);
        }
        this.riskModel = model;
    }

    /** Registers a child parent order; returns its handle for decide/onFill. */
    public int add(BenchmarkExecutor child) {
        if (count == children.length) {
            throw new IllegalStateException("portfolio is full (" + children.length + " symbols)");
        }
        children[count] = child;
        return count++;
    }

    // ------------------------------------------------------------------
    // The interval decision
    // ------------------------------------------------------------------

    /**
     * One portfolio interval: asks every child for its own due quantity,
     * then applies the leg-balance band and the capacity allocation.
     * {@code dueOut[handle]} receives the shares to send per symbol.
     *
     * @param scheduleFraction elapsed fraction of the execution horizon
     * @param states           per-handle market snapshots (index = handle)
     * @param dueOut           per-handle output, length >= {@link #size()}
     */
    public void decide(double scheduleFraction, BenchmarkExecutor.MarketState[] states,
                       long[] dueOut) {
        if (states.length < count || dueOut.length < count) {
            throw new IllegalArgumentException(
                    "states/dueOut must cover all " + count + " symbols");
        }
        // 1. Each child's own decision, and its notional at the best-known price.
        for (int i = 0; i < count; i++) {
            dueOut[i] = children[i].dueQuantity(scheduleFraction, states[i]);
            double mid = states[i].mid();
            if (mid > 0 && mid < Double.POSITIVE_INFINITY) {
                lastMid[i] = mid;              // NaN fails the > 0 test
            }
        }

        // 2. Leg balance: throttle the leg that would push |net| past the band.
        applyLegBand(dueOut);

        // 3. Capacity: when total demand exceeds the interval budget,
        //    allocate it risk-weighted and cut each symbol to its share.
        if (config.maxIntervalNotional() < Double.POSITIVE_INFINITY) {
            fillRiskFactors(states);
            double total = 0;
            double sumWeight = 0;
            for (int i = 0; i < count; i++) {
                double notional = dueOut[i] * lastMid[i];
                dueNotional[i] = notional;
                total += notional;
                sumWeight += riskFactor[i] * notional;
            }
            if (total > config.maxIntervalNotional() && sumWeight > 0) {
                for (int i = 0; i < count; i++) {
                    if (dueNotional[i] <= 0) {
                        continue;
                    }
                    double weight = riskFactor[i] * dueNotional[i];
                    double allocation = config.maxIntervalNotional() * weight / sumWeight;
                    if (dueNotional[i] > allocation) {
                        dueOut[i] = (long) Math.floor(dueOut[i] * allocation / dueNotional[i]);
                    }
                }
                // 4. The risk weights cut the two legs asymmetrically, so
                //    the capacity pass can push |net| back over the band
                //    the first pass enforced. Re-apply it — the band only
                //    reduces dues, so it can never re-violate the budget,
                //    and the sequence terminates here by construction.
                applyLegBand(dueOut);
            }
        }
    }

    /**
     * The capacity weight multiplier per symbol. With a covariance model
     * that has a live risk picture: {@code 1 + clamp(MRC, 0, 1)} where MRC
     * is the remaining position's marginal contribution to basket variance
     * — a natural hedge (negative MRC) earns no extra capacity, because
     * executing it INCREASES the risk left behind. Otherwise the diagonal
     * fallback {@code 1 + volatilityRegime}. Same bounded [1, 2] shape
     * either way, so the two modes are interchangeable mid-flight.
     */
    private void fillRiskFactors(BenchmarkExecutor.MarketState[] states) {
        boolean modeled = false;
        if (riskModel != null) {
            // Tail entries stay zero for good: nothing else writes
            // signedRemaining and count never shrinks, so unadded handles
            // carry no risk without re-zeroing.
            for (int i = 0; i < count; i++) {
                double sign = children[i].side() == Side.BUY ? 1 : -1;
                signedRemaining[i] = sign * children[i].remaining() * lastMid[i];
            }
            if (riskModel.marginalContribution(signedRemaining, riskFactor) > 0) {
                for (int i = 0; i < count; i++) {
                    riskFactor[i] = 1 + MathUtils.clamp(riskFactor[i], 0, 1);
                }
                modeled = true;
            }
        }
        if (!modeled) {
            for (int i = 0; i < count; i++) {
                double vol = states[i].volatility();
                riskFactor[i] = 1 + (vol > 0 ? vol : 0);   // NaN -> 0
            }
        }
    }

    /**
     * The leg-balance band: if this interval's dues would carry the net
     * filled notional past {@code maxNetNotional}, scale down the leg that
     * is pushing it over. Reads the CURRENT dues, so it is safe to apply
     * again after another overlay has changed them.
     */
    private void applyLegBand(long[] dueOut) {
        if (config.maxNetNotional() == Double.POSITIVE_INFINITY) {
            return;
        }
        double buyDue = 0;
        double sellDue = 0;
        for (int i = 0; i < count; i++) {
            double notional = dueOut[i] * lastMid[i];
            if (children[i].side() == Side.BUY) {
                buyDue += notional;
            } else {
                sellDue += notional;
            }
        }
        double net = buyFilledNotional - sellFilledNotional;
        double projected = net + buyDue - sellDue;
        if (projected > config.maxNetNotional() && buyDue > 0) {
            double allowed = Math.max(0, config.maxNetNotional() - net + sellDue);
            scaleSide(Side.BUY, allowed / buyDue, dueOut);
        } else if (projected < -config.maxNetNotional() && sellDue > 0) {
            double allowed = Math.max(0, config.maxNetNotional() + net + buyDue);
            scaleSide(Side.SELL, allowed / sellDue, dueOut);
        }
    }

    /**
     * Scales one side's dues by {@code scale} in [0,1] (floor rounding).
     * Symbols without a known price are skipped — they contributed nothing
     * to the notional being reduced, so cutting them would shrink flow
     * without moving the ledger.
     */
    private void scaleSide(Side side, double scale, long[] dueOut) {
        double s = MathUtils.clamp(scale, 0, 1);
        for (int i = 0; i < count; i++) {
            if (children[i].side() == side && dueOut[i] > 0 && lastMid[i] > 0) {
                dueOut[i] = (long) Math.floor(dueOut[i] * s);
            }
        }
    }

    // ------------------------------------------------------------------
    // Fills and progress
    // ------------------------------------------------------------------

    /**
     * A fill for one child: forwards to its executor and maintains the net
     * ledger. A non-positive or non-finite price still advances the child's
     * schedule but cannot enter the notional ledger.
     *
     * <p><b>Report every fill through THIS method, never through
     * {@code child(h).onFill(...)}</b> — the child call advances that
     * symbol's schedule but silently bypasses the buy/sell notional ledger
     * the leg-balance band reads, leaving the basket's net exposure
     * uncontrolled while every per-child number looks healthy.</p>
     */
    public void onFill(int handle, long qty, double price) {
        children[handle].onFill(qty);
        if (qty > 0 && price > 0 && price < Double.POSITIVE_INFINITY) {
            lastMid[handle] = price;
            double notional = qty * price;
            if (children[handle].side() == Side.BUY) {
                buyFilledNotional += notional;
            } else {
                sellFilledNotional += notional;
            }
        }
    }

    /** Signed net filled notional: buys − sells. The leg-balance ledger. */
    public double netNotional() {
        return buyFilledNotional - sellFilledNotional;
    }

    public boolean done() {
        for (int i = 0; i < count; i++) {
            if (!children[i].done()) {
                return false;
            }
        }
        return true;
    }

    public int size() {
        return count;
    }

    /**
     * The child executor behind a handle — for progress/drift reads and for
     * feeding {@code onMarketVolume} to VWAP/POV children. Do NOT report
     * fills via {@code child(h).onFill(...)}: fills must go through
     * {@link #onFill(int, long, double)} so the leg-balance ledger sees them.
     */
    public BenchmarkExecutor child(int handle) {
        return children[handle];
    }
}

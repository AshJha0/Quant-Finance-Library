package com.quantfinlib.crb;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.risk.VarEngine;

import java.util.Map;
import java.util.TreeMap;

/**
 * The central risk book — one netted view of the firm's market risk
 * across desks and products. Every instrument is decomposed into a
 * COMMON risk-factor space at booking time, and that is the entire
 * point: an FX-option delta nets against a spot position, an
 * equity-option delta nets against cash shares, and two desks' opposite
 * flows cancel before anyone pays a spread to the street.
 *
 * <p><b>Factor naming and units</b> (query via {@link #exposure(String)}):
 * <ul>
 *   <li>{@code EQ:<sym>} — equity delta in book-currency notional
 *       (cash shares contribute qty·price; options contribute
 *       Δ·spot·contracts·multiplier);</li>
 *   <li>{@code EQGAMMA:<sym>} / {@code EQVEGA:<sym>} — dollar gamma per
 *       1% move (Γ·S²/100 per contract-adjusted unit) and vega per vol
 *       POINT;</li>
 *   <li>{@code CCY:<ccy>} — currency exposure in NATIVE units of that
 *       currency (an EURUSD buy of 10M books CCY:EUR +10M euros and
 *       CCY:USD −10M·rate dollars) — this is what lets spot, swaps,
 *       NDFs and option deltas net at the CURRENCY level;</li>
 *   <li>{@code FXPOINTS:<pair>} — forward-points risk of swaps: P&amp;L
 *       in quote units per 1.0 move in the far−near differential;</li>
 *   <li>{@code FXGAMMA:<pair>} / {@code FXVEGA:<pair>} — dollar gamma
 *       per 1% and vega per vol point, in quote-currency units.</li>
 * </ul>
 *
 * <p>Sign convention: positive quantity/notional = the BOOK is long.
 * Book what the book absorbs (a client sell hits the book as a buy).
 * NDFs carry currency delta until fixing; the pending non-deliverable
 * notional is tracked per pair in {@link #pendingFixing(String)} and
 * released via {@link #settleFixing} once fixings occur. This is a
 * RISK ledger, not a cash ledger: premium and settlement cash legs are
 * deliberately untracked (the same delta-only stance for cash equities
 * — no settlement cash — and options — no premium), and lifecycle
 * events (expiry, exercise, settlement) re-book as offsetting flows.
 * Deterministic, single-threaded, research/warm lane — pair it with
 * {@link InternalizationEngine} for flow decisions and
 * {@link CrbAutoHedger} for the hedging loop.</p>
 */
public final class CentralRiskBook {

    private final FactorRegistry registry = new FactorRegistry();
    private double[] net = new double[16];
    private double[] gross = new double[16];
    private final Map<String, double[]> deskNet = new TreeMap<>();
    // TreeMap: deterministic serialization order in writeState.
    private final Map<String, Double> pendingFixings = new TreeMap<>();
    private long flowsBooked;

    // ------------------------------------------------------------------
    // Booking — equities
    // ------------------------------------------------------------------

    /** Cash equity: {@code qty} shares (signed) at {@code price}. */
    public void bookCashEquity(String desk, String symbol, double qty, double price) {
        requireDesk(desk);
        requireFinite(qty, "qty");
        requirePositive(price, "price");
        add(desk, "EQ:" + symbol, qty * price);
        flowsBooked++;
    }

    /**
     * Listed equity option: {@code contracts} signed, {@code multiplier}
     * shares per contract (100 for US listed). Greeks come from
     * {@link BlackScholes} with the house q-convention ({@code carry} =
     * dividend yield).
     */
    public void bookEquityOption(String desk, String symbol, OptionType type,
                                 double contracts, double multiplier,
                                 double spot, double strike, double rate, double carry,
                                 double vol, double timeYears) {
        requireDesk(desk);
        requireFinite(contracts, "contracts");
        requireFinite(rate, "rate");
        requireFinite(carry, "carry");
        requirePositive(multiplier, "multiplier");
        requirePositive(spot, "spot");
        requirePositive(strike, "strike");
        requirePositive(vol, "vol");
        requirePositive(timeYears, "timeYears");
        double units = contracts * multiplier;
        double delta = BlackScholes.delta(type, spot, strike, rate, carry, vol, timeYears);
        double gamma = BlackScholes.gamma(spot, strike, rate, carry, vol, timeYears);
        double vega = BlackScholes.vega(spot, strike, rate, carry, vol, timeYears);
        // Compute-validate-COMMIT: every leg must be finite before any
        // add(), or an overflow on the second leg would leave a
        // half-booked flow in the netted arrays.
        double deltaLeg = units * delta * spot;
        double gammaLeg = units * gamma * spot * spot / 100;                 // $ per 1%
        double vegaLeg = units * vega / 100;                                 // per vol point
        requireFinite(deltaLeg, "delta leg");
        requireFinite(gammaLeg, "gamma leg");
        requireFinite(vegaLeg, "vega leg");
        add(desk, "EQ:" + symbol, deltaLeg);
        add(desk, "EQGAMMA:" + symbol, gammaLeg);
        add(desk, "EQVEGA:" + symbol, vegaLeg);
        flowsBooked++;
    }

    // ------------------------------------------------------------------
    // Booking — FX
    // ------------------------------------------------------------------

    /**
     * FX spot on {@code pair} ("EURUSD"): buy {@code baseNotional} of the
     * base currency (signed) at {@code rate}. Decomposes into the two
     * CURRENCY legs so it nets against every other product's FX delta.
     */
    public void bookFxSpot(String desk, String pair, double baseNotional, double rate) {
        requireDesk(desk);
        requirePair(pair);
        requireFinite(baseNotional, "baseNotional");
        requirePositive(rate, "rate");
        double quoteLeg = -baseNotional * rate;
        requireFinite(quoteLeg, "quote leg");
        add(desk, "CCY:" + pair.substring(0, 3), baseNotional);
        add(desk, "CCY:" + pair.substring(3, 6), quoteLeg);
        flowsBooked++;
    }

    /**
     * FX swap (buy-sell base for positive notional): near leg at
     * {@code nearRate}, far leg back at {@code farRate}. The base-currency
     * legs cancel EXACTLY (that is what a swap is); what remains is the
     * quote-currency cash-flow imbalance and the forward-POINTS risk.
     * The FXPOINTS factor is the SENSITIVITY: a buy-sell locked in at
     * today's (far − near) must close out at tomorrow's, so its P&amp;L
     * per +1.0 move in the market's far−near differential is
     * {@code −baseNotional} — points widening HURTS the buy-sell.
     */
    public void bookFxSwap(String desk, String pair, double baseNotional,
                           double nearRate, double farRate) {
        requireDesk(desk);
        requirePair(pair);
        requireFinite(baseNotional, "baseNotional");
        requirePositive(nearRate, "nearRate");
        requirePositive(farRate, "farRate");
        double quoteImbalance = baseNotional * (farRate - nearRate);
        requireFinite(quoteImbalance, "quote-leg imbalance");
        add(desk, "CCY:" + pair.substring(3, 6), quoteImbalance);
        add(desk, "FXPOINTS:" + pair, -baseNotional);
        flowsBooked++;
    }

    /**
     * NDF: buy {@code baseNotional} of base forward at {@code fwdRate}.
     * Economically a forward until the fixing — full currency delta on
     * both legs — with the non-deliverable notional tracked per pair
     * (settlement is cash in the deliverable currency; what fixes at the
     * fixing is the RATE, and until then the book is exposed).
     */
    public void bookNdf(String desk, String pair, double baseNotional, double fwdRate) {
        requireDesk(desk);
        requirePair(pair);
        requireFinite(baseNotional, "baseNotional");
        requirePositive(fwdRate, "fwdRate");
        double quoteLeg = -baseNotional * fwdRate;
        requireFinite(quoteLeg, "quote leg");
        add(desk, "CCY:" + pair.substring(0, 3), baseNotional);
        add(desk, "CCY:" + pair.substring(3, 6), quoteLeg);
        pendingFixings.merge(pair, Math.abs(baseNotional), Double::sum);
        flowsBooked++;
    }

    /**
     * FX option via Garman-Kohlhagen ({@link BlackScholes} with carry =
     * foreign rate): {@code baseNotional} signed (long calls on base).
     * Delta decomposes into the two currency legs; gamma/vega stay
     * pair-keyed in quote-currency units.
     */
    public void bookFxOption(String desk, String pair, OptionType type, double baseNotional,
                             double spotRate, double strike, double domesticRate,
                             double foreignRate, double vol, double timeYears) {
        requireDesk(desk);
        requirePair(pair);
        requireFinite(baseNotional, "baseNotional");
        requireFinite(domesticRate, "domesticRate");
        requireFinite(foreignRate, "foreignRate");
        requirePositive(spotRate, "spotRate");
        requirePositive(strike, "strike");
        requirePositive(vol, "vol");
        requirePositive(timeYears, "timeYears");
        double delta = BlackScholes.delta(type, spotRate, strike, domesticRate,
                foreignRate, vol, timeYears);
        double gamma = BlackScholes.gamma(spotRate, strike, domesticRate,
                foreignRate, vol, timeYears);
        double vega = BlackScholes.vega(spotRate, strike, domesticRate,
                foreignRate, vol, timeYears);
        // Compute-validate-COMMIT (see bookEquityOption).
        double deltaBase = baseNotional * delta;
        double quoteLeg = -deltaBase * spotRate;
        double gammaLeg = baseNotional * gamma * spotRate * spotRate / 100;
        double vegaLeg = baseNotional * vega / 100;
        requireFinite(deltaBase, "delta leg");
        requireFinite(quoteLeg, "quote leg");
        requireFinite(gammaLeg, "gamma leg");
        requireFinite(vegaLeg, "vega leg");
        add(desk, "CCY:" + pair.substring(0, 3), deltaBase);
        add(desk, "CCY:" + pair.substring(3, 6), quoteLeg);
        add(desk, "FXGAMMA:" + pair, gammaLeg);
        add(desk, "FXVEGA:" + pair, vegaLeg);
        flowsBooked++;
    }

    // ------------------------------------------------------------------
    // The netted view
    // ------------------------------------------------------------------

    /** Net exposure on a factor (0 for a factor never booked). */
    public double exposure(String factor) {
        int id = registry.idIfPresent(factor);
        // A factor can be REGISTERED but never booked (a hedge universe
        // registers hedge-only factors): its exposure is simply 0.
        return id < 0 || id >= net.length ? 0 : net[id];
    }

    /** Gross (sum of |flow|) on a factor — what the desks did severally. */
    public double grossExposure(String factor) {
        int id = registry.idIfPresent(factor);
        return id < 0 || id >= gross.length ? 0 : gross[id];
    }

    /** One desk's net contribution to a factor. */
    public double deskExposure(String desk, String factor) {
        double[] d = deskNet.get(desk);
        if (d == null) {
            return 0;
        }
        int id = registry.idIfPresent(factor);
        return id < 0 || id >= d.length ? 0 : d[id];
    }

    /** Net exposures over all factors, indexed by registry id. */
    public double[] netExposures() {
        double[] out = new double[registry.size()];
        System.arraycopy(net, 0, out, 0, Math.min(net.length, out.length));
        return out;
    }

    /**
     * How much risk the netting destroyed before anyone hedged:
     * {@code 1 − Σ|net| / Σgross} — 0 when every flow is one-way, → 1
     * when the desks' flows offset each other entirely.
     */
    public double nettingEfficiency() {
        // min(): hedge-only factors registered past the booked arrays
        // carry zero exposure by definition.
        int n = Math.min(registry.size(), net.length);
        double sumNet = 0;
        double sumGross = 0;
        for (int i = 0; i < n; i++) {
            sumNet += Math.abs(net[i]);
            sumGross += gross[i];
        }
        return sumGross <= 0 ? 0 : 1 - sumNet / sumGross;
    }

    /** Non-deliverable notional still awaiting its fixing, per pair. */
    public double pendingFixing(String pair) {
        return pendingFixings.getOrDefault(pair, 0.0);
    }

    /**
     * Releases {@code notional} (positive, gross) of pending fixing after
     * the fixing occurs — without this the pending number only ever
     * grows and goes stale the day after the first fixing. The fixed
     * NDF's currency delta must be re-booked separately as the
     * offsetting flow when it cash-settles (position decomposition, not
     * lifecycle — see class doc). Over-settling throws: releasing more
     * than is pending means the caller's fixing ledger disagrees with
     * the book's, which is a reconciliation problem, not a rounding one.
     */
    public void settleFixing(String pair, double notional) {
        requirePair(pair);
        requirePositive(notional, "notional");
        double pending = pendingFixings.getOrDefault(pair, 0.0);
        if (notional > pending + 1e-9) {
            throw new IllegalArgumentException("settling " + notional
                    + " but only " + pending + " is pending on " + pair);
        }
        double remaining = pending - notional;
        if (remaining <= 1e-9) {
            pendingFixings.remove(pair);
        } else {
            pendingFixings.put(pair, remaining);
        }
    }

    public long flowsBooked() {
        return flowsBooked;
    }

    public FactorRegistry factors() {
        return registry;
    }

    public java.util.Set<String> desks() {
        return java.util.Collections.unmodifiableSet(deskNet.keySet());
    }

    // ------------------------------------------------------------------
    // Risk report
    // ------------------------------------------------------------------

    /**
     * The book-level risk report. {@code covariance} is over the factor
     * space in REGISTRY ORDER ({@code factors().name(i)}) with entries in
     * (factor-return)² units matching each factor's exposure units — the
     * same contract as {@link VarEngine}. The headline number is the
     * diversification benefit: standalone desk VaRs minus the netted
     * book's VaR, i.e. what running the risk CENTRALLY is worth.
     */
    public CrbReport report(double[][] covariance, double confidence) {
        int n = registry.size();
        if (covariance.length != n) {
            throw new IllegalArgumentException("covariance must be " + n + "x" + n
                    + " over factors().name(i) in registry order");
        }
        double[] netVec = netExposures();
        double bookVar = VarEngine.deltaNormalVar(netVec, covariance, confidence);
        double bookEs = VarEngine.deltaNormalEs(netVec, covariance, confidence);
        double standalone = 0;
        for (double[] d : deskNet.values()) {
            double[] full = new double[n];
            System.arraycopy(d, 0, full, 0, Math.min(d.length, n));
            standalone += VarEngine.deltaNormalVar(full, covariance, confidence);
        }
        double sumNet = 0;
        double sumGross = 0;
        int booked = Math.min(n, net.length);
        for (int i = 0; i < booked; i++) {
            sumNet += Math.abs(net[i]);
            sumGross += gross[i];
        }
        return new CrbReport(sumGross, sumNet, nettingEfficiency(),
                bookVar, bookEs, standalone, standalone - bookVar);
    }

    /**
     * @param grossExposure    Σ over factors of gross booked notional
     * @param netExposure      Σ over factors of |netted| notional
     * @param nettingEfficiency 1 − net/gross
     * @param var              delta-normal VaR of the netted book
     * @param es               delta-normal ES of the netted book
     * @param standaloneDeskVar Σ of per-desk standalone VaRs
     * @param diversificationBenefit standaloneDeskVar − var: what central
     *                               netting is worth in VaR terms
     */
    public record CrbReport(double grossExposure, double netExposure,
                            double nettingEfficiency, double var, double es,
                            double standaloneDeskVar, double diversificationBenefit) {
    }

    // ------------------------------------------------------------------
    // Overnight persistence (persist.Checkpoint section body)
    // ------------------------------------------------------------------

    /**
     * Serializes the whole netted book — factor names, net/gross, per-desk
     * attribution, pending fixings, flow count — for a
     * {@code persist.Checkpoint} section. Restore into a FRESH instance
     * via {@link #readState}: a book that relearns its positions every
     * morning is not a book.
     */
    public void writeState(java.io.DataOutput out) throws java.io.IOException {
        out.writeByte(1);
        int n = registry.size();
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            out.writeUTF(registry.name(i));
        }
        for (int i = 0; i < n; i++) {
            out.writeDouble(i < net.length ? net[i] : 0);
            out.writeDouble(i < gross.length ? gross[i] : 0);
        }
        out.writeInt(deskNet.size());
        for (Map.Entry<String, double[]> e : deskNet.entrySet()) {
            out.writeUTF(e.getKey());
            double[] d = e.getValue();
            for (int i = 0; i < n; i++) {
                out.writeDouble(i < d.length ? d[i] : 0);
            }
        }
        out.writeInt(pendingFixings.size());
        for (Map.Entry<String, Double> e : pendingFixings.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeDouble(e.getValue());
        }
        out.writeLong(flowsBooked);
    }

    /** Restores state written by {@link #writeState} into THIS (fresh) book. */
    public void readState(java.io.DataInput in) throws java.io.IOException {
        com.quantfinlib.persist.Checkpoint.requireVersion(in, 1, "CentralRiskBook");
        if (registry.size() != 0 || flowsBooked != 0) {
            throw new IllegalStateException(
                    "restore into a FRESH CentralRiskBook, not one already booked into");
        }
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            registry.id(in.readUTF());          // ids are registration order
        }
        net = new double[Math.max(16, n)];
        gross = new double[net.length];
        for (int i = 0; i < n; i++) {
            net[i] = in.readDouble();
            gross[i] = in.readDouble();
        }
        int desks = in.readInt();
        for (int k = 0; k < desks; k++) {
            String desk = in.readUTF();
            double[] d = new double[net.length];
            for (int i = 0; i < n; i++) {
                d[i] = in.readDouble();
            }
            deskNet.put(desk, d);
        }
        int fixings = in.readInt();
        for (int k = 0; k < fixings; k++) {
            String pair = in.readUTF();
            pendingFixings.put(pair, in.readDouble());
        }
        flowsBooked = in.readLong();
    }

    // ------------------------------------------------------------------

    private void add(String desk, String factor, double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("non-finite exposure for " + factor);
        }
        int id = registry.id(factor);
        if (id >= net.length) {
            net = java.util.Arrays.copyOf(net, Math.max(net.length * 2, id + 1));
            gross = java.util.Arrays.copyOf(gross, net.length);
        }
        net[id] += amount;
        gross[id] += Math.abs(amount);
        double[] d = deskNet.computeIfAbsent(desk, k -> new double[net.length]);
        if (id >= d.length) {
            d = java.util.Arrays.copyOf(d, net.length);
            deskNet.put(desk, d);
        }
        d[id] += amount;
    }

    private static void requireDesk(String desk) {
        if (desk == null || desk.isBlank()) {
            throw new IllegalArgumentException("desk must be named");
        }
    }

    private static void requirePair(String pair) {
        if (pair == null || pair.length() != 6) {
            throw new IllegalArgumentException("pair must be 6 chars like EURUSD: " + pair);
        }
    }

    private static void requireFinite(double x, String name) {
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requirePositive(double x, String name) {
        if (!(x > 0) || x == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }
}

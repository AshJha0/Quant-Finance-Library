package com.quantfinlib.crb;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.risk.VarEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The central risk book — one netted view of the firm's market risk
 * across desks and products. Every instrument is decomposed into a
 * common risk-factor space at booking time, and that is the entire
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
 *       point;</li>
 *   <li>{@code CCY:<ccy>}: currency exposure in native units of that
 *       currency (an EURUSD buy of 10M books CCY:EUR +10M euros and
 *       CCY:USD −10M·rate dollars). This is what lets spot, swaps,
 *       NDFs and option deltas net at the currency level;</li>
 *   <li>{@code FXPOINTS:<pair>} — forward-points risk of swaps: P&amp;L
 *       in quote units per 1.0 move in the far−near differential;</li>
 *   <li>{@code FXGAMMA:<pair>} / {@code FXVEGA:<pair>} — dollar gamma
 *       per 1% and vega per vol point, in quote-currency units.</li>
 * </ul>
 *
 * <p>Sign convention: positive quantity/notional = the book is long.
 * Book what the book absorbs (a client sell hits the book as a buy).
 * NDFs carry currency delta until fixing; the pending non-deliverable
 * notional is tracked per pair in {@link #pendingFixing(String)} and
 * released via {@link #settleFixing} once fixings occur. This is a
 * risk ledger, not a cash ledger: premium and settlement cash legs are
 * not tracked (the same delta-only stance applies to cash equities, with
 * no settlement cash, and to options, with no premium), and lifecycle
 * events (expiry, exercise, settlement) re-book as offsetting flows.
 * Deterministic, single-threaded, research/warm lane — pair it with
 * {@link InternalizationEngine} for flow decisions and
 * {@link CrbAutoHedger} for the hedging loop.</p>
 */
public final class CentralRiskBook {
    /** Creates an instance; the explicit form of the default constructor. */
    public CentralRiskBook() {
    }


    private final FactorRegistry registry = new FactorRegistry();
    private double[] net = new double[16];
    private double[] gross = new double[16];
    private long flowsBooked;

    // Dense ids. Desks, equity symbols and FX pairs are resolved to ints once
    // (HashMap lookup on an interned key, no allocation), and factor ids per
    // handle are resolved on first booking, so a booking after warm-up does
    // no string building and no boxing. Factor registration order is the
    // order of first booking, as before (it fixes the covariance layout).
    private final Map<String, Integer> deskIds = new HashMap<>();
    private final List<String> deskNames = new ArrayList<>();
    private double[][] deskVec = new double[4][];          // null until the desk books

    private static final int EQ = 0;
    private static final int EQGAMMA = 1;
    private static final int EQVEGA = 2;
    private static final String[] EQ_PREFIX = {"EQ:", "EQGAMMA:", "EQVEGA:"};
    private final Map<String, Integer> equityHandles = new HashMap<>();
    private final List<String> equitySymbols = new ArrayList<>();
    private int[] equityFactors = filled(3 * 8);

    private static final int CCY_BASE = 0;
    private static final int CCY_QUOTE = 1;
    private static final int FXPOINTS = 2;
    private static final int FXGAMMA = 3;
    private static final int FXVEGA = 4;
    private final Map<String, Integer> pairHandles = new HashMap<>();
    private final List<String> pairNames = new ArrayList<>();
    private int[] pairFactors = filled(5 * 8);
    private double[] pendingByPair = new double[8];
    private boolean[] pendingPresent = new boolean[8];

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, -1);
        return a;
    }

    // ------------------------------------------------------------------
    // Dense-id handles (setup; the int-keyed booking methods use them)
    // ------------------------------------------------------------------

    /**
     * Dense id of a desk, registered on first sight. The desk appears in
     * {@link #desks()} once it books a flow.
     *
     * @param desk desk name, non-blank
     * @return the desk id
     */
    public int deskId(String desk) {
        requireDesk(desk);
        Integer id = deskIds.get(desk);
        if (id != null) {
            return id;
        }
        int d = deskNames.size();
        deskNames.add(desk);
        deskIds.put(desk, d);
        if (d >= deskVec.length) {
            deskVec = Arrays.copyOf(deskVec, deskVec.length * 2);
        }
        return d;
    }

    /**
     * Handle of an equity symbol for the int-keyed equity booking methods.
     * Registers no risk factor until the first booking.
     *
     * @param symbol equity symbol
     * @return the handle
     */
    public int equityHandle(String symbol) {
        Integer h = equityHandles.get(symbol);
        if (h != null) {
            return h;
        }
        int k = equitySymbols.size();
        equitySymbols.add(symbol);
        equityHandles.put(symbol, k);
        if (3 * k + 3 > equityFactors.length) {
            int old = equityFactors.length;
            equityFactors = Arrays.copyOf(equityFactors, old * 2);
            Arrays.fill(equityFactors, old, equityFactors.length, -1);
        }
        return k;
    }

    /**
     * Handle of an FX pair ("EURUSD") for the int-keyed FX booking methods.
     * Registers no risk factor until the first booking.
     *
     * @param pair six-letter pair
     * @return the handle
     */
    public int fxPairHandle(String pair) {
        Integer h = pairHandles.get(pair);
        if (h != null) {
            return h;
        }
        requirePair(pair);
        int k = pairNames.size();
        pairNames.add(pair);
        pairHandles.put(pair, k);
        if (5 * k + 5 > pairFactors.length) {
            int old = pairFactors.length;
            pairFactors = Arrays.copyOf(pairFactors, old * 2);
            Arrays.fill(pairFactors, old, pairFactors.length, -1);
        }
        if (k >= pendingByPair.length) {
            pendingByPair = Arrays.copyOf(pendingByPair, pendingByPair.length * 2);
            pendingPresent = Arrays.copyOf(pendingPresent, pendingByPair.length);
        }
        return k;
    }

    private int equityFactor(int handle, int leg) {
        int i = 3 * handle + leg;
        int id = equityFactors[i];
        if (id < 0) {
            id = registry.id(EQ_PREFIX[leg] + equitySymbols.get(handle));   // first booking only
            equityFactors[i] = id;
        }
        return id;
    }

    private int pairFactor(int handle, int leg) {
        int i = 5 * handle + leg;
        int id = pairFactors[i];
        if (id < 0) {
            String pair = pairNames.get(handle);
            String name = switch (leg) {
                case CCY_BASE -> "CCY:" + pair.substring(0, 3);
                case CCY_QUOTE -> "CCY:" + pair.substring(3, 6);
                case FXPOINTS -> "FXPOINTS:" + pair;
                case FXGAMMA -> "FXGAMMA:" + pair;
                default -> "FXVEGA:" + pair;
            };
            id = registry.id(name);                                         // first booking only
            pairFactors[i] = id;
        }
        return id;
    }

    // ------------------------------------------------------------------
    // Booking — equities
    // ------------------------------------------------------------------

    /** Cash equity: {@code qty} shares (signed) at {@code price}. */
    public void bookCashEquity(String desk, String symbol, double qty, double price) {
        bookCashEquity(deskId(desk), equityHandle(symbol), qty, price);
    }

    /**
     * As {@link #bookCashEquity(String, String, double, double)} with dense ids
     * from {@link #deskId} and {@link #equityHandle}. No allocation after the
     * first booking on the handle.
     *
     * @param desk   desk id
     * @param equity equity handle
     * @param qty    signed shares
     * @param price  price per share
     */
    public void bookCashEquity(int desk, int equity, double qty, double price) {
        requireFinite(qty, "qty");
        requirePositive(price, "price");
        add(desk, equityFactor(equity, EQ), qty * price);
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
        bookEquityOption(deskId(desk), equityHandle(symbol), type, contracts, multiplier, spot,
                strike, rate, carry, vol, timeYears);
    }

    /**
     * As the String-keyed overload with dense ids from {@link #deskId} and
     * {@link #equityHandle}.
     *
     * @param desk       desk id
     * @param equity     equity handle
     * @param type       call or put
     * @param contracts  signed contracts
     * @param multiplier shares per contract
     * @param spot       underlying price
     * @param strike     strike
     * @param rate       risk-free rate
     * @param carry      dividend yield
     * @param vol        volatility
     * @param timeYears  time to expiry in years
     */
    public void bookEquityOption(int desk, int equity, OptionType type,
                                 double contracts, double multiplier,
                                 double spot, double strike, double rate, double carry,
                                 double vol, double timeYears) {
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
        // Compute-validate-commit: every leg must be finite before any
        // add(), or an overflow on the second leg would leave a
        // half-booked flow in the netted arrays.
        double deltaLeg = units * delta * spot;
        double gammaLeg = units * gamma * spot * spot / 100;                 // $ per 1%
        double vegaLeg = units * vega / 100;                                 // per vol point
        requireFinite(deltaLeg, "delta leg");
        requireFinite(gammaLeg, "gamma leg");
        requireFinite(vegaLeg, "vega leg");
        add(desk, equityFactor(equity, EQ), deltaLeg);
        add(desk, equityFactor(equity, EQGAMMA), gammaLeg);
        add(desk, equityFactor(equity, EQVEGA), vegaLeg);
        flowsBooked++;
    }

    // ------------------------------------------------------------------
    // Booking — FX
    // ------------------------------------------------------------------

    /**
     * FX spot on {@code pair} ("EURUSD"): buy {@code baseNotional} of the
     * base currency (signed) at {@code rate}. Decomposes into the two
     * currency legs so it nets against every other product's FX delta.
     */
    public void bookFxSpot(String desk, String pair, double baseNotional, double rate) {
        bookFxSpot(deskId(desk), fxPairHandle(pair), baseNotional, rate);
    }

    /**
     * As the String-keyed overload with dense ids from {@link #deskId} and
     * {@link #fxPairHandle}. No allocation after the first booking on the handle.
     *
     * @param desk desk id
     * @param pair pair handle
     * @param baseNotional signed base-currency notional
     * @param rate spot rate
     */
    public void bookFxSpot(int desk, int pair, double baseNotional, double rate) {
        requireFinite(baseNotional, "baseNotional");
        requirePositive(rate, "rate");
        double quoteLeg = -baseNotional * rate;
        requireFinite(quoteLeg, "quote leg");
        add(desk, pairFactor(pair, CCY_BASE), baseNotional);
        add(desk, pairFactor(pair, CCY_QUOTE), quoteLeg);
        flowsBooked++;
    }

    /**
     * FX swap (buy-sell base for positive notional): near leg at
     * {@code nearRate}, far leg back at {@code farRate}. The base-currency
     * legs cancel exactly (that is what a swap is); what remains is the
     * quote-currency cash-flow imbalance and the forward-points risk.
     * The FXPOINTS factor is the sensitivity: a buy-sell locked in at
     * today's (far − near) must close out at tomorrow's, so its P&amp;L
     * per +1.0 move in the market's far−near differential is
     * {@code −baseNotional} — points widening hurts the buy-sell.
     *
     * <p>The quote-leg imbalance {@code B·(far − near)} is booked at face,
     * undiscounted and without its value date; the risk report therefore
     * measures it on undiscounted notional. For long-dated swaps discount
     * the imbalance before booking if the report must be on present value.</p>
     */
    public void bookFxSwap(String desk, String pair, double baseNotional,
                           double nearRate, double farRate) {
        bookFxSwap(deskId(desk), fxPairHandle(pair), baseNotional, nearRate, farRate);
    }

    /**
     * As the String-keyed overload with dense ids from {@link #deskId} and
     * {@link #fxPairHandle}. No allocation after the first booking on the handle.
     *
     * @param desk desk id
     * @param pair pair handle
     * @param baseNotional signed base notional of the far leg
     * @param nearRate near all-in rate
     * @param farRate far all-in rate
     */
    public void bookFxSwap(int desk, int pair, double baseNotional,
                           double nearRate, double farRate) {
        requireFinite(baseNotional, "baseNotional");
        requirePositive(nearRate, "nearRate");
        requirePositive(farRate, "farRate");
        double quoteImbalance = baseNotional * (farRate - nearRate);
        requireFinite(quoteImbalance, "quote-leg imbalance");
        add(desk, pairFactor(pair, CCY_QUOTE), quoteImbalance);
        add(desk, pairFactor(pair, FXPOINTS), -baseNotional);
        flowsBooked++;
    }

    /**
     * NDF: buy {@code baseNotional} of base forward at {@code fwdRate}.
     * Economically a forward until the fixing (full currency delta on
     * both legs), with the non-deliverable notional tracked per pair
     * (settlement is cash in the deliverable currency; what fixes at the
     * fixing is the rate, and until then the book is exposed).
     * Both legs are booked at face (undiscounted, date-blind); see
     * {@link #bookFxSwap} for what that means for the report.
     */
    public void bookNdf(String desk, String pair, double baseNotional, double fwdRate) {
        bookNdf(deskId(desk), fxPairHandle(pair), baseNotional, fwdRate);
    }

    /**
     * As the String-keyed overload with dense ids from {@link #deskId} and
     * {@link #fxPairHandle}. No allocation after the first booking on the handle.
     *
     * @param desk desk id
     * @param pair pair handle
     * @param baseNotional signed base notional
     * @param fwdRate contracted forward rate
     */
    public void bookNdf(int desk, int pair, double baseNotional, double fwdRate) {
        requireFinite(baseNotional, "baseNotional");
        requirePositive(fwdRate, "fwdRate");
        double quoteLeg = -baseNotional * fwdRate;
        requireFinite(quoteLeg, "quote leg");
        add(desk, pairFactor(pair, CCY_BASE), baseNotional);
        add(desk, pairFactor(pair, CCY_QUOTE), quoteLeg);
        pendingByPair[pair] += Math.abs(baseNotional);
        pendingPresent[pair] = true;
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
        bookFxOption(deskId(desk), fxPairHandle(pair), type, baseNotional, spotRate, strike,
                domesticRate, foreignRate, vol, timeYears);
    }

    /**
     * As the String-keyed overload with dense ids from {@link #deskId} and
     * {@link #fxPairHandle}. No allocation after the first booking on the handle.
     *
     * @param desk desk id
     * @param pair pair handle
     * @param type call or put on the base currency
     * @param baseNotional signed base notional
     * @param spotRate spot rate
     * @param strike strike
     * @param domesticRate quote-currency rate
     * @param foreignRate base-currency rate
     * @param vol volatility
     * @param timeYears time to expiry in years
     */
    public void bookFxOption(int desk, int pair, OptionType type, double baseNotional,
                             double spotRate, double strike, double domesticRate,
                             double foreignRate, double vol, double timeYears) {
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
        // Compute-validate-commit (see bookEquityOption).
        double deltaBase = baseNotional * delta;
        double quoteLeg = -deltaBase * spotRate;
        double gammaLeg = baseNotional * gamma * spotRate * spotRate / 100;
        double vegaLeg = baseNotional * vega / 100;
        requireFinite(deltaBase, "delta leg");
        requireFinite(quoteLeg, "quote leg");
        requireFinite(gammaLeg, "gamma leg");
        requireFinite(vegaLeg, "vega leg");
        add(desk, pairFactor(pair, CCY_BASE), deltaBase);
        add(desk, pairFactor(pair, CCY_QUOTE), quoteLeg);
        add(desk, pairFactor(pair, FXGAMMA), gammaLeg);
        add(desk, pairFactor(pair, FXVEGA), vegaLeg);
        flowsBooked++;
    }

    // ------------------------------------------------------------------
    // The netted view
    // ------------------------------------------------------------------

    /** Net exposure on a factor (0 for a factor never booked). */
    public double exposure(String factor) {
        int id = registry.idIfPresent(factor);
        // A factor can be registered but never booked (a hedge universe
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
        Integer k = deskIds.get(desk);
        double[] d = k == null ? null : deskVec[k];
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
        Integer h = pairHandles.get(pair);
        return h == null || !pendingPresent[h] ? 0.0 : pendingByPair[h];
    }

    /**
     * Releases {@code notional} (positive, gross) of pending fixing after
     * the fixing occurs. Without this the pending number only ever
     * grows and goes stale the day after the first fixing. The fixed
     * NDF's currency delta must be re-booked separately as the
     * offsetting flow when it cash-settles (position decomposition, not
     * lifecycle; see class doc). Over-settling throws: releasing more
     * than is pending means the caller's fixing ledger disagrees with
     * the book's, which is a reconciliation problem, not a rounding one.
     */
    public void settleFixing(String pair, double notional) {
        requirePair(pair);
        requirePositive(notional, "notional");
        double pending = pendingFixing(pair);
        if (notional > pending + 1e-9) {
            throw new IllegalArgumentException("settling " + notional
                    + " but only " + pending + " is pending on " + pair);
        }
        double remaining = pending - notional;
        int h = fxPairHandle(pair);
        if (remaining <= 1e-9) {
            pendingPresent[h] = false;
            pendingByPair[h] = 0;
        } else {
            pendingByPair[h] = remaining;
        }
    }

    public long flowsBooked() {
        return flowsBooked;
    }

    public FactorRegistry factors() {
        return registry;
    }

    public java.util.Set<String> desks() {
        SortedSet<String> out = new TreeSet<>();
        for (int k = 0; k < deskNames.size(); k++) {
            if (deskVec[k] != null) {
                out.add(deskNames.get(k));
            }
        }
        return Collections.unmodifiableSortedSet(out);
    }

    // ------------------------------------------------------------------
    // Risk report
    // ------------------------------------------------------------------

    /**
     * The book-level risk report. {@code covariance} is over the factor
     * space in registry order ({@code factors().name(i)}) with entries in
     * (factor-return)² units matching each factor's exposure units — the
     * same contract as {@link VarEngine}. The headline number is the
     * diversification benefit: standalone desk VaRs minus the netted
     * book's VaR, i.e. what running the risk centrally is worth.
     *
     * <p>VaR and ES are second order ({@link VarEngine#deltaGammaVar},
     * {@link VarEngine#deltaGammaEs}, Cornish-Fisher). Dollar gamma is not
     * an exposure to a factor return of its own, so the {@code EQGAMMA:} and
     * {@code FXGAMMA:} rows are taken out of the linear vector and placed in
     * the gamma matrix on their underlying factors, with factor returns read
     * as relative moves:</p>
     * <ul>
     *   <li>{@code EQGAMMA:s} with dollar gamma G per 1% (G = Γ·S²/100)
     *       adds {@code ½·100·G·r_s²}, i.e. {@code Γ[EQ:s][EQ:s] += 100·G};</li>
     *   <li>{@code FXGAMMA:BBBQQQ} adds {@code ½·100·G·(r_BBB − r_QQQ)²}
     *       on the two {@code CCY:} factors (the pair's relative move is the
     *       difference of the two currencies' moves against the reporting
     *       currency); G is in quote-currency units, so the report is in
     *       one currency only when the quote currency is the reporting one
     *       or the caller has converted.</li>
     * </ul>
     * <p>The covariance rows of the gamma factors are not used and may be
     * zero. Vega rows ({@code EQVEGA:}, {@code FXVEGA:}) stay linear: their
     * factor return is the change in implied vol in points. For a book
     * without options the result equals delta-normal VaR exactly.</p>
     */
    public CrbReport report(double[][] covariance, double confidence) {
        int n = registry.size();
        if (covariance.length != n) {
            throw new IllegalArgumentException("covariance must be " + n + "x" + n
                    + " over factors().name(i) in registry order");
        }
        double[] netVec = netExposures();
        double[][] gammaMap = new double[n][n];
        double[] linear = splitGamma(netVec, gammaMap);
        double bookVar = VarEngine.deltaGammaVar(linear, gammaMap, covariance, confidence);
        double bookEs = VarEngine.deltaGammaEs(linear, gammaMap, covariance, confidence);
        double standalone = 0;
        for (int k : bookedDesksByName()) {
            double[] d = deskVec[k];
            double[] full = new double[n];
            System.arraycopy(d, 0, full, 0, Math.min(d.length, n));
            double[][] g = new double[n][n];
            double[] lin = splitGamma(full, g);
            standalone += VarEngine.deltaGammaVar(lin, g, covariance, confidence);
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
     * Moves dollar-gamma rows into a gamma matrix on their underlying
     * factors (see {@link #report}); returns the linear exposure vector.
     */
    private double[] splitGamma(double[] exposures, double[][] gammaOut) {
        double[] linear = exposures.clone();
        for (int i = 0; i < exposures.length; i++) {
            String name = registry.name(i);
            double g = exposures[i];
            if (name.startsWith("EQGAMMA:")) {
                linear[i] = 0;
                if (g != 0) {
                    int u = registry.idIfPresent("EQ:" + name.substring(8));
                    if (u < 0) {
                        throw new IllegalStateException("no EQ factor for " + name);
                    }
                    gammaOut[u][u] += 100 * g;
                }
            } else if (name.startsWith("FXGAMMA:")) {
                linear[i] = 0;
                if (g != 0) {
                    String pair = name.substring(8);
                    int b = registry.idIfPresent("CCY:" + pair.substring(0, 3));
                    int q = registry.idIfPresent("CCY:" + pair.substring(3, 6));
                    if (b < 0 || q < 0) {
                        throw new IllegalStateException("no CCY factors for " + name);
                    }
                    double h = 100 * g;
                    gammaOut[b][b] += h;
                    gammaOut[q][q] += h;
                    gammaOut[b][q] -= h;
                    gammaOut[q][b] -= h;
                }
            }
        }
        return linear;
    }

    /**
     * @param grossExposure    Σ over factors of gross booked notional
     * @param netExposure      Σ over factors of |netted| notional
     * @param nettingEfficiency 1 − net/gross
     * @param var              delta-gamma (Cornish-Fisher) VaR of the netted book
     * @param es               delta-gamma (Cornish-Fisher) ES of the netted book
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
     * Serializes the whole netted book (factor names, net/gross, per-desk
     * attribution, pending fixings, flow count) for a
     * {@code persist.Checkpoint} section. Restore into a fresh instance
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
        int[] desks = bookedDesksByName();
        out.writeInt(desks.length);
        for (int k : desks) {
            out.writeUTF(deskNames.get(k));
            double[] d = deskVec[k];
            for (int i = 0; i < n; i++) {
                out.writeDouble(i < d.length ? d[i] : 0);
            }
        }
        TreeSet<String> pending = new TreeSet<>();
        for (int h = 0; h < pairNames.size(); h++) {
            if (pendingPresent[h]) {
                pending.add(pairNames.get(h));
            }
        }
        out.writeInt(pending.size());
        for (String pair : pending) {
            out.writeUTF(pair);
            out.writeDouble(pendingFixing(pair));
        }
        out.writeLong(flowsBooked);
    }

    /** Restores state written by {@link #writeState} into this (fresh) book. */
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
            deskVec[deskId(desk)] = d;
        }
        int fixings = in.readInt();
        for (int k = 0; k < fixings; k++) {
            String pair = in.readUTF();
            int h = fxPairHandle(pair);
            pendingByPair[h] = in.readDouble();
            pendingPresent[h] = true;
        }
        flowsBooked = in.readLong();
    }

    // ------------------------------------------------------------------

    private void add(int desk, int id, double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("non-finite exposure for " + registry.name(id));
        }
        if (id >= net.length) {
            net = Arrays.copyOf(net, Math.max(net.length * 2, id + 1));
            gross = Arrays.copyOf(gross, net.length);
        }
        net[id] += amount;
        gross[id] += Math.abs(amount);
        double[] d = deskVec[desk];
        if (d == null) {
            d = new double[net.length];          // first flow of this desk
            deskVec[desk] = d;
        } else if (id >= d.length) {
            d = Arrays.copyOf(d, net.length);
            deskVec[desk] = d;
        }
        d[id] += amount;
    }

    /** Ids of desks that have booked, in name order (deterministic output). */
    private int[] bookedDesksByName() {
        TreeSet<String> names = new TreeSet<>();
        for (int k = 0; k < deskNames.size(); k++) {
            if (deskVec[k] != null) {
                names.add(deskNames.get(k));
            }
        }
        int[] out = new int[names.size()];
        int i = 0;
        for (String n : names) {
            out[i++] = deskIds.get(n);
        }
        return out;
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

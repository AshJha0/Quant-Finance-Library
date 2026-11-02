package com.quantfinlib.credit;

import com.quantfinlib.rates.DiscountCurve;
import com.quantfinlib.rates.YieldCurve;

import java.util.List;
import java.util.Objects;

/**
 * Monte Carlo exposure generator for a netting set of multi-curve swaps
 * and FX forwards on several currency pairs, each foreign currency with
 * its own stochastic short rate. The output {@link ExposureProfile} feeds
 * the profile overloads of {@link CvaApproximator}.
 *
 * <p>In 3.0 this class absorbed the single-currency {@code ExposureSimulator}
 * of 2.1 to 2.4 (decision D4). A single-currency market is the special case
 * with zero pairs, or one pair whose foreign rate vol is 0; on that case this
 * engine reproduces the 2.4 single-currency results path for path from the
 * same seed, to the tolerance pinned in {@code ExposureSimulatorFoldTest}
 * (own test against values printed by the 2.4.0 code). The trade terms
 * {@link SwapTerms}, {@link FxForwardTerms} and {@link Csa} are the 2.x
 * {@code ExposureSimulator.Swap}, {@code FxForward} and {@code Csa} with the
 * same components.</p>
 *
 * <p><b>Plain idea.</b> Every currency has a Hull-White short rate fitted
 * to its own discount curve, every pair has a lognormal spot, and all the
 * Brownian motions are joined by one correlation matrix. Everything is
 * simulated under the domestic risk-neutral measure, so a foreign rate
 * picks up the quanto drift: if the foreign rate tends to rise when the
 * foreign currency strengthens, the domestic investor sees the foreign
 * rate drift down.</p>
 *
 * <p><b>Formal statement.</b> Factors are ordered
 * {@code [W_d, W_1, W_S1, W_2, W_S2, ...]} (domestic rate, then per pair
 * the foreign rate and the spot). Under the domestic measure Q_d
 * (Brigo and Mercurio, Interest Rate Models, 2nd ed., 2006, section 12.1,
 * the two-currency Hull-White model):</p>
 *
 * <pre>
 *   r_d = x_d + phi_d,   dx_d = -a_d x_d dt + sigma_d dW_d
 *   r_c = x_c + phi_c,   dx_c = (-a_c x_c - rho_{c,Sc} sigma_c sigma_Sc) dt + sigma_c dW_c
 *   dS_c / S_c = (r_d - r_c) dt + sigma_Sc dW_Sc
 * </pre>
 *
 * <p>The quanto term {@code -rho sigma_c sigma_S} is the Girsanov shift
 * from the foreign to the domestic bank-account measure. Bond prices in
 * each currency use the Hull-White closed form (Brigo and Mercurio, eq. 3.39)
 * with that currency's (a, sigma, curve) and the state x_c:</p>
 *
 * <pre>
 *   P(t,T) = P(0,T)/P(0,t) * exp( (V(t,T) - V(0,T) + V(0,t)) / 2 - B(t,T) x(t) )
 *   B(t,T) = (1 - e^{-a (T-t)}) / a
 *   V(t,T) = sigma^2/a^2 [ tau - 2 B(tau) + (1 - e^{-2 a tau}) / (2a) ],  tau = T - t
 * </pre>
 *
 * <p> The zero-mean Gaussian
 * part of every state (x, its integral, and the Brownian levels) is drawn
 * exactly per grid step from its joint covariance, built from integrals
 * of products of the kernels {@code c0 + c1 e^{-a s}}; the quanto drift
 * enters as the deterministic mean {@code -rho sigma_c sigma_S B_c(t)} of
 * x_c and {@code -rho sigma_c sigma_S (t - B_c(t)) / a_c} of its integral.
 * The spot is then exact:</p>
 *
 * <pre>
 *   S_c(t) = S_c(0) * exp(-int r_c) / D_d(0,t) * exp(-sigma_S^2 t / 2 + sigma_S W_S(t))
 *   exp(-int_0^t r_c) = P_c(0,t) exp(-V_c(0,t)/2 - int_0^t x_c)
 * </pre>
 *
 * <p>so {@code D_d(0,t) S_c(t) exp(int r_c)} is an exact martingale and
 * {@code E[D_d(0,t) S_c(t) P_c(t,T)] = S_c(0) P_c(0,T)} with no
 * discretisation bias (tested).</p>
 *
 * <p>Multi-curve swaps: each swap may carry its own projection curve.
 * The basis to the discount curve is held deterministic: for a period
 * (S, T) the simulated projection ratio is
 * {@code P^f(t,S)/P^f(t,T) = k(S,T) P_d(t,S)/P_d(t,T)} with
 * {@code k = [P^f(0,S)/P^f(0,T)] / [P_d(0,S)/P_d(0,T)]}, the usual
 * deterministic-spread multi-curve extension (Mercurio, Interest rate
 * and the credit crunch: new formulas and market models, 2009, section 3,
 * with zero spread volatility). The floating coupon pays
 * {@code k / P_d(S,T) - 1} per unit notional at T, fixed at S. With the
 * projection equal to the discount curve, k = 1 and the 2.1 single-curve
 * swap is recovered. Since 2.3 the basis may instead be stochastic
 * ({@link BasisFactor}), and swaps may be written in a foreign currency
 * ({@link ForeignSwap}); both are opt-in through the ten-argument
 * {@code simulate}.</p>
 *
 * <p><b>Worked example.</b> A 5y EUR/USD forward (domestic USD) with
 * sigma_S = 12 %, sigma_EUR = 1 %, rho(EUR rate, spot) = -0.5: the
 * forward FX rate is lognormal under the 5y USD forward measure with
 * variance {@code int |sigma_S e_S - sigma_c B_c e_c + sigma_d B_d e_d|^2},
 * so the discounted expected exposure at every date is a Black call; the
 * test {@code fxForwardEeMatchesBlackWithStochasticRates} checks the
 * simulation against that integral (own test, independent quadrature).</p>
 *
 * <p>Wrong-way risk stays path-consistent: the rates driver of the
 * profile is the domestic Brownian W_d and the FX driver is W_S of the
 * pair chosen by {@code wrongWayFxPair}, read on the same paths that
 * produce the exposure. Swaps are domestic only.</p>
 *
 * <p>Trades and collateral: a vanilla swap (fixed against a floating leg on
 * the same schedule, accrual = time between dates) is valued at t from the
 * simulated curves, with the floating coupon in progress fixed at its reset;
 * reset dates are added to the internal grid. An FX forward to buy {@code N}
 * foreign at strike K is worth {@code N (S P_f(t,T) - K P_d(t,T))}. A cash
 * flow paid at t is not part of the value at t. Under a two-way CSA the
 * target at a margin call on value V is {@code V - H} if V &gt; H,
 * {@code V + H} if V &lt; -H, else 0, with threshold H; the balance moves to
 * the target only when the change is at least the minimum transfer amount.
 * The call for exposure date t is made on the value at {@code t - MPR}, so
 * the collateralised exposure at t is {@code max(V(t) - C(t - MPR), 0)}
 * (Gregory, The xVA Challenge, 3rd ed., 2015, ch. 7). Research lane.</p>
 */
public final class MultiCurrencyExposureSimulator {

    private MultiCurrencyExposureSimulator() {
    }

    /** A discount function {@code t -> P(0,t)}, t in years, P(0,0) = 1. */
    @FunctionalInterface
    public interface Curve {
        /**
         * Discount factor to time t.
         *
         * @param t years, &gt; 0
         * @return P(0,t)
         */
        double discountFactor(double t);

        /**
         * Adapter for a {@link YieldCurve}.
         *
         * @param c curve
         * @return the discount function
         */
        static Curve of(YieldCurve c) {
            Objects.requireNonNull(c, "curve");
            return c::discountFactor;
        }

        /**
         * Adapter for a 2.1 multi-curve {@link DiscountCurve} (time in ACT/365F years).
         *
         * @param c curve
         * @return the discount function
         */
        static Curve of(DiscountCurve c) {
            Objects.requireNonNull(c, "curve");
            return c::discountFactor;
        }
    }

    /**
     * Domestic Hull-White short rate.
     *
     * @param discount      domestic discount curve
     * @param meanReversion a, &gt; 0
     * @param rateVol       sigma, &ge; 0
     */
    public record Domestic(Curve discount, double meanReversion, double rateVol) {
        /** Validates the parameters. */
        public Domestic {
            Objects.requireNonNull(discount, "discount");
            checkHw(meanReversion, rateVol);
        }
    }

    /**
     * One foreign currency: its Hull-White short rate and the spot against
     * the domestic currency (domestic per foreign).
     *
     * @param discount      foreign discount curve
     * @param meanReversion a_c, &gt; 0
     * @param rateVol       sigma_c, &ge; 0 (0 gives the 2.1 deterministic foreign rate)
     * @param spot          S_c(0), &gt; 0
     * @param fxVol         sigma_Sc, &ge; 0
     */
    public record Foreign(Curve discount, double meanReversion, double rateVol, double spot,
                          double fxVol) {
        /** Validates the parameters. */
        public Foreign {
            Objects.requireNonNull(discount, "discount");
            checkHw(meanReversion, rateVol);
            if (!(spot > 0) || spot == Double.POSITIVE_INFINITY
                    || !(fxVol >= 0) || fxVol == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("need positive finite spot and fxVol >= 0");
            }
        }
    }

    /**
     * The market: domestic rate, foreign currencies, and the correlation of
     * the factors {@code [W_d, W_1, W_S1, W_2, W_S2, ...]}.
     *
     * @param domestic    domestic rate model
     * @param foreign     foreign currencies, pair index = list index (may be empty)
     * @param correlation symmetric, unit diagonal, positive semi-definite, size 1 + 2 n
     */
    public record Market(Domestic domestic, List<Foreign> foreign, double[][] correlation) {
        /** Validates and copies. */
        public Market {
            Objects.requireNonNull(domestic, "domestic");
            foreign = List.copyOf(foreign);
            int n = 1 + 2 * foreign.size();
            Objects.requireNonNull(correlation, "correlation");
            if (correlation.length != n) {
                throw new IllegalArgumentException("correlation must be " + n + "x" + n);
            }
            double[][] c = new double[n][];
            for (int i = 0; i < n; i++) {
                if (correlation[i].length != n) {
                    throw new IllegalArgumentException("correlation must be " + n + "x" + n);
                }
                c[i] = correlation[i].clone();
            }
            for (int i = 0; i < n; i++) {
                if (c[i][i] != 1) {
                    throw new IllegalArgumentException("correlation diagonal must be 1");
                }
                for (int j = 0; j < n; j++) {
                    if (!(c[i][j] >= -1) || !(c[i][j] <= 1) || c[i][j] != c[j][i]) {
                        throw new IllegalArgumentException("correlation must be symmetric, in [-1, 1]");
                    }
                }
            }
            if (!positiveSemiDefinite(c)) {
                throw new IllegalArgumentException("correlation is not positive semi-definite");
            }
            correlation = c;
        }

        /** Defensive copy of the correlation matrix. */
        @Override
        public double[][] correlation() {
            double[][] c = new double[correlation.length][];
            for (int i = 0; i < c.length; i++) {
                c[i] = correlation[i].clone();
            }
            return c;
        }
    }

    /**
     * Vanilla fixed-for-floating swap.
     *
     * @param notional      notional, &gt; 0
     * @param fixedRate     fixed rate, decimal
     * @param payFixed      true for a payer swap (receive floating)
     * @param startYears    first reset time, &ge; 0
     * @param maturityYears last payment time, &gt; start
     * @param paymentsPerYear payments per year, &ge; 1
     */
    public record SwapTerms(double notional, double fixedRate, boolean payFixed, double startYears,
                       double maturityYears, int paymentsPerYear) {
        /** Validates the terms. */
        public SwapTerms {
            if (!(notional > 0) || notional == Double.POSITIVE_INFINITY || !Double.isFinite(fixedRate)) {
                throw new IllegalArgumentException("need positive notional and finite rate");
            }
            if (!(startYears >= 0) || !(maturityYears > startYears)
                    || maturityYears == Double.POSITIVE_INFINITY || paymentsPerYear < 1) {
                throw new IllegalArgumentException("need 0 <= start < maturity and frequency >= 1");
            }
        }

        /** Schedule T_0 = start, ..., T_n = maturity (a short last period if needed). */
        double[] dates() {
            double step = 1.0 / paymentsPerYear;
            int n = (int) Math.ceil((maturityYears - startYears) / step - 1e-9);
            double[] d = new double[n + 1];
            for (int j = 0; j < n; j++) {
                d[j] = startYears + j * step;
            }
            d[n] = maturityYears;
            return d;
        }
    }

    /**
     * FX forward: buy {@code foreignNotional} units of foreign currency at
     * {@code strike} domestic per foreign at {@code maturityYears}; a
     * negative notional sells.
     *
     * @param foreignNotional foreign units bought (sold if negative), non-zero
     * @param strike          contract rate, &gt; 0
     * @param maturityYears   settlement time, &gt; 0
     */
    public record FxForwardTerms(double foreignNotional, double strike, double maturityYears) {
        /** Validates the terms. */
        public FxForwardTerms {
            if (!Double.isFinite(foreignNotional) || foreignNotional == 0
                    || !(strike > 0) || strike == Double.POSITIVE_INFINITY
                    || !(maturityYears > 0) || maturityYears == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("need non-zero notional, positive strike and maturity");
            }
        }
    }

    /**
     * Collateral agreement terms (two-way, symmetric).
     *
     * @param threshold        H, &ge; 0
     * @param minimumTransfer  MTA, &ge; 0
     * @param marginPeriodYears MPR in years, &ge; 0 (10 business days is about 10/250)
     */
    public record Csa(double threshold, double minimumTransfer, double marginPeriodYears) {
        /** Validates the terms. */
        public Csa {
            if (!(threshold >= 0) || !(minimumTransfer >= 0) || !(marginPeriodYears >= 0)
                    || !Double.isFinite(threshold + minimumTransfer + marginPeriodYears)) {
                throw new IllegalArgumentException("CSA terms must be >= 0 and finite");
            }
        }

        double target(double v) {
            if (v > threshold) {
                return v - threshold;
            }
            if (v < -threshold) {
                return v + threshold;
            }
            return 0;
        }
    }

    /**
     * A domestic swap with an optional projection curve for its floating leg.
     *
     * @param terms      swap terms
     * @param projection projection curve, or null to project on the discount curve
     */
    public record Swap(SwapTerms terms, Curve projection) {
        /** Validates. */
        public Swap {
            Objects.requireNonNull(terms, "terms");
        }
    }

    /**
     * An FX forward on one pair.
     *
     * @param pair  index into {@link Market#foreign()}
     * @param terms forward terms (notional in that foreign currency)
     */
    public record FxForward(int pair, FxForwardTerms terms) {
        /** Validates. */
        public FxForward {
            Objects.requireNonNull(terms, "terms");
            if (pair < 0) {
                throw new IllegalArgumentException("pair must be >= 0");
            }
        }
    }

    /**
     * A swap in a foreign currency (added in 2.3). Its legs are valued with
     * that currency's Hull-White curve and state, in foreign units, and
     * converted to domestic at the simulated spot of the pair, so the
     * domestic-measure value {@code S_c(t) * V_c(t)} is a martingale after
     * discounting with the domestic bank account (tested). The projection
     * basis, if any, is deterministic against the foreign discount curve.
     *
     * @param pair       index into {@link Market#foreign()}
     * @param terms      swap terms (notional in that foreign currency)
     * @param projection foreign projection curve, or null for the foreign discount curve
     */
    public record ForeignSwap(int pair, SwapTerms terms, Curve projection) {
        /** Validates. */
        public ForeignSwap {
            Objects.requireNonNull(terms, "terms");
            if (pair < 0) {
                throw new IllegalArgumentException("pair must be >= 0");
            }
        }
    }

    /**
     * Stochastic projection-discount basis for domestic swaps that carry a
     * projection curve (added in 2.3). Leaving it out (null) keeps the
     * deterministic basis of 2.2.
     *
     * <p><b>Plain idea.</b> The basis between the projection and the
     * discount curve is not fixed: it widens in stress and drifts back.
     * One mean-reverting factor y_b moves the log basis of every period,
     * and it may be correlated with the rate and FX factors.</p>
     *
     * <p><b>Formal statement.</b> Under the domestic measure
     * {@code dy_b = -a_b y_b dt + sigma_b dW_b}, y_b(0) = 0. For a period
     * (S, T) with today's basis {@code k0 = [P^f(0,S)/P^f(0,T)] / [P_d(0,S)/P_d(0,T)]},
     * the simulated projection ratio is</p>
     *
     * <pre>
     *   X(t) = k0 * m(t) * P_d(t,S) / P_d(t,T),  m(t) = exp(e^{-a_b (S-t)} y_b(t) + D(t)),  t &lt;= S
     *   D(t) = int_0^t [rho_db sigma_b sigma_d e^{-a_b (S-u)} B_d(S-u) - sigma_b^2 e^{-2 a_b (S-u)} / 2] du
     * </pre>
     *
     * <p>so at the fixing the log basis multiplier is y_b(S) + D(S), and
     * before it the factor loads through its own conditional mean
     * {@code e^{-a_b(S-t)} y_b(t)}. D is the drift that makes X a martingale
     * under the T-forward measure, the condition that the forward
     * projection rate be fair (Mercurio 2009, section 2; the drift is own
     * algebra from the Girsanov shift {@code dW^T = dW + sigma_d B_d(T-t) dt}
     * and is checked by a Monte Carlo martingale test). The floating
     * coupon is worth {@code k0 m(t) P_d(t,S) - P_d(t,T)} before the fixing and
     * {@code (k0 m(S) / P_d(S,T) - 1) P_d(t,T)} after. With sigma_b = 0 the
     * factor is not simulated and results equal the deterministic basis
     * path for path (tested). Foreign swaps keep a deterministic basis.</p>
     *
     * <p><b>Worked example.</b> sigma_b = 0.3 %, a_b = 0.5, rho = 0: a
     * period fixing in 5y has log-basis standard deviation
     * {@code 0.003 * sqrt((1 - e^{-5}) / 1)}, about 0.30 % at the fixing
     * (own arithmetic).</p>
     *
     * @param meanReversion a_b, &gt; 0
     * @param vol           sigma_b, &ge; 0
     * @param correlation   correlation of W_b with each market factor
     *                      {@code [W_d, W_1, W_S1, ...]}, length 1 + 2 n
     */
    public record BasisFactor(double meanReversion, double vol, double[] correlation) {
        /** Validates and copies. */
        public BasisFactor {
            checkHw(meanReversion, vol);
            Objects.requireNonNull(correlation, "correlation");
            correlation = correlation.clone();
            for (double r : correlation) {
                if (!(r >= -1) || !(r <= 1)) {
                    throw new IllegalArgumentException("basis correlation must be in [-1, 1]");
                }
            }
        }

        /** Defensive copy. */
        @Override
        public double[] correlation() {
            return correlation.clone();
        }
    }

    /**
     * Simulates a netting set with foreign-currency swaps and an optional
     * stochastic basis (added in 2.3). With no foreign swaps and a null
     * basis it equals
     * {@link #simulate(Market, List, List, Csa, double[], int, long, int)}
     * path for path.
     *
     * @param market         market
     * @param swaps          domestic swaps (may be empty)
     * @param foreignSwaps   foreign-currency swaps (may be empty)
     * @param forwards       FX forwards (may be empty)
     * @param basis          stochastic basis factor, or null for the deterministic basis
     * @param csa            collateral terms, or null
     * @param times          exposure dates, strictly ascending, &gt; 0
     * @param paths          paths, &ge; 2
     * @param seed           seed
     * @param wrongWayFxPair pair whose spot Brownian is the profile's FX driver
     * @return the exposure profile
     */
    public static ExposureProfile simulate(Market market, List<Swap> swaps, List<ForeignSwap> foreignSwaps,
                                           List<FxForward> forwards, BasisFactor basis,
                                           Csa csa, double[] times, int paths, long seed,
                                           int wrongWayFxPair) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(foreignSwaps, "foreignSwaps");
        for (ForeignSwap f : foreignSwaps) {
            if (f.pair() >= market.foreign().size()) {
                throw new IllegalArgumentException("foreign swap on unknown pair " + f.pair());
            }
        }
        if (basis != null) {
            double[][] corr = market.correlation();
            int n = corr.length;
            double[] rb = basis.correlation();
            if (rb.length != n) {
                throw new IllegalArgumentException("basis correlation must have length " + n);
            }
            double[][] ext = new double[n + 1][n + 1];
            for (int i = 0; i < n; i++) {
                System.arraycopy(corr[i], 0, ext[i], 0, n);
                ext[i][n] = rb[i];
                ext[n][i] = rb[i];
            }
            ext[n][n] = 1;
            if (!positiveSemiDefinite(ext)) {
                throw new IllegalArgumentException("correlation with basis factor is not positive semi-definite");
            }
        }
        return simulateChecked(market, swaps, foreignSwaps, forwards, basis, csa, times, paths, seed,
                wrongWayFxPair);
    }

    /**
     * Simulates the netting set with the FX wrong-way driver taken from pair 0.
     *
     * @param market   market
     * @param swaps    swaps (may be empty)
     * @param forwards FX forwards (may be empty)
     * @param csa      collateral terms, or null
     * @param times    exposure dates, strictly ascending, &gt; 0
     * @param paths    paths, &ge; 2
     * @param seed     seed
     * @return the exposure profile
     */
    public static ExposureProfile simulate(Market market, List<Swap> swaps, List<FxForward> forwards,
                                           Csa csa, double[] times, int paths, long seed) {
        return simulate(market, swaps, forwards, csa, times, paths, seed, 0);
    }

    /**
     * Simulates the netting set.
     *
     * @param market         market
     * @param swaps          swaps (may be empty)
     * @param forwards       FX forwards (may be empty)
     * @param csa            collateral terms, or null
     * @param times          exposure dates, strictly ascending, &gt; 0
     * @param paths          paths, &ge; 2
     * @param seed           seed
     * @param wrongWayFxPair pair whose spot Brownian is the profile's FX driver
     *                       (zero driver if no forward trades that pair)
     * @return the exposure profile
     */
    public static ExposureProfile simulate(Market market, List<Swap> swaps, List<FxForward> forwards,
                                           Csa csa, double[] times, int paths, long seed,
                                           int wrongWayFxPair) {
        return simulateChecked(market, swaps, List.of(), forwards, null, csa, times, paths, seed, wrongWayFxPair);
    }

    private static ExposureProfile simulateChecked(Market market, List<Swap> swaps, List<ForeignSwap> foreignSwaps,
                                                   List<FxForward> forwards, BasisFactor basis,
                                                   Csa csa, double[] times, int paths,
                                                   long seed, int wrongWayFxPair) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(swaps, "swaps");
        Objects.requireNonNull(forwards, "forwards");
        Objects.requireNonNull(times, "times");
        if (swaps.isEmpty() && forwards.isEmpty() && foreignSwaps.isEmpty()) {
            throw new IllegalArgumentException("netting set has no trades");
        }
        for (FxForward f : forwards) {
            if (f.pair() >= market.foreign().size()) {
                throw new IllegalArgumentException("forward on unknown pair " + f.pair());
            }
        }
        if (wrongWayFxPair < 0) {
            throw new IllegalArgumentException("wrongWayFxPair must be >= 0");
        }
        if (paths < 2) {
            throw new IllegalArgumentException("need at least 2 paths, got " + paths);
        }
        if (times.length == 0) {
            throw new IllegalArgumentException("need at least one exposure date");
        }
        double prev = 0;
        for (double t : times) {
            if (!(t > prev) || t == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("times must be strictly ascending, positive, finite");
            }
            prev = t;
        }
        return new MultiCurrencyExposureEngine.Engine(market, swaps, foreignSwaps, forwards, basis, csa, times.clone(), wrongWayFxPair)
                .run(paths, seed);
    }

    private static void checkHw(double a, double sigma) {
        if (!(a > 0) || a == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("meanReversion must be positive and finite");
        }
        if (!(sigma >= 0) || sigma == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("rate vol must be >= 0 and finite");
        }
    }

    static boolean positiveSemiDefinite(double[][] c) {
        int n = c.length;
        double[][] l = new double[n][n];
        for (int j = 0; j < n; j++) {
            double d = c[j][j];
            for (int k = 0; k < j; k++) {
                d -= l[j][k] * l[j][k];
            }
            if (d < -1e-10) {
                return false;
            }
            if (d <= 1e-12) {
                for (int i = j + 1; i < n; i++) {
                    double s = c[i][j];
                    for (int k = 0; k < j; k++) {
                        s -= l[i][k] * l[j][k];
                    }
                    if (Math.abs(s) > 1e-8) {
                        return false;
                    }
                }
                continue;
            }
            l[j][j] = Math.sqrt(d);
            for (int i = j + 1; i < n; i++) {
                double s = c[i][j];
                for (int k = 0; k < j; k++) {
                    s -= l[i][k] * l[j][k];
                }
                l[i][j] = s / l[j][j];
            }
        }
        return true;
    }

    /** Cholesky of a positive semi-definite matrix; zero pivots give zero columns. */
    static double[][] cholesky(double[][] c) {
        return MultiCurrencyExposureEngine.cholesky(c);
    }
}

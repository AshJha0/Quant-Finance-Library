package com.quantfinlib.credit;

import com.quantfinlib.rates.DiscountCurve;
import com.quantfinlib.rates.YieldCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * Monte Carlo exposure generator for a netting set of multi-curve swaps
 * and FX forwards on several currency pairs, each foreign currency with
 * its own stochastic short rate. It generalises {@link ExposureSimulator},
 * which stays the default: a market built with
 * {@link Market#fromLegacy(ExposureSimulator.Market)} reproduces the 2.1
 * results to rounding, path for path, from the same seed.
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
 * each currency use the closed form of {@link ExposureSimulator} with that
 * currency's (a, sigma, curve) and the state x_c. The zero-mean Gaussian
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
 * produce the exposure (same convention as {@link ExposureSimulator}).
 * Swaps are domestic only. Research lane.</p>
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

        /**
         * The 2.1 market as a special case: one pair (if the legacy market
         * has a foreign curve) with a deterministic foreign rate, FX-rate
         * correlation as given, foreign mean reversion set to the domestic one
         * (irrelevant at zero foreign vol).
         *
         * @param m legacy market
         * @return the equivalent market
         */
        public static Market fromLegacy(ExposureSimulator.Market m) {
            Objects.requireNonNull(m, "market");
            Domestic d = new Domestic(Curve.of(m.domestic()), m.meanReversion(), m.rateVol());
            if (m.foreign() == null) {
                return new Market(d, List.of(), new double[][]{{1}});
            }
            Foreign f = new Foreign(Curve.of(m.foreign()), m.meanReversion(), 0, m.fxSpot(), m.fxVol());
            double r = m.fxRateCorrelation();
            return new Market(d, List.of(f), new double[][]{{1, 0, r}, {0, 1, 0}, {r, 0, 1}});
        }
    }

    /**
     * A domestic swap with an optional projection curve for its floating leg.
     *
     * @param terms      swap terms
     * @param projection projection curve, or null to project on the discount curve
     */
    public record Swap(ExposureSimulator.Swap terms, Curve projection) {
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
    public record FxForward(int pair, ExposureSimulator.FxForward terms) {
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
    public record ForeignSwap(int pair, ExposureSimulator.Swap terms, Curve projection) {
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
     * {@link #simulate(Market, List, List, ExposureSimulator.Csa, double[], int, long, int)}
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
                                           ExposureSimulator.Csa csa, double[] times, int paths, long seed,
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
                                           ExposureSimulator.Csa csa, double[] times, int paths, long seed) {
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
                                           ExposureSimulator.Csa csa, double[] times, int paths, long seed,
                                           int wrongWayFxPair) {
        return simulateChecked(market, swaps, List.of(), forwards, null, csa, times, paths, seed, wrongWayFxPair);
    }

    private static ExposureProfile simulateChecked(Market market, List<Swap> swaps, List<ForeignSwap> foreignSwaps,
                                                   List<FxForward> forwards, BasisFactor basis,
                                                   ExposureSimulator.Csa csa, double[] times, int paths,
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
        return new Engine(market, swaps, foreignSwaps, forwards, basis, csa, times.clone(), wrongWayFxPair)
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

    /** One Hull-White currency: curve, a, sigma. */
    private record Hw(Curve curve, double a, double sigma) {
        double b(double tau) {
            return -Math.expm1(-a * tau) / a;
        }

        double v(double tau) {
            if (sigma == 0) {
                return 0;
            }
            double b2 = -Math.expm1(-2 * a * tau) / (2 * a);
            return sigma * sigma / (a * a) * (tau - 2 * b(tau) + b2);
        }

        double p0(double t) {
            return t == 0 ? 1 : curve.discountFactor(t);
        }

        double bond(double t, double tMat, double x) {
            if (tMat <= t) {
                return 1;
            }
            double lnA = 0.5 * (v(tMat - t) - v(tMat) + v(t));
            return p0(tMat) / p0(t) * Math.exp(lnA - b(tMat - t) * x);
        }
    }

    /** A Gaussian state component: kernel c0 + c1 e^{-k s} on factor f. */
    private record Kernel(int factor, double c0, double c1, double k) {
    }

    private static double eInt(double k, double dt) {
        return k == 0 ? dt : -Math.expm1(-k * dt) / k;
    }

    private static final class Engine {
        private final Hw dom;
        private final Hw[] fgn;
        private final double[] spot;
        private final double[] fxVol;
        private final double[] quanto;       // rho_{c,Sc} sigma_c sigma_Sc
        private final double[][] corr;
        private final ExposureSimulator.Swap[] swaps;
        private final Curve[] proj;
        private final double[][] swapDates;
        private final double[][] basis;       // k(S,T) per swap period
        private final ExposureSimulator.FxForward[] fwds;
        private final int[] fwdPair;
        private final ExposureSimulator.Csa csa;
        private final double[] times;
        private final double[] grid;
        private final int[] userIndex;
        private final int[] lagIndex;
        // state layout
        private final List<Kernel> kernels = new ArrayList<>();
        private final int[] sIdx;   // per pair, index of W_S, or -1 if not simulated
        private final int[] yIdx;   // per pair, index of y_c (I_c is +1), or -1
        private final int wwPair;
        private final int[] swapCcy;        // -1 domestic, else pair index
        private final boolean[] stochBasis;  // domestic projected swap under the basis factor
        private final int bIdx;             // index of y_b, or -1
        private final double basA;
        private final double basSig;
        private final double basRho;        // corr(W_d, W_b)

        Engine(Market mk, List<Swap> domSw, List<ForeignSwap> fSw, List<FxForward> fw, BasisFactor bf,
               ExposureSimulator.Csa csa, double[] times, int wwPair) {
            Domestic d = mk.domestic();
            dom = new Hw(d.discount(), d.meanReversion(), d.rateVol());
            int n = mk.foreign().size();
            fgn = new Hw[n];
            spot = new double[n];
            fxVol = new double[n];
            quanto = new double[n];
            boolean withBasis = bf != null && bf.vol() > 0;
            double[][] mc = mk.correlation();
            if (withBasis) {
                int nf0 = mc.length;
                double[] rb = bf.correlation();
                corr = new double[nf0 + 1][nf0 + 1];
                for (int i = 0; i < nf0; i++) {
                    System.arraycopy(mc[i], 0, corr[i], 0, nf0);
                    corr[i][nf0] = rb[i];
                    corr[nf0][i] = rb[i];
                }
                corr[nf0][nf0] = 1;
                basA = bf.meanReversion();
                basSig = bf.vol();
                basRho = rb[0];
            } else {
                corr = mc;
                basA = 1;
                basSig = 0;
                basRho = 0;
            }
            List<Swap> sw = new ArrayList<>(domSw);
            int nDom = domSw.size();
            for (ForeignSwap f : fSw) {
                sw.add(new Swap(f.terms(), f.projection()));
            }
            swapCcy = new int[sw.size()];
            stochBasis = new boolean[sw.size()];
            for (int k = 0; k < sw.size(); k++) {
                swapCcy[k] = k < nDom ? -1 : fSw.get(k - nDom).pair();
                stochBasis[k] = withBasis && k < nDom && sw.get(k).projection() != null;
            }
            for (int c = 0; c < n; c++) {
                Foreign f = mk.foreign().get(c);
                fgn[c] = new Hw(f.discount(), f.meanReversion(), f.rateVol());
                spot[c] = f.spot();
                fxVol[c] = f.fxVol();
                quanto[c] = corr[1 + 2 * c][2 + 2 * c] * f.rateVol() * f.fxVol();
            }
            this.csa = csa;
            this.times = times;
            this.wwPair = wwPair;
            swaps = new ExposureSimulator.Swap[sw.size()];
            proj = new Curve[sw.size()];
            swapDates = new double[sw.size()][];
            basis = new double[sw.size()][];
            for (int k = 0; k < swaps.length; k++) {
                swaps[k] = sw.get(k).terms();
                proj[k] = sw.get(k).projection();
                swapDates[k] = swaps[k].dates();
                int np = swapDates[k].length - 1;
                basis[k] = new double[np];
                for (int j = 0; j < np; j++) {
                    if (proj[k] == null) {
                        basis[k][j] = 1;
                    } else {
                        double s = swapDates[k][j], e = swapDates[k][j + 1];
                        double pfs = s == 0 ? 1 : proj[k].discountFactor(s);
                        double pfe = proj[k].discountFactor(e);
                        Hw h = hw(k);
                        basis[k][j] = (pfs / pfe) / (h.p0(s) / h.p0(e));
                    }
                }
            }
            fwds = new ExposureSimulator.FxForward[fw.size()];
            fwdPair = new int[fw.size()];
            boolean[] used = new boolean[n];
            for (int i = 0; i < fwds.length; i++) {
                fwds[i] = fw.get(i).terms();
                fwdPair[i] = fw.get(i).pair();
                used[fwdPair[i]] = true;
            }
            for (int k = 0; k < swapCcy.length; k++) {
                if (swapCcy[k] >= 0) {
                    used[swapCcy[k]] = true;
                }
            }
            // Domestic y, I, W first (the 2.1 draw order), then per used pair W_S, y_c, I_c.
            double a = dom.a(), s = dom.sigma();
            kernels.add(new Kernel(0, 0, s, a));
            kernels.add(new Kernel(0, s / a, -s / a, a));
            kernels.add(new Kernel(0, 1, 0, 0));
            sIdx = new int[n];
            yIdx = new int[n];
            Arrays.fill(sIdx, -1);
            Arrays.fill(yIdx, -1);
            for (int c = 0; c < n; c++) {
                if (!used[c]) {
                    continue;
                }
                sIdx[c] = kernels.size();
                kernels.add(new Kernel(2 + 2 * c, 1, 0, 0));
                if (fgn[c].sigma() > 0) {
                    double ac = fgn[c].a(), sc = fgn[c].sigma();
                    yIdx[c] = kernels.size();
                    kernels.add(new Kernel(1 + 2 * c, 0, sc, ac));
                    kernels.add(new Kernel(1 + 2 * c, sc / ac, -sc / ac, ac));
                }
            }
            if (withBasis) {
                int fb = corr.length - 1;
                bIdx = kernels.size();
                kernels.add(new Kernel(fb, 0, basSig, basA));
                kernels.add(new Kernel(fb, basSig / basA, -basSig / basA, basA));
            } else {
                bIdx = -1;
            }

            TreeSet<Double> set = new TreeSet<>();
            set.add(0.0);
            for (double t : times) {
                set.add(t);
            }
            for (double[] dd : swapDates) {
                for (int j = 0; j < dd.length - 1; j++) {
                    if (dd[j] <= times[times.length - 1]) {
                        set.add(dd[j]);
                    }
                }
            }
            double[] lag = new double[times.length];
            for (int i = 0; i < times.length; i++) {
                lag[i] = csa == null ? times[i] : Math.max(times[i] - csa.marginPeriodYears(), 0);
                set.add(lag[i]);
            }
            grid = new double[set.size()];
            int g = 0;
            for (double t : set) {
                grid[g++] = t;
            }
            userIndex = new int[times.length];
            lagIndex = new int[times.length];
            for (int i = 0; i < times.length; i++) {
                userIndex[i] = Arrays.binarySearch(grid, times[i]);
                lagIndex[i] = Arrays.binarySearch(grid, lag[i]);
            }
        }

        double[][] stepCov(double dt) {
            int n = kernels.size();
            double[][] cov = new double[n][n];
            for (int i = 0; i < n; i++) {
                Kernel p = kernels.get(i);
                for (int j = 0; j <= i; j++) {
                    Kernel q = kernels.get(j);
                    double r = corr[p.factor()][q.factor()];
                    double v = p.c0() * q.c0() * dt
                            + p.c0() * q.c1() * eInt(q.k(), dt)
                            + p.c1() * q.c0() * eInt(p.k(), dt)
                            + p.c1() * q.c1() * eInt(p.k() + q.k(), dt);
                    cov[i][j] = r * v;
                    cov[j][i] = r * v;
                }
            }
            return cov;
        }

        /** True foreign state x_c(t) and its integral, adding the quanto mean. */
        double fgnX(int c, double t, double y) {
            return y - quanto[c] * fgn[c].b(t);
        }

        double fgnIntX(int c, double t, double iy) {
            return iy - quanto[c] * (t - fgn[c].b(t)) / fgn[c].a();
        }

        Hw hw(int k) {
            return swapCcy[k] < 0 ? dom : fgn[swapCcy[k]];
        }

        /**
         * Log basis multiplier ln m(t) for a period fixing at s, with y = y_b(t), t &lt;= s.
         */
        double logBasis(double t, double s, double y) {
            double w1 = s - t, ad = dom.a();
            double f1 = fInt(basA, w1, s), f2 = fInt(basA + ad, w1, s), f3 = fInt(2 * basA, w1, s);
            double drift = basRho * basSig * dom.sigma() / ad * (f1 - f2) - 0.5 * basSig * basSig * f3;
            return Math.exp(-basA * w1) * y + drift;
        }

        private static double fInt(double k, double w1, double w2) {
            return (Math.exp(-k * w1) - Math.exp(-k * w2)) / k;
        }

        double value(double t, double xd, double[] fx, double[] xf, double[][] resetBond, double yb) {
            double v = 0;
            for (int k = 0; k < swaps.length; k++) {
                ExposureSimulator.Swap s = swaps[k];
                double[] d = swapDates[k];
                int n = d.length - 1;
                if (t >= d[n]) {
                    continue;
                }
                int ccy = swapCcy[k];
                Hw h = hw(k);
                double x = ccy < 0 ? xd : xf[ccy];
                double fixed = 0, flt = 0;
                for (int j = 1; j <= n; j++) {
                    if (d[j] <= t) {
                        continue;
                    }
                    double pe = h.bond(t, d[j], x);
                    fixed += (d[j] - d[j - 1]) * pe;
                    double kb = basis[k][j - 1];
                    if (d[j - 1] >= t) {
                        double mb = stochBasis[k] ? Math.exp(logBasis(t, d[j - 1], yb)) : 1;
                        flt += kb * mb * h.bond(t, d[j - 1], x) - pe;
                    } else {
                        // resetBond holds P(S,T) / m(S) for stochastic-basis periods
                        flt += (kb / resetBond[k][j - 1] - 1) * pe;
                    }
                }
                double pv = s.notional() * (flt - s.fixedRate() * fixed);
                if (ccy >= 0) {
                    pv *= fx[ccy];
                }
                v += s.payFixed() ? pv : -pv;
            }
            for (int i = 0; i < fwds.length; i++) {
                ExposureSimulator.FxForward f = fwds[i];
                if (t >= f.maturityYears()) {
                    continue;
                }
                int c = fwdPair[i];
                double pfT = fgn[c].bond(t, f.maturityYears(), xf[c]);
                v += f.foreignNotional() * (fx[c] * pfT - f.strike() * dom.bond(t, f.maturityYears(), xd));
            }
            return v;
        }

        ExposureProfile run(int paths, long seed) {
            int m = times.length;
            int ng = grid.length;
            int nk = kernels.size();
            int nf = fgn.length;
            double[][][] chol = new double[ng][][];
            for (int g = 1; g < ng; g++) {
                chol[g] = cholesky(stepCov(grid[g] - grid[g - 1]));
            }
            // per-step decay / B for each OU component (y at index i, I at i + 1)
            double[][] decay = new double[ng][nk];
            double[][] bStep = new double[ng][nk];
            for (int g = 1; g < ng; g++) {
                double dt = grid[g] - grid[g - 1];
                for (int i = 0; i < nk; i++) {
                    double k = kernels.get(i).k();
                    decay[g][i] = Math.exp(-k * dt);
                    bStep[g][i] = eInt(k, dt);
                }
            }
            boolean[] isY = new boolean[nk];
            isY[0] = true;
            for (int c = 0; c < nf; c++) {
                if (yIdx[c] >= 0) {
                    isY[yIdx[c]] = true;
                }
            }
            if (bIdx >= 0) {
                isY[bIdx] = true;
            }

            double[][] discPos = new double[paths][m];
            double[][] discNeg = new double[paths][m];
            double[][] expo = new double[paths][m];
            double[][] driverRates = new double[paths][m];
            double[][] driverFx = new double[paths][m];
            double[] wrOnGrid = new double[ng];
            double[] wfxOnGrid = new double[ng];
            int wwIdx = wwPair < nf ? sIdx[wwPair] : -1;

            double[] fx0 = spot.clone();
            double[] xf = new double[nf];
            double v0 = value(0, 0, fx0, xf, initialResets(), 0);
            SplittableRandom rng = new SplittableRandom(seed);
            double[] vOnGrid = new double[ng];
            double[] dOnGrid = new double[ng];
            double[][] resets = new double[swaps.length][];
            for (int k = 0; k < swaps.length; k++) {
                resets[k] = new double[swapDates[k].length - 1];
            }
            int[][] resetAt = resetLookup();
            boolean[] needV = new boolean[ng];
            for (int i = 0; i < m; i++) {
                needV[userIndex[i]] = true;
                needV[lagIndex[i]] = true;
            }
            double[] st = new double[nk];
            double[] z = new double[nk];
            double[] fx = new double[nf];
            for (int p = 0; p < paths; p++) {
                Arrays.fill(st, 0);
                vOnGrid[0] = v0;
                dOnGrid[0] = 1;
                Arrays.fill(xf, 0);
                fillResets(resets, resetAt, 0, 0, xf, 0);
                for (int g = 1; g < ng; g++) {
                    for (int i = 0; i < nk; i++) {
                        z[i] = rng.nextGaussian();
                    }
                    double[][] c = chol[g];
                    for (int i = 0; i < nk; i++) {
                        if (isY[i]) {
                            // integral first (uses the old y), then y
                            double e1 = 0, e2 = 0;
                            for (int j = 0; j <= i; j++) {
                                e1 += c[i][j] * z[j];
                            }
                            for (int j = 0; j <= i + 1; j++) {
                                e2 += c[i + 1][j] * z[j];
                            }
                            st[i + 1] += st[i] * bStep[g][i] + e2;
                            st[i] = st[i] * decay[g][i] + e1;
                            i++;
                        } else {
                            double e = 0;
                            for (int j = 0; j <= i; j++) {
                                e += c[i][j] * z[j];
                            }
                            st[i] += e;
                        }
                    }
                    double t = grid[g];
                    wrOnGrid[g] = st[2];
                    wfxOnGrid[g] = wwIdx >= 0 ? st[wwIdx] : 0;
                    double disc = dom.p0(t) * Math.exp(-0.5 * dom.v(t) - st[1]);
                    dOnGrid[g] = disc;
                    double xd = st[0];
                    double yb = bIdx >= 0 ? st[bIdx] : 0;
                    for (int cc = 0; cc < nf; cc++) {
                        xf[cc] = yIdx[cc] >= 0 ? fgnX(cc, t, st[yIdx[cc]]) : 0;
                    }
                    fillResets(resets, resetAt, g, xd, xf, yb);
                    if (needV[g]) {
                        for (int cc = 0; cc < nf; cc++) {
                            if (sIdx[cc] < 0) {
                                continue;
                            }
                            double iy = yIdx[cc] >= 0 ? st[yIdx[cc] + 1] : 0;
                            Hw h = fgn[cc];
                            double ix = yIdx[cc] >= 0 ? fgnIntX(cc, t, iy) : 0;
                            double accF = h.p0(t) * Math.exp(-0.5 * h.v(t) - ix);
                            double sv = fxVol[cc];
                            fx[cc] = spot[cc] * accF / disc * Math.exp(-0.5 * sv * sv * t + sv * st[sIdx[cc]]);
                        }
                        vOnGrid[g] = value(t, xd, fx, xf, resets, yb);
                    }
                }
                double balance = 0;
                if (csa != null) {
                    double tgt = csa.target(v0);
                    balance = Math.abs(tgt) >= csa.minimumTransfer() ? tgt : 0;
                }
                for (int i = 0; i < m; i++) {
                    if (csa != null) {
                        double tgt = csa.target(vOnGrid[lagIndex[i]]);
                        if (Math.abs(tgt - balance) >= csa.minimumTransfer()) {
                            balance = tgt;
                        }
                    }
                    double net = vOnGrid[userIndex[i]] - balance;
                    double d = dOnGrid[userIndex[i]];
                    expo[p][i] = Math.max(net, 0);
                    discPos[p][i] = d * Math.max(net, 0);
                    discNeg[p][i] = d * Math.max(-net, 0);
                    int u = userIndex[i];
                    int l = csa == null || lagIndex[i] == u ? 0 : lagIndex[i];
                    double norm = 1 / Math.sqrt(times[i] - grid[l]);
                    driverRates[p][i] = (wrOnGrid[u] - wrOnGrid[l]) * norm;
                    driverFx[p][i] = wwIdx >= 0 ? (wfxOnGrid[u] - wfxOnGrid[l]) * norm : 0;
                }
            }
            double[] p0s = new double[m];
            for (int i = 0; i < m; i++) {
                p0s[i] = dom.p0(times[i]);
            }
            return new ExposureProfile(times, p0s, discPos, discNeg, expo, driverRates, driverFx);
        }

        private double[][] initialResets() {
            double[][] r = new double[swaps.length][];
            for (int k = 0; k < swaps.length; k++) {
                r[k] = new double[swapDates[k].length - 1];
                Arrays.fill(r[k], 1);
            }
            return r;
        }

        private int[][] resetLookup() {
            List<List<Integer>> lists = new ArrayList<>();
            for (int g = 0; g < grid.length; g++) {
                lists.add(new ArrayList<>());
            }
            for (int k = 0; k < swaps.length; k++) {
                for (int j = 0; j < swapDates[k].length - 1; j++) {
                    int g = Arrays.binarySearch(grid, swapDates[k][j]);
                    if (g < 0) {
                        continue;
                    }
                    lists.get(g).add(k);
                    lists.get(g).add(j);
                }
            }
            int[][] out = new int[grid.length][];
            for (int g = 0; g < grid.length; g++) {
                List<Integer> l = lists.get(g);
                out[g] = new int[l.size()];
                for (int i = 0; i < l.size(); i++) {
                    out[g][i] = l.get(i);
                }
            }
            return out;
        }

        private void fillResets(double[][] resets, int[][] resetAt, int g, double x, double[] xf, double yb) {
            int[] r = resetAt[g];
            for (int i = 0; i < r.length; i += 2) {
                int k = r[i], j = r[i + 1];
                double xs = swapCcy[k] < 0 ? x : xf[swapCcy[k]];
                double pb = hw(k).bond(grid[g], swapDates[k][j + 1], xs);
                if (stochBasis[k]) {
                    pb /= Math.exp(logBasis(grid[g], grid[g], yb));
                }
                resets[k][j] = pb;
            }
        }
    }

    /** Cholesky of a positive semi-definite matrix; zero pivots give zero columns. */
    static double[][] cholesky(double[][] c) {
        int n = c.length;
        double[][] l = new double[n][n];
        for (int j = 0; j < n; j++) {
            double d = c[j][j];
            for (int k = 0; k < j; k++) {
                d -= l[j][k] * l[j][k];
            }
            double scale = Math.max(Math.abs(c[j][j]), 1e-300);
            if (d <= 1e-13 * scale) {
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
        return l;
    }
}

package com.quantfinlib.microstructure;

import com.quantfinlib.util.MathUtils;

/**
 * Self-exciting (Hawkes) event intensity — the model behind the trader's
 * observation that "activity breeds activity": one trade raises the
 * probability of the next, so order flow arrives in bursts, not as a
 * steady Poisson drizzle. The exponential Hawkes form keeps the whole
 * process in two numbers:
 *
 * <pre>  λ(t) = μ + S(t),   S(t) = Σ over past events of α·e^(−β(t−tᵢ))</pre>
 *
 * where μ is the baseline arrival rate, α the excitation each event adds,
 * and β the decay. {@code S} updates in O(1) per event — decay what is
 * there, add α — which is what makes Hawkes streaming-friendly while
 * richer clustering models are not.
 *
 * <p><b>Stability is enforced, not assumed:</b> the branching ratio
 * {@code α/β} is the expected number of children each event spawns; at
 * ≥ 1 the process is explosive (each event begets more than one, and the
 * intensity diverges), so the constructor rejects it. Read
 * {@link #burstScore} as the regime signal: 0 = baseline flow, 1 = the
 * self-excited component equals the baseline (activity running 2×), and
 * it decays back with the configured half-life when the burst ends.</p>
 *
 * <p>Uses: pre-positioning for activity bursts (a burst means queues
 * consume faster — {@code QueueModel} horizons shorten), conflation
 * pressure forecasting, and as a dimensionless ensemble input. Feed every
 * arrival of the event type you care about (trades, quotes, or your own
 * fills) via {@link #onEvent}; timestamps must be non-decreasing (a
 * backwards timestamp is ignored — feed-merge jitter must not inject
 * negative decay). Zero allocation per event, single writer, one
 * instance per symbol per event type.</p>
 *
 * <p><b>Calibration.</b> {@link #fit} estimates μ, α and β from event
 * timestamps by maximum likelihood, using the O(n) recursion for the
 * exponential kernel (Ozaki 1979, Ann. Inst. Statist. Math. 31(1)):</p>
 * <pre>
 *   ℓ = Σᵢ log(μ + α Aᵢ) − μT − (α/β) Σᵢ (1 − e^{−β(T − tᵢ)}),
 *   A₁ = 0,  Aᵢ = e^{−β(tᵢ − tᵢ₋₁)} (1 + Aᵢ₋₁)
 * </pre>
 * <p>maximised by Nelder-Mead over {@code (log μ, log α, log β)} with the
 * stationarity constraint {@code α/β < 1} enforced. Here α is the jump in
 * intensity per event (events per second) and β the decay rate per second,
 * so the half-life passed to the constructor is {@code ln 2 / β}.</p>
 */
public final class HawkesIntensity {

    private final double baselineRatePerSec;
    private final double excitation;       // α: intensity added per event (per sec)
    private final long decayHalfLifeNanos;

    private double excited;                // S(t) as of lastEventNanos
    private long lastEventNanos;
    private boolean hasEvent;
    private long events;

    /**
     * @param baselineRatePerSec  μ — arrival rate with no excitation, e.g. 2.0
     * @param excitation          α — intensity each event adds (per second),
     *                            e.g. 0.1
     * @param decayHalfLifeNanos  how fast excitation fades, e.g. 2s. Stability
     *                            requires branching ratio α/β &lt; 1 where
     *                            β = ln2/halfLife (per second): at a 2s
     *                            half-life, β ≈ 0.35/s, so α must stay
     *                            below ~0.35 — rejected otherwise.
     */
    public HawkesIntensity(double baselineRatePerSec, double excitation,
                           long decayHalfLifeNanos) {
        if (baselineRatePerSec <= 0 || excitation < 0 || decayHalfLifeNanos <= 0) {
            throw new IllegalArgumentException(
                    "need baselineRate > 0, excitation >= 0, halfLife > 0");
        }
        double beta = Math.log(2) / (decayHalfLifeNanos * 1e-9);
        if (excitation / beta >= 1) {
            throw new IllegalArgumentException("explosive: branching ratio "
                    + (excitation / beta) + " >= 1 (each event spawns >= 1 child)");
        }
        this.baselineRatePerSec = baselineRatePerSec;
        this.excitation = excitation;
        this.decayHalfLifeNanos = decayHalfLifeNanos;
    }

    /** Baseline 2 events/s, excitation 0.1/s per event, 2s half-life (branching ~0.29). */
    public HawkesIntensity() {
        this(2.0, 0.1, 2_000_000_000L);
    }

    /**
     * One event arrival. Timestamps must be non-decreasing; an
     * out-of-order timestamp is dropped (negative decay would GROW past
     * excitation instead of fading it).
     */
    public void onEvent(long timestampNanos) {
        if (hasEvent) {
            long dt = timestampNanos - lastEventNanos;
            if (dt < 0) {
                return;
            }
            excited = excited * MathUtils.decayFactor(dt, decayHalfLifeNanos) + excitation;
        } else {
            excited = excitation;
            hasEvent = true;
        }
        lastEventNanos = timestampNanos;
        events++;
    }

    /** The current intensity λ(now) in events per second. */
    public double intensity(long nowNanos) {
        return baselineRatePerSec + excitedAt(nowNanos);
    }

    /**
     * The dimensionless burst regime: self-excited intensity over the
     * baseline, clamped to [0, 1] at "activity running 2× baseline".
     * 0 in steady flow; decays back with the configured half-life.
     */
    public double burstScore(long nowNanos) {
        return MathUtils.clamp(excitedAt(nowNanos) / baselineRatePerSec, 0, 1);
    }

    private double excitedAt(long nowNanos) {
        if (!hasEvent) {
            return 0;
        }
        long dt = nowNanos - lastEventNanos;
        return dt <= 0 ? excited : excited * MathUtils.decayFactor(dt, decayHalfLifeNanos);
    }

    public long events() {
        return events;
    }

    /**
     * A maximum-likelihood calibration.
     *
     * @param baselineRatePerSec μ
     * @param excitation         α, intensity jump per event (per second)
     * @param decayPerSec        β
     * @param logLikelihood      ℓ at the optimum
     * @param converged          simplex converged within the iteration cap
     */
    public record Fit(double baselineRatePerSec, double excitation, double decayPerSec,
                      double logLikelihood, int events, boolean converged) {

        /** α/β: expected direct children per event. */
        public double branchingRatio() {
            return excitation / decayPerSec;
        }

        /** Excitation half-life {@code ln 2 / β} in nanoseconds. */
        public long halfLifeNanos() {
            return Math.max(1, Math.round(Math.log(2) / decayPerSec * 1e9));
        }

        /** A streaming model with the fitted parameters. */
        public HawkesIntensity toModel() {
            return new HawkesIntensity(baselineRatePerSec, excitation, halfLifeNanos());
        }
    }

    /**
     * Maximum-likelihood fit of an exponential-kernel Hawkes process to
     * event times observed over {@code [startNanos, endNanos]}.
     *
     * @param timestampsNanos non-decreasing event times inside the window
     * @throws IllegalArgumentException with fewer than 10 events or a
     *                                  malformed window
     */
    public static Fit fit(long[] timestampsNanos, long startNanos, long endNanos) {
        int n = timestampsNanos.length;
        if (n < 10 || endNanos <= startNanos) {
            throw new IllegalArgumentException("need >= 10 events and endNanos > startNanos");
        }
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            if (timestampsNanos[i] < startNanos || timestampsNanos[i] > endNanos
                    || (i > 0 && timestampsNanos[i] < timestampsNanos[i - 1])) {
                throw new IllegalArgumentException("timestamps must be sorted and inside the window");
            }
            t[i] = (timestampsNanos[i] - startNanos) * 1e-9;
        }
        double horizon = (endNanos - startNanos) * 1e-9;
        double rate = n / horizon;
        double beta0 = Math.max(1e-6, 1 / Math.max(1e-9, horizon / n));
        double[][] simplex = {
                {Math.log(0.5 * rate), Math.log(0.4 * beta0), Math.log(beta0)},
                {Math.log(0.8 * rate), Math.log(0.2 * beta0), Math.log(beta0)},
                {Math.log(0.5 * rate), Math.log(0.2 * beta0), Math.log(2 * beta0)},
                {Math.log(0.3 * rate), Math.log(0.5 * beta0), Math.log(0.7 * beta0)},
        };
        double[] f = new double[4];
        for (int k = 0; k < 4; k++) {
            f[k] = -logLikelihood(t, horizon, simplex[k]);
        }
        boolean converged = false;
        for (int it = 0; it < 2_000; it++) {
            // order: best first
            for (int a = 0; a < 4; a++) {
                for (int b = a + 1; b < 4; b++) {
                    if (f[b] < f[a]) {
                        double tf = f[a];
                        f[a] = f[b];
                        f[b] = tf;
                        double[] tp = simplex[a];
                        simplex[a] = simplex[b];
                        simplex[b] = tp;
                    }
                }
            }
            if (Math.abs(f[3] - f[0]) <= 1e-10 * (1 + Math.abs(f[0]))) {
                converged = true;
                break;
            }
            double[] c = new double[3];
            for (int k = 0; k < 3; k++) {
                for (int d = 0; d < 3; d++) {
                    c[d] += simplex[k][d] / 3;
                }
            }
            double[] xr = step(c, simplex[3], -1);
            double fr = -logLikelihood(t, horizon, xr);
            if (fr < f[0]) {
                double[] xe = step(c, simplex[3], -2);
                double fe = -logLikelihood(t, horizon, xe);
                if (fe < fr) {
                    simplex[3] = xe;
                    f[3] = fe;
                } else {
                    simplex[3] = xr;
                    f[3] = fr;
                }
            } else if (fr < f[2]) {
                simplex[3] = xr;
                f[3] = fr;
            } else {
                double[] xc = step(c, simplex[3], 0.5);
                double fc = -logLikelihood(t, horizon, xc);
                if (fc < f[3]) {
                    simplex[3] = xc;
                    f[3] = fc;
                } else {
                    for (int k = 1; k < 4; k++) {
                        for (int d = 0; d < 3; d++) {
                            simplex[k][d] = simplex[0][d] + 0.5 * (simplex[k][d] - simplex[0][d]);
                        }
                        f[k] = -logLikelihood(t, horizon, simplex[k]);
                    }
                }
            }
        }
        int best = 0;
        for (int k = 1; k < 4; k++) {
            if (f[k] < f[best]) {
                best = k;
            }
        }
        double[] x = simplex[best];
        return new Fit(Math.exp(x[0]), Math.exp(x[1]), Math.exp(x[2]), -f[best], n, converged);
    }

    /** c + coef·(worst − c): coef −1 reflects, −2 expands, 0.5 contracts. */
    private static double[] step(double[] c, double[] worst, double coef) {
        double[] x = new double[3];
        for (int d = 0; d < 3; d++) {
            x[d] = c[d] + coef * (worst[d] - c[d]);
        }
        return x;
    }

    /** Ozaki log-likelihood at log-parameters; −∞ outside the stationary region. */
    static double logLikelihood(double[] t, double horizon, double[] logParams) {
        double mu = Math.exp(logParams[0]);
        double alpha = Math.exp(logParams[1]);
        double beta = Math.exp(logParams[2]);
        if (!(alpha < beta) || !Double.isFinite(mu) || !Double.isFinite(beta)) {
            return Double.NEGATIVE_INFINITY;
        }
        double ll = 0;
        double a = 0;
        double comp = 0;
        for (int i = 0; i < t.length; i++) {
            if (i > 0) {
                a = Math.exp(-beta * (t[i] - t[i - 1])) * (1 + a);
            }
            ll += Math.log(mu + alpha * a);
            comp += 1 - Math.exp(-beta * (horizon - t[i]));
        }
        return ll - mu * horizon - alpha / beta * comp;
    }
}

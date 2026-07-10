package com.quantfinlib.microstructure;

/**
 * TIME-VARYING regression by Kalman filter — the pairs desk's upgrade
 * over a static OLS hedge ratio. The state {@code [α, β]} follows a
 * random walk (relationships DRIFT: index compositions change, business
 * mixes shift, a hedge ratio fitted on last year is stale by spring),
 * and each observation {@code y = α + β·x + ε} nudges the estimate by
 * exactly as much as its information content warrants:
 *
 * <pre>  state:       [α, β]_t = [α, β]_{t-1} + noise(q)
 *  observation:  y_t = α_t + β_t·x_t + noise(r)</pre>
 *
 * The knobs mean something: {@code processNoise} (q) is how fast you
 * believe the relationship drifts — 0 collapses to recursive least
 * squares (β converges and freezes), large q chases every tick;
 * {@code observationNoise} (r) is how noisy each print is. Their RATIO
 * is what matters. {@link #betaVariance()} is the filter's own
 * uncertainty — a hedge sized off a β the filter itself distrusts is a
 * position, not a hedge.
 *
 * <p>Pairs workflow: {@code hedging.CointegrationTest} (is there a
 * relationship?) → this class (what is the ratio NOW?) →
 * {@link OrnsteinUhlenbeck} on the resulting spread (how fast does it
 * revert?) → {@code execution.SpreadExecutionAlgo} (execute with the
 * legging cap). O(1) per observation, allocation-free after
 * construction, deterministic; research/warm lane.</p>
 */
public final class KalmanBeta {

    private final double q;
    private final double r;
    private double alpha;
    private double beta;
    // 2x2 state covariance [pAA pAB; pAB pBB].
    private double pAA;
    private double pAB;
    private double pBB;
    private long observations;

    /**
     * @param initialBeta      starting hedge ratio (an OLS fit is a fine seed)
     * @param initialVariance  how much to distrust the seed, &gt; 0
     * @param processNoise     per-step state drift variance q, ≥ 0
     *                         (0 = the relationship never drifts: RLS)
     * @param observationNoise per-observation noise variance r, &gt; 0
     */
    public KalmanBeta(double initialBeta, double initialVariance,
                      double processNoise, double observationNoise) {
        if (!Double.isFinite(initialBeta)) {
            throw new IllegalArgumentException("initialBeta must be finite");
        }
        if (!(initialVariance > 0) || initialVariance == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("initialVariance must be positive and finite");
        }
        if (!(processNoise >= 0) || processNoise == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("processNoise must be >= 0 and finite");
        }
        if (!(observationNoise > 0) || observationNoise == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("observationNoise must be positive and finite");
        }
        this.beta = initialBeta;
        this.pAA = initialVariance;
        this.pBB = initialVariance;
        this.q = processNoise;
        this.r = observationNoise;
    }

    /**
     * One observation pair: {@code y ≈ α + β·x}.
     *
     * @return the innovation (observation minus prediction) — the
     *         filter's own surprise, useful as a spread signal
     */
    public double onObservation(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("observations must be finite");
        }
        // Predict: random-walk state, covariance grows by q.
        pAA += q;
        pBB += q;
        // Innovation and its variance: S = H P H' + r with H = [1, x].
        double innovation = y - (alpha + beta * x);
        double s = pAA + 2 * x * pAB + x * x * pBB + r;
        // Kalman gain K = P H' / S.
        double kA = (pAA + x * pAB) / s;
        double kB = (pAB + x * pBB) / s;
        // Update state and covariance (Joseph-free simple form; the
        // 2x2 stays symmetric PSD because s >= r > 0).
        alpha += kA * innovation;
        beta += kB * innovation;
        double hA = pAA + x * pAB;      // (H P)' components
        double hB = pAB + x * pBB;
        pAA -= kA * hA;
        pAB -= kA * hB;
        pBB -= kB * hB;
        observations++;
        return innovation;
    }

    /** The current intercept estimate. */
    public double alpha() {
        return alpha;
    }

    /** The current hedge ratio estimate. */
    public double beta() {
        return beta;
    }

    /** The filter's own uncertainty about β — size hedges accordingly. */
    public double betaVariance() {
        return pBB;
    }

    public long observations() {
        return observations;
    }
}

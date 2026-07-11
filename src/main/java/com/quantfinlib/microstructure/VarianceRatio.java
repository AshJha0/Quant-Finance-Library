package com.quantfinlib.microstructure;

import com.quantfinlib.util.MathUtils;

/**
 * The Lo-MacKinlay VARIANCE RATIO test — the question that comes before
 * every strategy choice: is this series trending, mean-reverting, or a
 * random walk? Under a random walk, variance grows LINEARLY with
 * horizon, so the ratio
 *
 * <pre>  VR(q) = Var(q-period returns) / (q · Var(1-period returns))</pre>
 *
 * is 1. Positive return autocorrelation (momentum) compounds —
 * VR &gt; 1; negative autocorrelation (mean reversion) cancels —
 * VR &lt; 1. The z-statistic says whether the deviation is signal or
 * sampling noise.
 *
 * <p>This is the natural companion to {@link OrnsteinUhlenbeck}: OU
 * REFUSES a series with no mean reversion, and the variance ratio
 * tells you what the series is INSTEAD (VR ≈ 1: don't trade the
 * spread, index it; VR &gt; 1: you're holding a momentum name, trade it
 * that way). Overlapping q-period sums with the SIMPLIFIED denominator
 * — Lo-MacKinlay's small-sample unbiased correction is omitted (bias
 * ~(q−1)/n, negligible for n ≫ q; the length gate enforces n ≥ 10q) —
 * and the homoskedastic z-statistic; the heteroskedasticity-robust
 * variant is likewise omitted. Stated, not hidden. VR(1) is
 * identically 1. Static, deterministic, research lane.</p>
 */
public final class VarianceRatio {

    /**
     * @param ratio VR(q): 1 = random walk, &gt; 1 trending, &lt; 1 reverting
     * @param zStat homoskedastic z; |z| &gt; ~2 rejects the random walk
     */
    public record Result(double ratio, double zStat) {

        /** |z| ≥ 2: the deviation from a random walk is not noise. */
        public boolean rejectsRandomWalk() {
            return Math.abs(zStat) >= 2;
        }
    }

    private VarianceRatio() {
    }

    /**
     * @param returns 1-period returns, ≥ 10·q finite observations
     * @param q       aggregation horizon, ≥ 2 (VR(1) ≡ 1 needs no test)
     */
    public static Result test(double[] returns, int q) {
        if (q < 2) {
            throw new IllegalArgumentException("q must be >= 2 (VR(1) is identically 1)");
        }
        int n = returns.length;
        if (n < 10 * q) {
            throw new IllegalArgumentException("need >= " + (10 * q)
                    + " observations for VR(" + q + "), got " + n);
        }
        for (double r : returns) {
            if (!Double.isFinite(r)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
        double mu = MathUtils.mean(returns);
        // 1-period variance (sample).
        double var1 = 0;
        for (double r : returns) {
            var1 += (r - mu) * (r - mu);
        }
        var1 /= (n - 1);
        if (!(var1 > 0)) {
            throw new IllegalArgumentException("returns carry no variance");
        }
        // Overlapping q-period sums around q·mu.
        double varQ = 0;
        int m = n - q + 1;
        double window = 0;
        for (int i = 0; i < q; i++) {
            window += returns[i];
        }
        for (int t = 0; ; t++) {
            double d = window - q * mu;
            varQ += d * d;
            if (t + q >= n) {
                break;
            }
            window += returns[t + q] - returns[t];
        }
        varQ /= (m - 1);

        double vr = varQ / (q * var1);
        // Homoskedastic asymptotic: (VR−1)·sqrt(n) ~ N(0, 2(2q−1)(q−1)/(3q)).
        double asymptoticVar = 2.0 * (2 * q - 1) * (q - 1) / (3.0 * q);
        double z = (vr - 1) * Math.sqrt(n / asymptoticVar);
        return new Result(vr, z);
    }
}

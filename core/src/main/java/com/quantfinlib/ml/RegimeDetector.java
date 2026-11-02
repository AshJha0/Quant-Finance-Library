package com.quantfinlib.ml;

import com.quantfinlib.stats.Stats;
import com.quantfinlib.internal.ValueArrays;

import java.util.SplittableRandom;

/**
 * Two-state Gaussian Markov-switching model (hidden Markov model) fitted by
 * Baum-Welch EM with scaled forward-backward recursions (Rabiner 1989,
 * Proc. IEEE 77, 257-286; Hamilton 1989, Econometrica 57, 357-384).
 * Detects calm/turbulent regimes in a return series. State 1 is always the
 * <b>high-volatility</b> regime.
 *
 * <p><b>Filtered versus smoothed.</b> The model reports two probability
 * paths. {@code filteredHighVolProbability[t] = P(high vol at t | r_0..r_t)}
 * uses only data up to t and is the one a strategy may act on at t (after
 * observing r_t). {@code smoothedHighVolProbability[t] = P(high vol at t |
 * r_0..r_{n−1})} uses the whole sample, including data after t; it is for
 * labelling regimes after the fact. De-levering on the smoothed path in a
 * backtest is look-ahead. Note that the parameters themselves are fitted on
 * the whole sample, so even the filtered path is only out-of-sample when the
 * model was fitted on an earlier window.</p>
 *
 * <p>EM is local: {@link #fit(double[], int)} runs from the median-split
 * start plus four seeded random starts and keeps the highest likelihood.
 * Emission densities are handled in log space with a per-period shift, so an
 * extreme return cannot underflow both densities to zero. Inputs must be
 * finite.</p>
 */
public final class RegimeDetector {

    public static final class RegimeModel {
        private final double[] means;
        private final double[] stdDevs;
        private final double[][] transition;
        private final double logLikelihood;
        private final double[] smoothedHighVolProbability;
        private final double[] filteredHighVolProbability;
        private final double[] currentProbabilities;
        private final int currentRegime;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param means per-state mean return
         * @param stdDevs per-state volatility (state 1 = high vol)
         * @param transition transition[i][j] = P(next=j | now=i)
         * @param smoothedHighVolProbability P(high vol at t | all data): ex post only
         * @param filteredHighVolProbability P(high vol at t | data up to t): causal
         * @param currentProbabilities filtered P(state | data up to T)
         */
        public RegimeModel(double[] means, double[] stdDevs, double[][] transition, double logLikelihood,
                double[] smoothedHighVolProbability, double[] filteredHighVolProbability,
                double[] currentProbabilities, int currentRegime) {
            this.means = means == null ? null : means.clone();
            this.stdDevs = stdDevs == null ? null : stdDevs.clone();
            this.transition = ValueArrays.copy(transition);
            this.logLikelihood = logLikelihood;
            this.smoothedHighVolProbability = smoothedHighVolProbability == null ? null : smoothedHighVolProbability.clone();
            this.filteredHighVolProbability = filteredHighVolProbability == null ? null : filteredHighVolProbability.clone();
            this.currentProbabilities = currentProbabilities == null ? null : currentProbabilities.clone();
            this.currentRegime = currentRegime;
        }

        /** Element {@code i} of {@code means} (per-state mean return). */
        public double means(int i) {
            return means[i];
        }

        /** Length of {@code means}. */
        public int meansLength() {
            return means.length;
        }

        /** A fresh copy of {@code means}. */
        public double[] meansCopy() {
            return means == null ? null : means.clone();
        }

        /** Element {@code i} of {@code stdDevs} (per-state volatility (state 1 = high vol)). */
        public double stdDevs(int i) {
            return stdDevs[i];
        }

        /** Length of {@code stdDevs}. */
        public int stdDevsLength() {
            return stdDevs.length;
        }

        /** A fresh copy of {@code stdDevs}. */
        public double[] stdDevsCopy() {
            return stdDevs == null ? null : stdDevs.clone();
        }

        /** Element {@code [i][j]} of {@code transition} (transition[i][j] = P(next=j | now=i)). */
        public double transition(int i, int j) {
            return transition[i][j];
        }

        /** Number of rows of {@code transition}. */
        public int transitionRows() {
            return transition.length;
        }

        /** A deep copy of {@code transition}. */
        public double[][] transitionCopy() {
            return ValueArrays.copy(transition);
        }

        /** {@code logLikelihood}. */
        public double logLikelihood() {
            return logLikelihood;
        }

        /** Element {@code i} of {@code smoothedHighVolProbability} (P(high vol at t | all data): ex post only). */
        public double smoothedHighVolProbability(int i) {
            return smoothedHighVolProbability[i];
        }

        /** Length of {@code smoothedHighVolProbability}. */
        public int smoothedHighVolProbabilityLength() {
            return smoothedHighVolProbability.length;
        }

        /** A fresh copy of {@code smoothedHighVolProbability}. */
        public double[] smoothedHighVolProbabilityCopy() {
            return smoothedHighVolProbability == null ? null : smoothedHighVolProbability.clone();
        }

        /** Element {@code i} of {@code filteredHighVolProbability} (P(high vol at t | data up to t): causal). */
        public double filteredHighVolProbability(int i) {
            return filteredHighVolProbability[i];
        }

        /** Length of {@code filteredHighVolProbability}. */
        public int filteredHighVolProbabilityLength() {
            return filteredHighVolProbability.length;
        }

        /** A fresh copy of {@code filteredHighVolProbability}. */
        public double[] filteredHighVolProbabilityCopy() {
            return filteredHighVolProbability == null ? null : filteredHighVolProbability.clone();
        }

        /** Element {@code i} of {@code currentProbabilities} (filtered P(state | data up to T)). */
        public double currentProbabilities(int i) {
            return currentProbabilities[i];
        }

        /** Length of {@code currentProbabilities}. */
        public int currentProbabilitiesLength() {
            return currentProbabilities.length;
        }

        /** A fresh copy of {@code currentProbabilities}. */
        public double[] currentProbabilitiesCopy() {
            return currentProbabilities == null ? null : currentProbabilities.clone();
        }

        /** {@code currentRegime}. */
        public int currentRegime() {
            return currentRegime;
        }

                 // argmax of currentProbabilities

        /** Expected persistence of a regime in periods: 1 / (1 - p_stay). */
        public double expectedDuration(int state) {
            return 1 / (1 - transition[state][state]);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegimeModel o)) {
                return false;
            }
            return java.util.Arrays.equals(means, o.means)
                    && java.util.Arrays.equals(stdDevs, o.stdDevs)
                    && java.util.Arrays.deepEquals(transition, o.transition)
                    && Double.compare(logLikelihood, o.logLikelihood) == 0
                    && java.util.Arrays.equals(smoothedHighVolProbability, o.smoothedHighVolProbability)
                    && java.util.Arrays.equals(filteredHighVolProbability, o.filteredHighVolProbability)
                    && java.util.Arrays.equals(currentProbabilities, o.currentProbabilities)
                    && currentRegime == o.currentRegime;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(means);
            h = 31 * h + java.util.Arrays.hashCode(stdDevs);
            h = 31 * h + java.util.Arrays.deepHashCode(transition);
            h = 31 * h + Double.hashCode(logLikelihood);
            h = 31 * h + java.util.Arrays.hashCode(smoothedHighVolProbability);
            h = 31 * h + java.util.Arrays.hashCode(filteredHighVolProbability);
            h = 31 * h + java.util.Arrays.hashCode(currentProbabilities);
            h = 31 * h + Integer.hashCode(currentRegime);
            return h;
        }

        @Override
        public String toString() {
            return "RegimeModel[" + "means=" + java.util.Arrays.toString(means) + ", " + "stdDevs=" + java.util.Arrays.toString(stdDevs) + ", " + "transition=" + java.util.Arrays.deepToString(transition) + ", " + "logLikelihood=" + logLikelihood + ", " + "smoothedHighVolProbability=" + java.util.Arrays.toString(smoothedHighVolProbability) + ", " + "filteredHighVolProbability=" + java.util.Arrays.toString(filteredHighVolProbability) + ", " + "currentProbabilities=" + java.util.Arrays.toString(currentProbabilities) + ", " + "currentRegime=" + currentRegime + "]";
        }
    }

    private RegimeDetector() {
    }

    /** Fits with five starts (median split plus four seeded random starts). */
    public static RegimeModel fit(double[] returns, int maxIterations) {
        return fit(returns, maxIterations, 5, 1L);
    }

    /**
     * Fits the regime model by EM from several starts.
     *
     * @param maxIterations EM iterations per start (0 evaluates the start)
     * @param starts        number of starting points, &ge; 1; the first is
     *                      always the median split, the rest are random
     * @param seed          seed for the random starts
     */
    public static RegimeModel fit(double[] returns, int maxIterations, int starts, long seed) {
        int n = returns.length;
        if (n < 100) {
            throw new IllegalArgumentException("need at least 100 returns, got " + n);
        }
        if (starts < 1 || maxIterations < 0) {
            throw new IllegalArgumentException("need starts >= 1 and maxIterations >= 0");
        }
        for (double r : returns) {
            if (!Double.isFinite(r)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
        double[] abs = new double[n];
        for (int t = 0; t < n; t++) {
            abs[t] = Math.abs(returns[t]);
        }
        double median = Stats.quantile(abs, 0.5);
        double mean = Stats.mean(returns);
        double sampleVar = Math.max(Stats.sampleVariance(returns), 1e-14);
        double lowVar = 0, highVar = 0;
        int lowCount = 0, highCount = 0;
        for (int t = 0; t < n; t++) {
            double d = returns[t] - mean;
            if (abs[t] <= median) {
                lowVar += d * d;
                lowCount++;
            } else {
                highVar += d * d;
                highCount++;
            }
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        RegimeModel best = null;
        for (int s = 0; s < starts; s++) {
            double[] mu;
            double[] variance;
            if (s == 0) {
                mu = new double[]{mean, mean};
                variance = new double[]{Math.max(lowVar / Math.max(1, lowCount), 1e-12),
                        Math.max(highVar / Math.max(1, highCount), 1e-12)};
            } else {
                double sd = Math.sqrt(sampleVar);
                mu = new double[]{mean + 0.1 * sd * rnd.nextGaussian(),
                        mean + 0.1 * sd * rnd.nextGaussian()};
                variance = new double[]{sampleVar * (0.1 + 0.8 * rnd.nextDouble()),
                        sampleVar * (1.0 + 4.0 * rnd.nextDouble())};
            }
            RegimeModel m = em(returns, mu, variance, maxIterations);
            if (best == null || m.logLikelihood() > best.logLikelihood()) {
                best = m;
            }
        }
        return best;
    }

    private static RegimeModel em(double[] returns, double[] mu, double[] variance,
                                  int maxIterations) {
        int n = returns.length;
        double[][] a = {{0.95, 0.05}, {0.05, 0.95}};
        double[] pi = {0.5, 0.5};

        double[][] alpha = new double[n][2];
        double[][] beta = new double[n][2];
        double[][] gamma = new double[n][2];
        double[][] b = new double[n][2];          // emission densities, shifted per t
        double[] scale = new double[n];
        double logLikelihood = Double.NEGATIVE_INFINITY;

        // The loop always ends on an E-step, so the returned probabilities
        // and log-likelihood are computed under the returned parameters.
        for (int iter = 0; iter <= maxIterations; iter++) {
            double shiftSum = 0;
            for (int t = 0; t < n; t++) {
                double l0 = logDensity(returns[t], mu[0], variance[0]);
                double l1 = logDensity(returns[t], mu[1], variance[1]);
                double mx = Math.max(l0, l1);
                b[t][0] = Math.exp(l0 - mx);
                b[t][1] = Math.exp(l1 - mx);
                shiftSum += mx;
            }
            double ll = shiftSum;
            for (int t = 0; t < n; t++) {
                double sum = 0;
                for (int j = 0; j < 2; j++) {
                    double prior = t == 0
                            ? pi[j]
                            : alpha[t - 1][0] * a[0][j] + alpha[t - 1][1] * a[1][j];
                    alpha[t][j] = prior * b[t][j];
                    sum += alpha[t][j];
                }
                if (!(sum > 0) || !Double.isFinite(sum)) {
                    throw new IllegalStateException("forward recursion degenerated at t=" + t);
                }
                scale[t] = sum;
                alpha[t][0] /= sum;
                alpha[t][1] /= sum;
                ll += Math.log(sum);
            }
            beta[n - 1][0] = 1;
            beta[n - 1][1] = 1;
            for (int t = n - 2; t >= 0; t--) {
                for (int i = 0; i < 2; i++) {
                    double sum = 0;
                    for (int j = 0; j < 2; j++) {
                        sum += a[i][j] * b[t + 1][j] * beta[t + 1][j];
                    }
                    beta[t][i] = sum / scale[t + 1];
                }
            }
            double[][] xiSum = new double[2][2];
            for (int t = 0; t < n; t++) {
                double norm = alpha[t][0] * beta[t][0] + alpha[t][1] * beta[t][1];
                gamma[t][0] = alpha[t][0] * beta[t][0] / norm;
                gamma[t][1] = 1 - gamma[t][0];
                if (t < n - 1) {
                    double denom = 0;
                    double[][] xi = new double[2][2];
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            xi[i][j] = alpha[t][i] * a[i][j] * b[t + 1][j] * beta[t + 1][j];
                            denom += xi[i][j];
                        }
                    }
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            xiSum[i][j] += xi[i][j] / denom;
                        }
                    }
                }
            }
            if (!Double.isFinite(ll)) {
                throw new IllegalStateException("log-likelihood is not finite");
            }
            boolean lastPass = iter == maxIterations
                    || Math.abs(ll - logLikelihood) < 1e-9 * Math.max(1, Math.abs(ll));
            logLikelihood = ll;
            if (lastPass) {
                break;
            }
            pi[0] = gamma[0][0];
            pi[1] = gamma[0][1];
            for (int i = 0; i < 2; i++) {
                double rowSum = xiSum[i][0] + xiSum[i][1];
                if (rowSum > 0) {
                    a[i][0] = xiSum[i][0] / rowSum;
                    a[i][1] = xiSum[i][1] / rowSum;
                }
            }
            for (int j = 0; j < 2; j++) {
                double weight = 0, weightedSum = 0;
                for (int t = 0; t < n; t++) {
                    weight += gamma[t][j];
                    weightedSum += gamma[t][j] * returns[t];
                }
                if (!(weight > 1e-9)) {
                    continue;              // an empty state keeps its parameters
                }
                mu[j] = weightedSum / weight;
                double varSum = 0;
                for (int t = 0; t < n; t++) {
                    double d = returns[t] - mu[j];
                    varSum += gamma[t][j] * d * d;
                }
                variance[j] = Math.max(varSum / weight, 1e-14);
            }
        }

        // Canonical ordering: state 1 = high volatility.
        if (variance[0] > variance[1]) {
            swap(mu);
            swap(variance);
            double tmp = a[0][0]; a[0][0] = a[1][1]; a[1][1] = tmp;
            tmp = a[0][1]; a[0][1] = a[1][0]; a[1][0] = tmp;
            for (int t = 0; t < n; t++) {
                double g = gamma[t][0]; gamma[t][0] = gamma[t][1]; gamma[t][1] = g;
                double al = alpha[t][0]; alpha[t][0] = alpha[t][1]; alpha[t][1] = al;
            }
        }
        double[] smoothed = new double[n];
        double[] filtered = new double[n];
        for (int t = 0; t < n; t++) {
            smoothed[t] = gamma[t][1];
            filtered[t] = alpha[t][1];
        }
        double[] current = {alpha[n - 1][0], alpha[n - 1][1]};
        return new RegimeModel(mu.clone(),
                new double[]{Math.sqrt(variance[0]), Math.sqrt(variance[1])},
                a, logLikelihood, smoothed, filtered, current, current[1] > current[0] ? 1 : 0);
    }

    /**
     * Most likely state sequence (Viterbi, in log space) under a fitted
     * model; uses the whole sample, so it is an ex-post labelling.
     */
    public static int[] viterbi(double[] returns, RegimeModel model) {
        int n = returns.length;
        if (n < 1) {
            throw new IllegalArgumentException("empty series");
        }
        double[][] la = new double[2][2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                la[i][j] = Math.log(model.transition(i, j));
            }
        }
        double[] v = new double[2];
        int[][] back = new int[n][2];
        for (int j = 0; j < 2; j++) {
            v[j] = Math.log(0.5) + logDensity(returns[0], model.means(j),
                    model.stdDevs(j) * model.stdDevs(j));
        }
        for (int t = 1; t < n; t++) {
            double[] next = new double[2];
            for (int j = 0; j < 2; j++) {
                double s0 = v[0] + la[0][j];
                double s1 = v[1] + la[1][j];
                back[t][j] = s1 > s0 ? 1 : 0;
                next[j] = Math.max(s0, s1) + logDensity(returns[t], model.means(j),
                        model.stdDevs(j) * model.stdDevs(j));
            }
            v = next;
        }
        int[] path = new int[n];
        path[n - 1] = v[1] > v[0] ? 1 : 0;
        for (int t = n - 1; t > 0; t--) {
            path[t - 1] = back[t][path[t]];
        }
        return path;
    }

    private static double logDensity(double x, double mean, double variance) {
        double d = x - mean;
        return -0.5 * (d * d / variance + Math.log(2 * Math.PI * variance));
    }

    private static void swap(double[] pair) {
        double tmp = pair[0];
        pair[0] = pair[1];
        pair[1] = tmp;
    }
}

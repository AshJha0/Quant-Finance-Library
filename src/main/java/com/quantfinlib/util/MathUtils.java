package com.quantfinlib.util;

import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.stats.Numerics;
import com.quantfinlib.stats.SpecialFunctions;
import com.quantfinlib.stats.Stats;

/**
 * Numerical primitives from 1.x and 2.x. Every method now delegates to a class in
 * {@code com.quantfinlib.stats}; results are unchanged.
 *
 * @deprecated since 2.4.0, removed in 3.0 (no alias). Use {@link Stats},
 *     {@link SpecialFunctions}, {@link LinearAlgebra} and {@link Numerics}; each
 *     method below names its exact replacement, and docs/MIGRATION_3.0.md has the table.
 */
@Deprecated(since = "2.4.0", forRemoval = true)
public final class MathUtils {

    private MathUtils() {
    }

    /**
     * Same as {@link Stats#mean(double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#mean(double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double mean(double[] v) {
        return Stats.mean(v);
    }

    /**
     * Same as {@link Stats#mean(double[],int,int)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#mean(double[],int,int)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double mean(double[] v, int from, int to) {
        return Stats.mean(v, from, to);
    }

    /**
     * Same as {@link Stats#sampleVariance(double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#sampleVariance(double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double variance(double[] v) {
        return Stats.sampleVariance(v);
    }

    /**
     * Same as {@link Stats#sampleStdDev(double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#sampleStdDev(double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double stdDev(double[] v) {
        return Stats.sampleStdDev(v);
    }

    /**
     * Same as {@link Stats#populationStdDev(double[],int,int)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#populationStdDev(double[],int,int)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double stdDevP(double[] v, int from, int to) {
        return Stats.populationStdDev(v, from, to);
    }

    /**
     * Same as {@link Stats#sampleStdDev(double[],int,int)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#sampleStdDev(double[],int,int)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double stdDevSample(double[] v, int from, int to) {
        return Stats.sampleStdDev(v, from, to);
    }

    /**
     * Same as {@link Stats#quantile(double[],double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#quantile(double[],double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double percentile(double[] values, double p) {
        return Stats.quantile(values, p);
    }

    /**
     * Same as {@link Stats#quantileSorted(double[],double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#quantileSorted(double[],double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double percentileSorted(double[] sorted, double p) {
        return Stats.quantileSorted(sorted, p);
    }

    /**
     * Same as {@link LinearAlgebra#dot(double[],double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link LinearAlgebra#dot(double[],double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double dot(double[] a, double[] b) {
        return LinearAlgebra.dot(a, b);
    }

    /**
     * Same as {@link LinearAlgebra#matVec(double[][],double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link LinearAlgebra#matVec(double[][],double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double[] matVec(double[][] m, double[] v) {
        return LinearAlgebra.matVec(m, v);
    }

    /**
     * Same as {@link LinearAlgebra#quadraticForm(double[],double[][])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link LinearAlgebra#quadraticForm(double[],double[][])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double quadraticForm(double[] w, double[][] m) {
        return LinearAlgebra.quadraticForm(w, m);
    }

    /**
     * Same as {@link Stats#sampleCovariance(double[],double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#sampleCovariance(double[],double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double covariance(double[] a, double[] b) {
        return Stats.sampleCovariance(a, b);
    }

    /**
     * Same as {@link Stats#pearsonOrZero(double[],double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#pearsonOrZero(double[],double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double correlation(double[] a, double[] b) {
        return Stats.pearsonOrZero(a, b);
    }

    /**
     * Same as {@link LinearAlgebra#cholesky(double[][])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link LinearAlgebra#cholesky(double[][])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double[][] cholesky(double[][] a) {
        return LinearAlgebra.cholesky(a);
    }

    /**
     * Same as {@link SpecialFunctions#normInv(double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#normInv(double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double normInv(double p) {
        return SpecialFunctions.normInv(p);
    }

    /**
     * Same as {@link Stats#skewness(double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#skewness(double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double skewness(double[] v) {
        return Stats.skewness(v);
    }

    /**
     * Same as {@link Stats#kurtosis(double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Stats#kurtosis(double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double kurtosis(double[] v) {
        return Stats.kurtosis(v);
    }

    /**
     * Same as {@link Numerics#pairSort(double[],int[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Numerics#pairSort(double[],int[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static void pairSort(double[] keys, int[] values) {
        Numerics.pairSort(keys, values);
    }

    /**
     * Same as {@link SpecialFunctions#logNormPdf(double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#logNormPdf(double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double logNormPdf(double x) {
        return SpecialFunctions.logNormPdf(x);
    }

    /**
     * Same as {@link SpecialFunctions#normPdf(double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#normPdf(double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double normPdf(double x) {
        return SpecialFunctions.normPdf(x);
    }

    /**
     * Same as {@link SpecialFunctions#normCdf(double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#normCdf(double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double normCdf(double x) {
        return SpecialFunctions.normCdf(x);
    }

    /**
     * Same as {@link LinearAlgebra#solveLinear(double[][],double[])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link LinearAlgebra#solveLinear(double[][],double[])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double[] solveLinear(double[][] a, double[] b) {
        return LinearAlgebra.solveLinear(a, b);
    }

    /**
     * Same as {@link LinearAlgebra#inverse(double[][])}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link LinearAlgebra#inverse(double[][])}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double[][] inverse(double[][] a) {
        return LinearAlgebra.inverse(a);
    }

    /**
     * Same as {@link Numerics#clamp(double,double,double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Numerics#clamp(double,double,double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double clamp(double v, double lo, double hi) {
        return Numerics.clamp(v, lo, hi);
    }

    /**
     * Same as {@link Numerics#nanArray(int)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Numerics#nanArray(int)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double[] nanArray(int n) {
        return Numerics.nanArray(n);
    }

    /**
     * Same as {@link Numerics#decayFactor(long,long)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link Numerics#decayFactor(long,long)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double decayFactor(long dtNanos, long halfLifeNanos) {
        return Numerics.decayFactor(dtNanos, halfLifeNanos);
    }

    /**
     * Same as {@link SpecialFunctions#logGamma(double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#logGamma(double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double logGamma(double x) {
        return SpecialFunctions.logGamma(x);
    }

    /**
     * Same as {@link SpecialFunctions#regularizedIncompleteBeta(double,double,double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#regularizedIncompleteBeta(double,double,double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double regularizedIncompleteBeta(double a, double b, double x) {
        return SpecialFunctions.regularizedIncompleteBeta(a, b, x);
    }

    /**
     * Same as {@link SpecialFunctions#tCdf(double,double)}.
     *
     * @deprecated since 2.4.0, removed in 3.0; use {@link SpecialFunctions#tCdf(double,double)}.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static double tCdf(double t, double df) {
        return SpecialFunctions.tCdf(t, df);
    }
}

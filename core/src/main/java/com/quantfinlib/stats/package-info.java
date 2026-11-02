/**
 * Canonical descriptive statistics: mean, sample and population variance,
 * quantiles with a named Hyndman-Fan definition, median, covariance,
 * Pearson correlation, skewness, kurtosis and midranks
 * ({@link com.quantfinlib.stats.Stats}); and, since 2.4.0, the numerical
 * routines that used to live in {@code util.MathUtils} (removed in 3.0):
 * {@link com.quantfinlib.stats.SpecialFunctions} (normal, log-gamma,
 * incomplete beta, Student-t), {@link com.quantfinlib.stats.LinearAlgebra}
 * (dot products, Cholesky, solve, inverse) and
 * {@link com.quantfinlib.stats.Numerics} (clamp, NaN arrays, decay factor,
 * pair sort).
 *
 * <p>This is a package of its own rather than more methods on
 * {@code util.MathUtils} so that these definitions sit in one small,
 * documented surface: every estimator here states its denominator, its
 * quantile type and its edge-case behaviour, and the older helpers elsewhere
 * in the library ({@code risk.Dependence.ranks} and the private copies in {@code alpha},
 * {@code ml}, {@code credit} and {@code regulatory}) delegate here. It
 * depends on nothing but {@code java.base}, so any package may use it.</p>
 */
@NullMarked
package com.quantfinlib.stats;

import org.jspecify.annotations.NullMarked;

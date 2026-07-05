package com.quantfinlib.alpha;

/**
 * A cross-sectional alpha factor: at a bar index, one raw score per symbol,
 * where <b>higher = more attractive to own</b> (buy high scores, sell low).
 *
 * <p>Scores are <em>raw</em>, in whatever natural unit the factor has
 * (return spread, z-score, yield). Normalization into portfolio weights is
 * deliberately a separate step ({@link PortfolioConstruction}) so that the
 * same factor can be evaluated rank-wise ({@link SignalEvaluator} uses rank
 * IC, which is scale-invariant) and constructed under different schemes
 * without re-implementing the signal.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>the returned array aligns with {@link AlphaContext#symbols()};</li>
 *   <li>{@code NaN} means "no score" (insufficient history, missing
 *       fundamentals) — every downstream step skips NaN entries;</li>
 *   <li>implementations must only read bars {@code <= index}: a factor that
 *       peeks forward invalidates every evaluation built on it. The
 *       validation suite cannot detect look-ahead mechanically — this
 *       contract is the guard.</li>
 * </ul>
 */
@FunctionalInterface
public interface AlphaFactor {

    /** Raw scores at {@code index}, aligned with the context's symbols. */
    double[] scores(AlphaContext ctx, int index);

    /** Human-readable name used in reports; override for real factors. */
    default String name() {
        return getClass().getSimpleName();
    }
}

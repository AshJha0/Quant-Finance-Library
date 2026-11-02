/**
 * A rule-based strategy builder. Compose {@link com.quantfinlib.dsl.Rule}s
 * (built via
 * {@link com.quantfinlib.dsl.Rules} factories over indicator arrays, with
 * and/or/not combinators) into a backtestable strategy through
 * {@link com.quantfinlib.dsl.StrategyBuilder} — entry/exit rules, stop loss
 * and take profit in a fluent chain. All rules are NaN-safe during indicator
 * warm-up.
 *
 * <p><b>Since 4.0.</b> This package is in the incubating artifact {@code
 * quant-finance-library-research} (module {@code com.quantfinlib.research});
 * its name did not change. See docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.dsl;

import org.jspecify.annotations.NullMarked;

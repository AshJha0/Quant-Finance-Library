/**
 * Strategy Builder DSL: compose {@link com.quantfinlib.dsl.Rule}s (built via
 * {@link com.quantfinlib.dsl.Rules} factories over indicator arrays, with
 * and/or/not combinators) into a backtestable strategy through
 * {@link com.quantfinlib.dsl.StrategyBuilder} — entry/exit rules, stop loss
 * and take profit in a fluent chain. All rules are NaN-safe during indicator
 * warm-up.
 */
package com.quantfinlib.dsl;

# How to use this guide

[Index: LEARN.md](../LEARN.md) | [Previous: Part IV, Knowledge checks: Q1001 to Q1058](15-checks-q1001-q1058.md) | [Next: Appendix, the formula reference](17-formula-reference.md)

---

## How to use this guide

Parts I to III are meant to be read in order. Part IV is different: it is
a knowledge check, organised by topic, with a worked answer for each
question and the class that implements the idea. Do not memorise the
answers. For each topic you want to understand properly, do three things:

1. **Run the class's test.** Every class cited above is covered by tests
   under `src/test/java/com/quantfinlib/`, usually `<Class>Test.java` and
   sometimes a themed suite (`ComponentVar` is tested in
   `RiskAllocationTest`). Run it (`./mvnw test -Dtest=RiskAllocationTest`)
   and read what it asserts: the assertions are the claims in the worked
   answer, pinned to numbers.
2. **Read the javadoc, then the code.** The javadoc headers in this library
   are written as explanations with references, not API listings, and most
   worked answers are condensed from them. Most classes are small enough
   to read in one sitting.
3. **Re-derive one number.** Pick a single assertion (the Feller ratio, a
   component-VaR share, the deflated Sharpe of a grid winner, the
   Acerbi-Tasche ES of losses 1 to 1000) and reproduce it by hand or in a
   scratch `main`. Re-deriving a number is the quickest way to find out
   whether you understand the convention behind it.

If a term is unfamiliar, Parts I to III teach it from zero.
[COOKBOOK.md](../COOKBOOK.md) has runnable recipes for the workflows the
questions come from, [MARKET_RISK.md](../MARKET_RISK.md) covers the risk
stack in depth, and [ULTRA_LOW_LATENCY.md](../ULTRA_LOW_LATENCY.md) covers
the low-latency topics in more depth than Part II.

---

[Index: LEARN.md](../LEARN.md) | [Previous: Part IV, Knowledge checks: Q1001 to Q1058](15-checks-q1001-q1058.md) | [Next: Appendix, the formula reference](17-formula-reference.md)

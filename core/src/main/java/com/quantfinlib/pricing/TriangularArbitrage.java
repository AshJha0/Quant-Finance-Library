package com.quantfinlib.pricing;

/**
 * Triangular arbitrage checks over three related FX pairs, using dealable
 * bid/ask quotes (not mids), so a positive result is executable edge before
 * fees.
 *
 * <p>Conventions: {@code ab} is the price of one unit of A in B, {@code bc}
 * of one B in C, {@code ac} of one A in C — e.g. A=EUR, B=USD, C=JPY:
 * ab=EURUSD, bc=USDJPY, ac=EURJPY.</p>
 */
public final class TriangularArbitrage {

    public record Quote(double bid, double ask) {

        public Quote {
            if (ask < bid) {
                throw new IllegalArgumentException("crossed quote: bid " + bid + " > ask " + ask);
            }
        }

        public double mid() {
            return (bid + ask) / 2;
        }
    }

    private TriangularArbitrage() {
    }

    /**
     * Best executable round-trip edge in basis points (positive = arbitrage):
     * <ul>
     *   <li>Path 1 — buy A synthetically via B ({@code ab.ask * bc.ask}) and
     *       sell it directly at {@code ac.bid}.</li>
     *   <li>Path 2 — buy A directly at {@code ac.ask} and sell it via B at
     *       {@code ab.bid * bc.bid}.</li>
     * </ul>
     */
    public static double arbitrageBps(Quote ab, Quote bc, Quote ac) {
        double syntheticAsk = ab.ask() * bc.ask();     // cost in C to build one A via B
        double syntheticBid = ab.bid() * bc.bid();     // proceeds in C unwinding one A via B
        double path1 = (ac.bid() - syntheticAsk) / syntheticAsk;
        double path2 = (syntheticBid - ac.ask()) / ac.ask();
        return Math.max(path1, path2) * 1e4;
    }

    /** True when the executable edge exceeds {@code thresholdBps} (e.g. costs). */
    public static boolean exists(Quote ab, Quote bc, Quote ac, double thresholdBps) {
        return arbitrageBps(ab, bc, ac) > thresholdBps;
    }

    /** The no-arbitrage cross rate implied by the two leg mids. */
    public static double impliedCrossMid(Quote ab, Quote bc) {
        return ab.mid() * bc.mid();
    }
}

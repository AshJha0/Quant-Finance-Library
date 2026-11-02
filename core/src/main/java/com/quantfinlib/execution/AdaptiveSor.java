package com.quantfinlib.execution;

import com.quantfinlib.execution.SmartOrderRouter.RouteLeg;
import com.quantfinlib.microstructure.QueueModel;
import com.quantfinlib.core.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The full-checklist smart order router: where {@link SmartOrderRouter}
 * ranks venues purely on fee-adjusted displayed price and {@link HftSor}
 * does the same at tick-path speed, this router prices in everything a
 * production SOR actually weighs:
 *
 * <ul>
 *   <li><b>Displayed liquidity</b> and <b>fees/rebates</b> — the all-in
 *       price, as before;</li>
 *   <li><b>Probability of fill / venue reliability</b> — a venue's
 *       {@link VenueScorecard} fill rate discounts its quote: expected
 *       cost adds {@code (1 − fillRate) × missPenalty} (the spread-ish
 *       cost of re-routing a faded child), and venues below a reliability
 *       floor are vetoed outright;</li>
 *   <li><b>Latency</b> — slower venues pay {@code latency × urgency}: in a
 *       moving market, microseconds of delay are adverse selection. The
 *       scorecard's <em>measured</em> latency overrides the advertised
 *       {@link VenueQuote#latencyNanos} once observed;</li>
 *   <li><b>Adverse selection</b> — a venue whose fills are followed by
 *       reversion ({@link VenueScorecard#postFillMarkout} negative) charges
 *       that reversion as a per-share cost: two identical quotes are not
 *       equal when one venue's fills systematically fade;</li>
 *   <li><b>Hidden liquidity</b> — dark pools are probed with sizes learned
 *       from realized probe fills ({@link VenueScorecard#onDarkProbe}),
 *       seeded by a configurable default when a pool is still unknown;</li>
 *   <li><b>Queue position</b> — for the passive leg of a child (posting
 *       rather than taking), {@link #passiveFillProbability} delegates to
 *       {@link QueueModel} so placement decisions can weigh the queue they
 *       would join.</li>
 * </ul>
 *
 * <p>The plan for the worked example (Exchange A: 10,000 shares at 120µs;
 * Exchange B: 8,000 at 80µs, same price; Dark Pool: unknown) is exactly
 * the classic answer: B's 8,000 first (equal price, lower latency), the
 * remaining 2,000 to A, and a simultaneous dark probe. Probe legs are
 * <b>additive and contingent</b> ({@link RoutingDecision#probes}): the
 * executor sends them alongside the lit legs and, on dark fills, cancels
 * lit remainder. The router plans; the executor manages overfill.</p>
 *
 * <p><b>Cross-asset</b>: prices are raw doubles, so the same router serves
 * equities (exchanges + dark pools) and FX (ECN/matching venues + mid-match
 * pools) — an FX "dark" venue is a mid-match session, and "fill rate" on an
 * FX venue is one minus its last-look reject rate. For routing over
 * per-LP tiered quote streams (the bank-stream side of FX), use
 * {@code fx.LpRouter}, which prices last-look rejects and hold time
 * natively from {@code fx.LpScorecard}.</p>
 *
 * <p>Research/decision lane (allocates the plan); the decision runs
 * once per parent slice, not per tick (timings in docs/BENCHMARKS.md). Venue identity
 * is by name, mapped once to dense scorecard ids at registration.</p>
 */
public final class AdaptiveSor {

    /** Tunable penalties; {@link #defaults()} is a sane starting point. */
    public record Config(double missPenaltyBps, double urgencyBpsPerMs,
                         double minFillRate, long defaultDarkProbeShares,
                         double maxDarkFraction) {

        public Config {
            if (missPenaltyBps < 0 || urgencyBpsPerMs < 0
                    || minFillRate < 0 || minFillRate > 1
                    || defaultDarkProbeShares < 0
                    || maxDarkFraction < 0 || maxDarkFraction > 1) {
                throw new IllegalArgumentException("invalid router config");
            }
        }

        /**
         * missPenalty 2 bps (≈ re-cross half a spread), urgency 1 bp/ms of
         * latency, veto below 50% fill rate, 5,000-share default dark
         * probe, dark capped at half the parent.
         */
        public static Config defaults() {
            return new Config(2.0, 1.0, 0.5, 5_000, 0.5);
        }
    }

    /**
     * The routed plan: {@code lit} legs cover up to the requested quantity
     * (best-expected-cost first); {@code probes} are additive contingent
     * dark legs sent alongside; {@code unrouted} is the shortfall no
     * eligible lit venue could absorb (0 on a fully routed order).
     */
    public record RoutingDecision(List<RouteLeg> lit, List<RouteLeg> probes,
                                  long routedQty, long unrouted) {
        public RoutingDecision {
            lit = List.copyOf(lit);
            probes = List.copyOf(probes);
        }
    }

    private final VenueScorecard scorecard;
    private final Config config;
    private final Map<String, Integer> venueIds = new HashMap<>();
    private double maxLatencyPenaltyBps = Double.POSITIVE_INFINITY;

    public AdaptiveSor(VenueScorecard scorecard, Config config) {
        this.scorecard = scorecard;
        this.config = config;
    }

    public AdaptiveSor(VenueScorecard scorecard) {
        this(scorecard, Config.defaults());
    }

    /**
     * Maps a venue name to its scorecard index. Register every venue once
     * at setup; quotes from unregistered venues are routed on quote data
     * alone (prior fill rate, advertised latency).
     */
    public void register(String venue, int scorecardId) {
        if (scorecardId < 0 || scorecardId >= scorecard.venueCount()) {
            throw new IllegalArgumentException("scorecardId out of range: " + scorecardId);
        }
        venueIds.put(venue, scorecardId);
    }

    /**
     * Caps the latency term of the expected cost at {@code bps} of price
     * (default: no cap). Without a cap a venue with a pathological measured
     * latency is pushed arbitrarily far down the ranking; with one it is
     * charged at most this much and other terms still order it.
     */
    public AdaptiveSor maxLatencyPenaltyBps(double bps) {
        if (!(bps >= 0)) {
            throw new IllegalArgumentException("maxLatencyPenaltyBps must be >= 0");
        }
        this.maxLatencyPenaltyBps = bps;
        return this;
    }

    /** The scorecard this router learns from (feed fills/misses/probes to it). */
    public VenueScorecard scorecard() {
        return scorecard;
    }

    /**
     * Routes a marketable parent of {@code quantity}. Lit venues are ranked
     * by expected cost per share, every term in price units:
     *
     * <pre>  allIn + s × [px × ((1−fillRate)×missPenaltyBps + latencyBps) / 1e4
     *              + adverseSelection]</pre>
     *
     * with {@code s = +1} for buys and {@code -1} for sells (a sell ranks by
     * the highest expected proceeds), {@code allIn = px (1 + s fee/1e4)},
     * {@code latencyBps = min(latencyMs × urgencyBpsPerMs, maxLatencyPenaltyBps)}
     * and adverseSelection the venue's negative post-fill markout in price
     * units (a venue with favorable or unknown markout pays nothing extra).
     * Venues are swept best-first at displayed size. Every quoting dark venue
     * gets a contingent probe leg at its midpoint, sized by learned hidden
     * liquidity (or the configured default while unknown); the probes of all
     * dark venues together are capped at {@code maxDarkFraction × quantity},
     * filled in quote order.
     */
    public RoutingDecision route(Side side, long quantity, List<VenueQuote> venues) {
        boolean buy = side == Side.BUY;
        List<RouteLeg> lit = new ArrayList<>();
        List<RouteLeg> probes = new ArrayList<>();
        List<Scored> candidates = new ArrayList<>(venues.size());
        long darkBudget = Math.round(config.maxDarkFraction() * quantity);

        for (VenueQuote v : venues) {
            Integer id = venueIds.get(v.venue());
            if (v.dark()) {
                long probe = Math.min(darkProbeSize(id, quantity), darkBudget);
                if (probe > 0 && !Double.isNaN(v.mid())) {
                    probes.add(new RouteLeg(v.venue(), v.mid(), probe, true));
                    darkBudget -= probe;
                }
                continue;
            }
            double px = buy ? v.ask() : v.bid();
            long size = buy ? v.askSize() : v.bidSize();
            if (size <= 0 || Double.isNaN(px) || px <= 0) {
                continue;
            }
            double fillRate = id != null ? scorecard.fillRate(id) : scorecard.fillRatePrior();
            if (id != null && fillRate < config.minFillRate()) {
                continue;                    // reliability veto
            }
            double latencyNanos = v.latencyNanos();
            if (id != null && scorecard.measuredLatencyNanos(id) > 0) {
                latencyNanos = scorecard.measuredLatencyNanos(id);
            }
            int s = buy ? 1 : -1;
            double allIn = px * (1 + s * v.feeBps() / 1e4);
            double missBps = (1 - fillRate) * config.missPenaltyBps();
            double latBps = Math.min(latencyNanos / 1e6 * config.urgencyBpsPerMs(),
                    maxLatencyPenaltyBps);
            // A venue whose fills revert charges that reversion per share
            // (price units); favorable/unknown markout adds nothing.
            double adverse = 0;
            if (id != null) {
                double markout = scorecard.postFillMarkout(id);
                if (markout < 0) {
                    adverse = -markout;
                }
            }
            double expected = allIn + s * (px * (missBps + latBps) / 1e4 + adverse);
            candidates.add(new Scored(v.venue(), px, expected, size));
        }

        candidates.sort((a, b) -> buy
                ? Double.compare(a.expected, b.expected)
                : Double.compare(b.expected, a.expected));

        long remaining = quantity;
        for (Scored c : candidates) {
            if (remaining <= 0) {
                break;
            }
            long take = Math.min(remaining, c.size);
            lit.add(new RouteLeg(c.venue, c.price, take, false));
            remaining -= take;
        }
        return new RoutingDecision(lit, probes, quantity - remaining, remaining);
    }

    private long darkProbeSize(Integer id, long quantity) {
        double learned = id != null ? scorecard.expectedHiddenShares(id) : 0;
        long base = learned > 0 ? Math.round(learned) : config.defaultDarkProbeShares();
        return Math.min(base, Math.round(config.maxDarkFraction() * quantity));
    }

    /**
     * Fill probability for a passive child joining a queue with
     * {@code qtyAhead} ahead of it — the queue-position leg of the routing
     * checklist, delegated to {@link QueueModel}. Feed {@code qtyAhead}
     * from {@code marketdata.L3BookBuilder.sharesAhead} when you track the
     * order, or the displayed level size before you join.
     */
    public static double passiveFillProbability(long qtyAhead, long orderQty,
                                                double expectedTradedQty) {
        return QueueModel.fillProbability(qtyAhead, orderQty, expectedTradedQty);
    }

    private record Scored(String venue, double price, double expected, long size) {
    }
}

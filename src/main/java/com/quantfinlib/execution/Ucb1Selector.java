package com.quantfinlib.execution;

/**
 * UCB1 multi-armed bandit — principled selection among venues, LPs or
 * algo variants when the scorecards are still THIN. The exploration
 * problem is real: always using the best-so-far venue means never
 * learning whether another improved; rotating uniformly wastes flow on
 * known-bad ones. UCB1 (Auer et al. 2002) picks the arm maximizing
 *
 * <pre>  mean reward + √(2·ln N / nᵢ)</pre>
 *
 * — the optimism bonus shrinks as an arm is tried, so exploration
 * decays exactly as fast as the evidence accumulates, with logarithmic
 * regret guaranteed. Rewards must be in [0, 1] (the theory's scale —
 * map fill quality, negated cost bps, or markout onto it; the gate
 * enforces it because a mis-scaled reward silently breaks the
 * exploration balance).
 *
 * <p>Where this sits vs the scorecards: {@code VenueScorecard} and
 * {@code fx.LpScorecard} are the RIGHT tool once hundreds of fills per
 * venue exist — they model fill rate, latency and markout separately.
 * UCB1 is for the cold start and for A/B-ing ALGO variants (is the
 * new schedule actually better?), where a single scalar reward and a
 * regret guarantee beat a half-warmed-up model. Deterministic
 * (ties break to the lowest index), O(arms) per selection,
 * allocation-free after construction. Research/warm lane.</p>
 */
public final class Ucb1Selector {

    private final double[] rewardSums;
    private final long[] pulls;
    private long totalPulls;

    /** @param arms number of venues/variants, ≥ 2 */
    public Ucb1Selector(int arms) {
        if (arms < 2) {
            throw new IllegalArgumentException("a one-armed bandit is not a decision");
        }
        this.rewardSums = new double[arms];
        this.pulls = new long[arms];
    }

    /**
     * The arm to use next: each arm once first (in index order), then
     * highest upper confidence bound, ties to the lowest index.
     */
    public int select() {
        for (int i = 0; i < pulls.length; i++) {
            if (pulls[i] == 0) {
                return i;               // every arm earns one look
            }
        }
        int best = 0;
        double bestUcb = Double.NEGATIVE_INFINITY;
        double logN = Math.log(totalPulls);
        for (int i = 0; i < pulls.length; i++) {
            double ucb = rewardSums[i] / pulls[i] + Math.sqrt(2 * logN / pulls[i]);
            if (ucb > bestUcb) {
                bestUcb = ucb;
                best = i;
            }
        }
        return best;
    }

    /**
     * Records the observed reward for an arm.
     *
     * @param arm    the arm that was used
     * @param reward in [0, 1] — the UCB1 theory's scale; rescale
     *               upstream, never here
     */
    public void record(int arm, double reward) {
        if (arm < 0 || arm >= pulls.length) {
            throw new IllegalArgumentException("arm " + arm + " of " + pulls.length);
        }
        if (!(reward >= 0 && reward <= 1)) {
            throw new IllegalArgumentException("reward must be in [0, 1], got " + reward
                    + " — a mis-scaled reward silently breaks the exploration balance");
        }
        rewardSums[arm] += reward;
        pulls[arm]++;
        totalPulls++;
    }

    /** Times an arm has been used. */
    public long pulls(int arm) {
        return pulls[arm];
    }

    /** The arm's observed mean reward (NaN before its first pull). */
    public double meanReward(int arm) {
        return pulls[arm] == 0 ? Double.NaN : rewardSums[arm] / pulls[arm];
    }

    public long totalPulls() {
        return totalPulls;
    }
}

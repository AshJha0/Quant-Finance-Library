package com.quantfinlib.fx;

/**
 * Tiered multi-LP FX book: the depth structure e-FX actually has. Equities
 * venues publish one anonymous order book; FX liquidity providers each
 * stream a private ladder of size tiers — 1M at 1.08500/02, 5M at
 * 1.08498/04, 10M wider still — and the practical questions are per-clip:
 * <em>what does 20M actually cost if I sweep</em>
 * ({@link #sweepBuyCost}/{@link #sweepSellProceeds}), and <em>which single
 * LP fills the whole clip at one price</em>
 * ({@link #bestFullAmountAsk}/{@link #bestFullAmountBid} — the full-amount
 * convention that avoids signaling the market with a spray of children).
 *
 * <p>{@code AggregatedBook} remains the top-of-book composite; this class
 * holds the tiers underneath it. Zero allocation: fixed
 * {@code lpCount × maxTiers} primitive arrays, caller-owned scratch for
 * sweep plans, single writer (the feed/aggregation thread). Tiers are
 * expected best-first per LP (tier 0 = tightest), exactly as LP streams
 * publish them; sizes are per-tier clip capacity, prices are the all-in
 * rate for a clip up to that size.</p>
 */
public final class FxTierBook {

    private final int lpCount;
    private final int maxTiers;

    // [lp * maxTiers + tier]
    private final double[] bidPx;
    private final double[] bidSz;
    private final double[] askPx;
    private final double[] askSz;
    private final int[] bidTiers;      // active tier count per LP
    private final int[] askTiers;

    // Sweep frontier scratch: current tier + remaining size per LP.
    // Single-writer, reused across calls.
    private final int[] sweepTier;
    private final double[] sweepRem;

    private double lastFullAmountPrice = Double.NaN;
    private long updateCount;

    public FxTierBook(int lpCount, int maxTiers) {
        if (lpCount < 1 || maxTiers < 1) {
            throw new IllegalArgumentException("need lpCount >= 1, maxTiers >= 1");
        }
        this.lpCount = lpCount;
        this.maxTiers = maxTiers;
        int n = lpCount * maxTiers;
        this.bidPx = new double[n];
        this.bidSz = new double[n];
        this.askPx = new double[n];
        this.askSz = new double[n];
        this.bidTiers = new int[lpCount];
        this.askTiers = new int[lpCount];
        this.sweepTier = new int[lpCount];
        this.sweepRem = new double[lpCount];
    }

    // ------------------------------------------------------------------
    // Feed side
    // ------------------------------------------------------------------

    /**
     * Replaces one tier of one LP's ladder ({@code tier} < maxTiers). Call
     * {@link #tierCount} after the last tier of an update so partially
     * written ladders are never visible to queries from the same thread's
     * later logic.
     *
     * @throws IllegalArgumentException on an out-of-range {@code lp} or
     *         {@code tier} — the flat layout means an unchecked overflow
     *         would silently land in the NEXT LP's ladder, which is the one
     *         failure mode a price store must never have
     */
    public void tier(int lp, boolean bid, int tier, double price, double size) {
        if (lp < 0 || lp >= lpCount || tier < 0 || tier >= maxTiers) {
            throw new IllegalArgumentException(
                    "lp/tier out of range: lp=" + lp + " tier=" + tier
                            + " (lpCount=" + lpCount + ", maxTiers=" + maxTiers + ")");
        }
        int i = lp * maxTiers + tier;
        if (bid) {
            bidPx[i] = price;
            bidSz[i] = size;
        } else {
            askPx[i] = price;
            askSz[i] = size;
        }
    }

    /** Declares how many tiers of {@code lp}'s side are now active (0 pulls the side). */
    public void tierCount(int lp, boolean bid, int count) {
        if (count < 0 || count > maxTiers) {
            throw new IllegalArgumentException("tier count out of range: " + count);
        }
        if (bid) {
            bidTiers[lp] = count;
        } else {
            askTiers[lp] = count;
        }
        updateCount++;
    }

    /** Pulls an LP entirely (disconnect / last-look withdrawal). */
    public void clear(int lp) {
        bidTiers[lp] = 0;
        askTiers[lp] = 0;
        updateCount++;
    }

    // ------------------------------------------------------------------
    // Composite queries
    // ------------------------------------------------------------------

    /**
     * Best (highest) bid across LPs, taken at each LP's FRONTIER tier —
     * the first well-formed one — so a malformed tier 0 (NaN/zero) masks
     * nothing, consistent with how sweeps and full-amount queries read the
     * same ladder. NaN when nobody bids.
     */
    public double bestBid() {
        double best = Double.NaN;
        for (int lp = 0; lp < lpCount; lp++) {
            int t = frontier(lp, bidPx, bidSz, bidTiers[lp], 0);
            if (t < bidTiers[lp]) {
                double p = bidPx[lp * maxTiers + t];
                if (Double.isNaN(best) || p > best) {
                    best = p;
                }
            }
        }
        return best;
    }

    /** Best (lowest) ask across LPs at each LP's frontier tier; NaN when nobody offers. */
    public double bestAsk() {
        double best = Double.NaN;
        for (int lp = 0; lp < lpCount; lp++) {
            int t = frontier(lp, askPx, askSz, askTiers[lp], 0);
            if (t < askTiers[lp]) {
                double p = askPx[lp * maxTiers + t];
                if (Double.isNaN(best) || p < best) {
                    best = p;
                }
            }
        }
        return best;
    }

    /**
     * All-in cost of BUYING {@code size} by sweeping ask tiers across LPs,
     * cheapest tier first. Returns the notional paid (Σ px×qty), or NaN
     * when the book cannot fill the size — a partial sweep is not a price.
     * Zero allocation (internal scratch; single writer).
     */
    public double sweepBuyCost(double size) {
        return sweep(size, /*buy*/ true, null);
    }

    /** Mirror: proceeds of SELLING {@code size} into the bid tiers; NaN if unfillable. */
    public double sweepSellProceeds(double size) {
        return sweep(size, false, null);
    }

    /**
     * Sweep with a plan: {@code outLpQty[lp]} receives the quantity taken
     * from each LP (array length ≥ lpCount, fully overwritten). Returns
     * notional as {@link #sweepBuyCost}/{@link #sweepSellProceeds}.
     */
    public double sweepPlan(boolean buy, double size, double[] outLpQty) {
        return sweep(size, buy, outLpQty);
    }

    private double sweep(double size, boolean buy, double[] outLpQty) {
        if (outLpQty != null) {
            java.util.Arrays.fill(outLpQty, 0, lpCount, 0);
        }
        if (size <= 0) {
            return Double.NaN;
        }
        double[] px = buy ? askPx : bidPx;
        double[] sz = buy ? askSz : bidSz;
        int[] tiers = buy ? askTiers : bidTiers;
        // Per-LP frontier cursors over the best-first ladders: within an LP
        // only the current (frontier) tier can ever be the global best, so
        // each pick scans lpCount candidates instead of every tier — and
        // malformed tiers (NaN price, non-positive/NaN size) are skipped at
        // the frontier, honoring the package's "NaN never wins" convention.
        for (int lp = 0; lp < lpCount; lp++) {
            sweepTier[lp] = frontier(lp, px, sz, tiers[lp], 0);
            sweepRem[lp] = sweepTier[lp] < tiers[lp]
                    ? sz[lp * maxTiers + sweepTier[lp]] : 0;
        }
        double remaining = size;
        double notional = 0;
        while (remaining > 0) {
            int best = -1;
            double bestPx = 0;
            for (int lp = 0; lp < lpCount; lp++) {
                if (sweepTier[lp] >= tiers[lp]) {
                    continue;                  // LP exhausted
                }
                double p = px[lp * maxTiers + sweepTier[lp]];
                if (best == -1 || (buy ? p < bestPx : p > bestPx)) {
                    best = lp;
                    bestPx = p;
                }
            }
            if (best == -1) {
                return Double.NaN;             // book too shallow for the size
            }
            double take = Math.min(remaining, sweepRem[best]);
            sweepRem[best] -= take;
            notional += take * bestPx;
            remaining -= take;
            if (outLpQty != null) {
                outLpQty[best] += take;
            }
            if (sweepRem[best] <= 0) {
                sweepTier[best] = frontier(best, px, sz, tiers[best], sweepTier[best] + 1);
                if (sweepTier[best] < tiers[best]) {
                    sweepRem[best] = sz[best * maxTiers + sweepTier[best]];
                }
            }
        }
        return notional;
    }

    /**
     * First tier at or after {@code from} with a dealable price and positive
     * size; n = exhausted. Dealable = strictly positive: FX outrights are
     * never zero or negative, so 0.0 (the array default — e.g. a decoded
     * empty price field) and NaN both read as "no quote" — a zero ask must
     * never win a buy sweep.
     */
    private int frontier(int lp, double[] px, double[] sz, int n, int from) {
        int base = lp * maxTiers;
        for (int t = from; t < n; t++) {
            if (px[base + t] > 0 && sz[base + t] > 0) {
                return t;
            }
        }
        return n;
    }

    /**
     * Best single-LP full-amount ASK for {@code size}: the lowest tier
     * price whose clip capacity covers the whole size at one LP — one
     * ticket, one price, no signaling. NaN when no LP quotes the size.
     */
    public double bestFullAmountAsk(double size) {
        return fullAmount(size, true);
    }

    /** Mirror on the bid side. */
    public double bestFullAmountBid(double size) {
        return fullAmount(size, false);
    }

    /** LP index behind {@link #bestFullAmountAsk}; -1 when no LP covers the size. */
    public int bestFullAmountAskLp(double size) {
        return fullAmountLp(size, true);
    }

    /** LP index behind {@link #bestFullAmountBid}; -1 when none. */
    public int bestFullAmountBidLp(double size) {
        return fullAmountLp(size, false);
    }

    private double fullAmount(double size, boolean buy) {
        fullAmountLp(size, buy);            // also records the price
        return lastFullAmountPrice;
    }

    private int fullAmountLp(double size, boolean buy) {
        int bestLp = -1;
        double bestPx = 0;
        for (int lp = 0; lp < lpCount; lp++) {
            double p = fullAmountPrice(lp, buy, size);
            if (Double.isNaN(p)) {
                continue;
            }
            if (bestLp == -1 || (buy ? p < bestPx : p > bestPx)) {
                bestLp = lp;
                bestPx = p;
            }
        }
        lastFullAmountPrice = bestLp == -1 ? Double.NaN : bestPx;
        return bestLp;
    }

    /**
     * One LP's full-amount price for a clip: the tightest tier whose clip
     * capacity covers {@code size}, NaN when the LP doesn't quote it (or
     * only at a malformed NaN price). Public because it IS the full-amount
     * convention — routers must consume it, not re-derive it.
     */
    public double fullAmountPrice(int lp, boolean buy, double size) {
        if (size <= 0) {
            return Double.NaN;              // a zero clip is not a quote request
        }
        int base = lp * maxTiers;
        int n = buy ? askTiers[lp] : bidTiers[lp];
        double[] px = buy ? askPx : bidPx;
        double[] sz = buy ? askSz : bidSz;
        for (int t = 0; t < n; t++) {
            // Tiers are best-first: the first well-formed covering tier wins
            // (dealable price = strictly positive, same rule as frontier()).
            if (sz[base + t] >= size && px[base + t] > 0) {
                return px[base + t];
            }
        }
        return Double.NaN;
    }

    public int lpCount() {
        return lpCount;
    }

    public int maxTiers() {
        return maxTiers;
    }

    public int tierCount(int lp, boolean bid) {
        return bid ? bidTiers[lp] : askTiers[lp];
    }

    public double price(int lp, boolean bid, int tier) {
        int i = lp * maxTiers + tier;
        return bid ? bidPx[i] : askPx[i];
    }

    public double size(int lp, boolean bid, int tier) {
        int i = lp * maxTiers + tier;
        return bid ? bidSz[i] : askSz[i];
    }

    public long updateCount() {
        return updateCount;
    }
}

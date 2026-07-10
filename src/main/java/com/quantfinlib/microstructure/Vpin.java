package com.quantfinlib.microstructure;

/**
 * VPIN — Volume-synchronized Probability of INformed trading (Easley,
 * López de Prado &amp; O'Hara): the flow-toxicity gauge a market maker
 * watches to decide when quoting is no longer a business. Trades fill
 * fixed-VOLUME buckets (volume time, not clock time — informed trading
 * compresses clock time but not volume time); each full bucket scores
 * its absolute buy/sell imbalance; VPIN is the average over the last
 * {@code window} buckets:
 *
 * <pre>  VPIN = (1/n) Σ |buyVol − sellVol| / bucketVolume</pre>
 *
 * <p>Balanced two-way flow reads near 0; one-sided (informed) flow
 * reads toward 1 — famously elevated in the hour before the 2010 flash
 * crash. Feed it {@code TradeClassifier}'s aggressor sides when the
 * venue does not disclose them. A trade larger than the bucket's
 * remaining capacity SPLITS across buckets, as the original
 * construction requires. Deterministic, O(1) per trade, allocation
 * only at construction; research/warm lane.</p>
 */
public final class Vpin {

    private final long bucketVolume;
    private final double[] imbalances;      // ring of completed buckets
    private int filled;
    private int head;
    private long bucketBuy;
    private long bucketSell;

    /**
     * @param bucketVolume shares/contracts per volume bucket, &gt; 0
     *                     (the classic choice: ~1/50th of average daily
     *                     volume)
     * @param window       completed buckets averaged, ≥ 1 (classic: 50)
     */
    public Vpin(long bucketVolume, int window) {
        if (bucketVolume <= 0) {
            throw new IllegalArgumentException("bucketVolume must be > 0");
        }
        if (window < 1) {
            throw new IllegalArgumentException("window must be >= 1");
        }
        this.bucketVolume = bucketVolume;
        this.imbalances = new double[window];
    }

    /**
     * One classified trade. Splits across bucket boundaries as needed.
     *
     * @param quantity     traded volume, &gt; 0
     * @param buyAggressor true if the buyer was the aggressor
     */
    public void onTrade(long quantity, boolean buyAggressor) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        long remaining = quantity;
        // Finish the open bucket first.
        long capacity = bucketVolume - (bucketBuy + bucketSell);
        long take = Math.min(remaining, capacity);
        if (buyAggressor) {
            bucketBuy += take;
        } else {
            bucketSell += take;
        }
        remaining -= take;
        if (bucketBuy + bucketSell == bucketVolume) {
            completeBucket();
        }
        if (remaining == 0) {
            return;
        }
        // Whole one-sided buckets, handled ARITHMETICALLY: a block of any
        // size is O(window), never O(quantity/bucketVolume) — buckets
        // older than the window would be evicted anyway, so only the
        // last min(full, window) matter (each has imbalance exactly 1).
        long full = remaining / bucketVolume;
        long toRecord = Math.min(full, imbalances.length);
        for (long i = 0; i < toRecord; i++) {
            imbalances[head] = 1.0;
            head = (head + 1) % imbalances.length;
        }
        // Clamp full BEFORE adding: with bucketVolume == 1 a corrupt
        // Long.MAX_VALUE trade makes filled + full wrap negative.
        filled = (int) Math.min(imbalances.length,
                filled + Math.min(full, imbalances.length));
        remaining -= full * bucketVolume;
        if (remaining > 0) {
            if (buyAggressor) {
                bucketBuy = remaining;
            } else {
                bucketSell = remaining;
            }
        }
    }

    private void completeBucket() {
        imbalances[head] = Math.abs(bucketBuy - bucketSell) / (double) bucketVolume;
        head = (head + 1) % imbalances.length;
        if (filled < imbalances.length) {
            filled++;
        }
        bucketBuy = 0;
        bucketSell = 0;
    }

    /**
     * The toxicity estimate over completed buckets; NaN until the first
     * bucket completes (an empty average pretending to be calm would be
     * exactly the wrong default for a risk signal).
     */
    public double vpin() {
        if (filled == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < filled; i++) {
            sum += imbalances[i];
        }
        return sum / filled;
    }

    /** True once the full window of buckets has completed. */
    public boolean ready() {
        return filled == imbalances.length;
    }

    public int bucketsCompleted() {
        return filled;
    }
}

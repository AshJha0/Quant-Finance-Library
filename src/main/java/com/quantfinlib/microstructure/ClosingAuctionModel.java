package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.util.MathUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Closing-auction participation model. The {@code CLOSING_PRICE} benchmark
 * curve back-loads the continuous session (f²) but is blind to the auction
 * itself — and for many liquid names the close auction is 5-15% of the
 * day, the single deepest liquidity event. Two questions decide how much
 * of a parent to RESERVE for it:
 *
 * <ol>
 *   <li><b>How big is the auction, typically?</b> Learned across days:
 *       feed {@link #onAuctionResult} at each close and the auction's
 *       share of daily volume becomes a day-over-day EWMA (first session
 *       seeds — the same convention as the seasonality curves);</li>
 *   <li><b>Which way is TODAY's auction leaning?</b> From the venue's
 *       imbalance dissemination (see the input contract below): an
 *       imbalance on the OPPOSITE side of your parent means the auction
 *       is looking for exactly your shares — reserve more; an imbalance
 *       on YOUR side means you would join a crowd competing to trade —
 *       reserve less and work the continuous market instead.</li>
 * </ol>
 *
 * <h2>Input contract (documented, not proven)</h2>
 * {@link #onImbalance} expects the fields every major close-auction feed
 * disseminates in the final minutes (Nasdaq NOII, NYSE order imbalance):
 * the <b>imbalance quantity and side</b> (unmatched shares), the
 * <b>paired quantity</b> (shares already crossable), and the
 * <b>indicative/reference prices</b>. This library ships no venue
 * imbalance feed, so this model is a <b>documented-contract structure</b>:
 * the learning and the reserve logic are tested against synthetic inputs,
 * but the mapping from YOUR venue's message format — and the
 * {@code imbalanceSensitivity} calibration — must be validated against
 * real dissemination data before the output steers size. That caveat is
 * the honest price of shipping the model without pretending to have the
 * data.
 *
 * <p>Usage with the executor: cap the continuous-session child at
 * {@code (1 − reserveFraction(buy)) × remaining} — the model shapes what
 * the {@code CLOSING_PRICE} curve leaves for the auction; it does not
 * replace the curve. Zero allocation per event, single writer, one
 * instance per symbol; the learned auction share persists via
 * {@code persist.Checkpoint}, today's imbalance state resets on
 * restore.</p>
 */
public final class ClosingAuctionModel {

    private final double dayAlpha;
    private final double imbalanceSensitivity;
    private final double maxReserveFraction;

    // Learned across days.
    private double auctionShareEwma;
    private int daysLearned;

    // Today's dissemination state. Zero IS the no-dissemination state —
    // a flag would be a second representation of the same fact.
    private double imbalanceRatio;         // signed: + = buy-side imbalance
    private double indicativePressure;     // (indicative − reference)/reference

    /**
     * @param dayAlpha             day-over-day EWMA weight for the auction
     *                             share, e.g. 0.1
     * @param imbalanceSensitivity how hard the live imbalance tilts the
     *                             reserve around the learned share, e.g.
     *                             0.5 (a fully one-sided book moves the
     *                             reserve ±50%) — CALIBRATE on your venue
     * @param maxReserveFraction   hard cap on what may be held back for
     *                             the auction, e.g. 0.3
     */
    public ClosingAuctionModel(double dayAlpha, double imbalanceSensitivity,
                               double maxReserveFraction) {
        if (dayAlpha <= 0 || dayAlpha > 1 || imbalanceSensitivity < 0
                || maxReserveFraction <= 0 || maxReserveFraction > 1) {
            throw new IllegalArgumentException(
                    "need dayAlpha in (0,1], sensitivity >= 0, maxReserve in (0,1]");
        }
        this.dayAlpha = dayAlpha;
        this.imbalanceSensitivity = imbalanceSensitivity;
        this.maxReserveFraction = maxReserveFraction;
    }

    /** 10% day weight, ±50% imbalance tilt, reserve capped at 30%. */
    public ClosingAuctionModel() {
        this(0.1, 0.5, 0.3);
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /**
     * An imbalance dissemination tick (see the class input contract).
     * Non-finite prices or empty books are gaps — nothing updates.
     *
     * @param imbalanceQty   unmatched shares on {@code buyImbalance}'s side
     * @param buyImbalance   true when the unmatched interest is to BUY
     * @param pairedQty      shares already matched at the indicative price
     * @param indicativePrice the price the auction would clear at now
     * @param referencePrice the continuous-market reference (last/mid)
     */
    public void onImbalance(long imbalanceQty, boolean buyImbalance, long pairedQty,
                            double indicativePrice, double referencePrice) {
        if (imbalanceQty < 0 || pairedQty < 0 || imbalanceQty + pairedQty == 0
                || !(indicativePrice > 0) || !(referencePrice > 0)
                || indicativePrice == Double.POSITIVE_INFINITY
                || referencePrice == Double.POSITIVE_INFINITY) {
            return;
        }
        double signed = (buyImbalance ? 1.0 : -1.0) * imbalanceQty;
        imbalanceRatio = signed / (imbalanceQty + pairedQty);
        indicativePressure = MathUtils.clamp(
                (indicativePrice - referencePrice) / referencePrice, -1, 1);
    }

    /**
     * Closes the day: folds the realized auction share of total volume
     * into the learned baseline (first session seeds) and resets today's
     * imbalance state. Zero-volume days teach nothing.
     */
    public void onAuctionResult(long auctionVolume, long continuousVolume) {
        long total = auctionVolume + continuousVolume;
        if (auctionVolume >= 0 && continuousVolume >= 0 && total > 0) {
            double share = (double) auctionVolume / total;
            auctionShareEwma = daysLearned == 0
                    ? share
                    : auctionShareEwma + dayAlpha * (share - auctionShareEwma);
            daysLearned++;
        }
        imbalanceRatio = 0;
        indicativePressure = 0;
    }

    // ------------------------------------------------------------------
    // The decision inputs
    // ------------------------------------------------------------------

    /**
     * The fraction of the remaining parent to hold back for the auction.
     * Base = the learned auction share; the live imbalance tilts it: an
     * imbalance OPPOSITE your side raises the reserve (the auction wants
     * your shares), a same-side imbalance lowers it (you'd join a crowd).
     * Capped at the configured maximum; 0 while nothing is learned.
     */
    public double reserveFraction(boolean parentIsBuy) {
        if (daysLearned == 0) {
            return 0;
        }
        // Opposite-side imbalance is positive tilt for us; before any
        // dissemination the ratio is 0 and the tilt vanishes with it.
        double opposite = parentIsBuy ? -imbalanceRatio : imbalanceRatio;
        double tilt = imbalanceSensitivity * opposite;
        return MathUtils.clamp(auctionShareEwma * (1 + tilt), 0, maxReserveFraction);
    }

    /** The learned typical auction share of daily volume (0 until learned). */
    public double auctionShare() {
        return auctionShareEwma;
    }

    /**
     * Today's signed imbalance as a fraction of total auction interest
     * (+ = buy-side unmatched); 0 before any dissemination.
     */
    public double imbalanceRatio() {
        return imbalanceRatio;
    }

    /**
     * Where the auction is clearing relative to the continuous market,
     * as a clamped relative difference; 0 before any dissemination.
     */
    public double indicativePressure() {
        return indicativePressure;
    }

    public int daysLearned() {
        return daysLearned;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the learned auction share — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeDouble(auctionShareEwma);
        out.writeInt(daysLearned);
    }

    /**
     * Restores the learned share; today's imbalance state resets. Throws
     * on a version mismatch.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "ClosingAuctionModel");
        auctionShareEwma = in.readDouble();
        daysLearned = in.readInt();
        imbalanceRatio = 0;
        indicativePressure = 0;
    }
}

package com.quantfinlib.pricing;

/**
 * A surface of raw SVI slices ({@link SviSlice}), one per pillar expiry,
 * interpolated linearly in total variance at fixed log-moneyness
 * k = ln(K/F(t)).
 *
 * <p>Between pillars, w(t, k) = (1−u)·w₁(k) + u·w₂(k) with
 * u = (t − t₁)/(t₂ − t₁). If the pillar slices do not cross
 * (w₂(k) &ge; w₁(k) for all k) every interpolated w is non-decreasing in t,
 * so the surface has no calendar arbitrage (Gatheral and Jacquier 2014,
 * section 2, lemma 2.1 on total variance). Before the first pillar
 * w = w₁(k)·t/t₁; after the last, w = w_n(k)·t/t_n (constant implied vol per
 * k). Slice independence means crossing slices are possible: check with
 * {@link #calendarViolations} or prefer {@link SsviSurface}, which is
 * calendar-free by construction. Linear interpolation of two butterfly-free
 * slices need not be butterfly-free; {@link #minDensityFactor} checks it.</p>
 */
public final class SviSurface implements TotalVarianceSurface {

    private final double[] expiries;
    private final SviSlice[] slices;
    private final double spot;
    private final double rate;
    private final double carry;

    public SviSurface(double[] expiries, SviSlice[] slices, double spot, double rate, double carry) {
        if (expiries.length == 0 || expiries.length != slices.length) {
            throw new IllegalArgumentException("one slice per expiry required");
        }
        for (int i = 0; i < expiries.length; i++) {
            if (!(expiries[i] > 0) || !Double.isFinite(expiries[i]) || slices[i] == null
                    || (i > 0 && !(expiries[i] > expiries[i - 1]))) {
                throw new IllegalArgumentException("expiries must be positive, ascending, with non-null slices");
            }
        }
        if (!(spot > 0) || !Double.isFinite(spot) || !Double.isFinite(rate) || !Double.isFinite(carry)) {
            throw new IllegalArgumentException("spot must be > 0; rate and carry finite");
        }
        this.expiries = expiries.clone();
        this.slices = slices.clone();
        this.spot = spot;
        this.rate = rate;
        this.carry = carry;
    }

    /**
     * Fits one SVI slice per expiry ({@link SviSlice#calibrate}, butterfly
     * penalty on) to implied vol quotes.
     *
     * @param weights residual weights per quote, or null for equal weights
     */
    public static SviSurface calibrate(double spot, double rate, double carry, double[] expiries,
                                       double[][] strikes, double[][] vols, double[][] weights) {
        int n = expiries.length;
        if (strikes.length != n || vols.length != n || (weights != null && weights.length != n)) {
            throw new IllegalArgumentException("one strike/vol row per expiry required");
        }
        SviSlice[] fitted = new SviSlice[n];
        for (int i = 0; i < n; i++) {
            double t = expiries[i];
            if (!(t > 0) || strikes[i].length != vols[i].length) {
                throw new IllegalArgumentException("expiry " + i + " invalid");
            }
            double f = spot * Math.exp((rate - carry) * t);
            double[] k = new double[strikes[i].length];
            double[] w = new double[k.length];
            for (int j = 0; j < k.length; j++) {
                if (!(strikes[i][j] > 0) || !(vols[i][j] > 0)) {
                    throw new IllegalArgumentException("strikes and vols must be positive");
                }
                k[j] = Math.log(strikes[i][j] / f);
                w[j] = vols[i][j] * vols[i][j] * t;
            }
            fitted[i] = SviSlice.calibrate(k, w, weights == null ? null : weights[i]);
        }
        return new SviSurface(expiries, fitted, spot, rate, carry);
    }

    public int expiryLength() {
        return expiries.length;
    }

    public double expiry(int i) {
        return expiries[i];
    }

    public SviSlice slice(int i) {
        return slices[i];
    }

    @Override
    public double spot() {
        return spot;
    }

    @Override
    public double rate() {
        return rate;
    }

    @Override
    public double carry() {
        return carry;
    }

    @Override
    public double totalVariance(double t, double k) {
        if (!(t > 0)) {
            throw new IllegalArgumentException("t must be > 0");
        }
        int n = expiries.length;
        if (t <= expiries[0]) {
            return slices[0].totalVariance(k) * t / expiries[0];
        }
        if (t >= expiries[n - 1]) {
            return slices[n - 1].totalVariance(k) * t / expiries[n - 1];
        }
        int i = 1;
        while (expiries[i] < t) {
            i++;
        }
        double u = (t - expiries[i - 1]) / (expiries[i] - expiries[i - 1]);
        return (1 - u) * slices[i - 1].totalVariance(k) + u * slices[i].totalVariance(k);
    }
}

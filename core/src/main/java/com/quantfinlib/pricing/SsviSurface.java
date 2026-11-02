package com.quantfinlib.pricing;

import java.util.function.Function;

/**
 * Surface SVI (Gatheral and Jacquier 2014, "Arbitrage-free SVI volatility
 * surfaces", Quantitative Finance 14(1), definition 4.1):
 *
 * <pre>
 *   w(k, θ_t) = θ_t / 2 · ( 1 + ρ φ(θ_t) k + √((φ(θ_t) k + ρ)² + 1 − ρ²) )
 * </pre>
 *
 * <p>with θ_t the ATM total variance at expiry t and the power-law
 * φ(θ) = η / (θ^γ (1 + θ)^{1−γ}) (their equation 4.5).</p>
 *
 * <p>No-arbitrage. Theorem 4.1: the surface is free of calendar arbitrage
 * iff θ_t is non-decreasing in t and 0 &le; ∂_θ(θφ) &le; φ(1+√(1−ρ²))/ρ².
 * For the power law ∂_θ(θφ) = η(1−γ)θ^{−γ}(1+θ)^{γ−2}, which satisfies
 * both bounds for γ ∈ [0, 1]. Theorem 4.2: the surface is free of butterfly
 * arbitrage if θφ(1+|ρ|) &lt; 4 and θφ²(1+|ρ|) &le; 4 for all θ. With
 * γ ∈ [0, 1/2] one has θφ &lt; η and θφ² &le; η², so the condition
 * η(1+|ρ|) &le; 2 (their remark 4.4) gives both. The constructor enforces
 * γ ∈ [0, 1/2] and η(1+|ρ|) &le; 2, so every instance is free of static
 * arbitrage for any non-decreasing θ_t.</p>
 *
 * <p>Interpolation in time is linear in θ between pillar expiries (so
 * calendar-arbitrage-free by theorem 4.1), proportional to t before the
 * first pillar (θ_0 = 0) and extended with the last segment's slope after
 * the last pillar. Log-moneyness is against F(t) = S e^{(r−q)t}.</p>
 *
 * <p>η = 0 gives a flat smile w = θ_t: with θ_t = σ²t that is the
 * Black-Scholes surface. Immutable.</p>
 */
public final class SsviSurface implements TotalVarianceSurface {

    private final double rho;
    private final double eta;
    private final double gamma;
    private final double[] expiries;
    private final double[] thetas;
    private final double spot;
    private final double rate;
    private final double carry;

    /**
     * @param rho      correlation-like skew parameter, |ρ| &lt; 1
     * @param eta      power-law level η &ge; 0 with η(1+|ρ|) &le; 2
     * @param gamma    power-law exponent in [0, 1/2]
     * @param expiries strictly ascending pillar expiries in years
     * @param thetas   ATM total variance at each pillar, positive and non-decreasing
     */
    public SsviSurface(double rho, double eta, double gamma, double[] expiries, double[] thetas,
                       double spot, double rate, double carry) {
        if (!(Math.abs(rho) < 1) || !(eta >= 0) || !(eta * (1 + Math.abs(rho)) <= 2 + 1e-12)
                || !(gamma >= 0 && gamma <= 0.5)) {
            throw new IllegalArgumentException(
                    "SSVI power law requires |rho| < 1, eta >= 0, eta(1+|rho|) <= 2, gamma in [0, 1/2]");
        }
        if (expiries.length == 0 || expiries.length != thetas.length) {
            throw new IllegalArgumentException("expiries and thetas must be non-empty and the same length");
        }
        for (int i = 0; i < expiries.length; i++) {
            if (!(expiries[i] > 0) || !(thetas[i] > 0) || !Double.isFinite(thetas[i])
                    || !Double.isFinite(expiries[i])) {
                throw new IllegalArgumentException("expiries and thetas must be positive and finite");
            }
            if (i > 0 && (!(expiries[i] > expiries[i - 1]) || thetas[i] < thetas[i - 1])) {
                throw new IllegalArgumentException(
                        "expiries must be strictly ascending and thetas non-decreasing (theorem 4.1)");
            }
        }
        if (!(spot > 0) || !Double.isFinite(spot) || !Double.isFinite(rate) || !Double.isFinite(carry)) {
            throw new IllegalArgumentException("spot must be > 0; rate and carry finite");
        }
        this.rho = rho;
        this.eta = eta;
        this.gamma = gamma;
        this.expiries = expiries.clone();
        this.thetas = thetas.clone();
        this.spot = spot;
        this.rate = rate;
        this.carry = carry;
    }

    public double rho() {
        return rho;
    }

    public double eta() {
        return eta;
    }

    public double gamma() {
        return gamma;
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

    public int expiryLength() {
        return expiries.length;
    }

    public double expiry(int i) {
        return expiries[i];
    }

    /** ATM total variance at pillar {@code i}. */
    public double theta(int i) {
        return thetas[i];
    }

    /** ATM total variance θ_t at any t &gt; 0, interpolated as described in the class note. */
    public double thetaAt(double t) {
        if (!(t > 0)) {
            throw new IllegalArgumentException("t must be > 0");
        }
        int n = expiries.length;
        if (t <= expiries[0]) {
            return thetas[0] * t / expiries[0];
        }
        if (t >= expiries[n - 1]) {
            double slope = n == 1 ? thetas[0] / expiries[0]
                    : (thetas[n - 1] - thetas[n - 2]) / (expiries[n - 1] - expiries[n - 2]);
            return thetas[n - 1] + slope * (t - expiries[n - 1]);
        }
        int i = 1;
        while (expiries[i] < t) {
            i++;
        }
        double u = (t - expiries[i - 1]) / (expiries[i] - expiries[i - 1]);
        return thetas[i - 1] + u * (thetas[i] - thetas[i - 1]);
    }

    /** Power-law φ(θ) = η / (θ^γ (1+θ)^{1−γ}). */
    public double phi(double theta) {
        return eta / (Math.pow(theta, gamma) * Math.pow(1 + theta, 1 - gamma));
    }

    @Override
    public double totalVariance(double t, double k) {
        return totalVarianceAtTheta(thetaAt(t), k);
    }

    private double totalVarianceAtTheta(double theta, double k) {
        double p = phi(theta);
        double x = p * k + rho;
        return 0.5 * theta * (1 + rho * p * k + Math.sqrt(x * x + 1 - rho * rho));
    }

    /**
     * The raw SVI slice equal to this surface at expiry t (Gatheral-Jacquier
     * 2014, section 4): a = θ(1−ρ²)/2, b = θφ/2, m = −ρ/φ, σ = √(1−ρ²)/φ.
     *
     * @throws IllegalStateException when η = 0 (flat smile, no finite SVI σ)
     */
    public SviSlice slice(double t) {
        double theta = thetaAt(t);
        double p = phi(theta);
        if (!(p > 0)) {
            throw new IllegalStateException("flat SSVI (eta = 0) has no raw SVI form");
        }
        return new SviSlice(0.5 * theta * (1 - rho * rho), 0.5 * theta * p, rho, -rho / p,
                Math.sqrt(1 - rho * rho) / p);
    }

    /**
     * Calibrates ρ, η and γ to implied vol quotes, by Levenberg-Marquardt on
     * weighted total-variance residuals in a reparametrisation that enforces
     * the constructor's no-arbitrage bounds. θ at each expiry is the market
     * ATM total variance: linear interpolation of quoted total variance in
     * log-moneyness at k = 0 (nearest quote if k = 0 is not bracketed), then
     * made non-decreasing by a running maximum.
     *
     * @param expiries strictly ascending expiries
     * @param strikes  strikes per expiry (each at least 2, ascending)
     * @param vols     Black-Scholes implied vols matching {@code strikes}
     * @param weights  residual weights matching {@code strikes}, or null for equal
     */
    public static SsviSurface calibrate(double spot, double rate, double carry, double[] expiries,
                                        double[][] strikes, double[][] vols, double[][] weights) {
        int n = expiries.length;
        if (n == 0 || strikes.length != n || vols.length != n || (weights != null && weights.length != n)) {
            throw new IllegalArgumentException("one strike/vol row per expiry required");
        }
        if (!(spot > 0)) {
            throw new IllegalArgumentException("spot must be > 0");
        }
        double[][] ks = new double[n][];
        double[][] ws = new double[n][];
        double[][] wt = new double[n][];
        double[] thetas = new double[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            double t = expiries[i];
            if (!(t > 0)) {
                throw new IllegalArgumentException("expiries must be > 0");
            }
            int m = strikes[i].length;
            if (m < 2 || vols[i].length != m || (weights != null && weights[i].length != m)) {
                throw new IllegalArgumentException("expiry " + i + ": need >= 2 quotes, lengths matching");
            }
            double f = spot * Math.exp((rate - carry) * t);
            ks[i] = new double[m];
            ws[i] = new double[m];
            wt[i] = new double[m];
            for (int j = 0; j < m; j++) {
                if (!(strikes[i][j] > 0) || !(vols[i][j] > 0) || !Double.isFinite(vols[i][j])) {
                    throw new IllegalArgumentException("strikes and vols must be positive");
                }
                if (j > 0 && !(strikes[i][j] > strikes[i][j - 1])) {
                    throw new IllegalArgumentException("strikes must be ascending");
                }
                ks[i][j] = Math.log(strikes[i][j] / f);
                ws[i][j] = vols[i][j] * vols[i][j] * t;
                wt[i][j] = weights == null ? 1 : weights[i][j];
            }
            thetas[i] = atmTotalVariance(ks[i], ws[i]);
            if (i > 0) {
                thetas[i] = Math.max(thetas[i], thetas[i - 1]);
            }
            total += m;
        }
        final int count = total;
        Function<double[], SsviSurface> decode = p -> {
            double r = 0.999 * Math.tanh(p[0]);
            double e = 2 / (1 + Math.abs(r)) / (1 + Math.exp(-p[1]));
            double g = 0.5 / (1 + Math.exp(-p[2]));
            return new SsviSurface(r, e, g, expiries, thetas, spot, rate, carry);
        };
        Function<double[], double[]> res = p -> {
            SsviSurface s = decode.apply(p);
            double[] out = new double[count];
            int c = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < ks[i].length; j++) {
                    out[c++] = wt[i][j] * (s.totalVarianceAtTheta(thetas[i], ks[i][j]) - ws[i][j]);
                }
            }
            return out;
        };
        LeastSquares.Solution best = null;
        for (double r0 : new double[]{-0.6, -0.2, 0.2, 0.6}) {
            for (double e0 : new double[]{-1, 1}) {
                LeastSquares.Solution s = LeastSquares.minimise(res, new double[]{atanh(r0 / 0.999), e0, 0}, 400);
                if (best == null || s.cost() < best.cost()) {
                    best = s;
                }
            }
        }
        return decode.apply(new double[]{best.x(0), best.x(1), best.x(2)});
    }

    private static double atmTotalVariance(double[] k, double[] w) {
        if (k[0] >= 0) {
            return w[0];
        }
        for (int j = 1; j < k.length; j++) {
            if (k[j] >= 0) {
                double u = -k[j - 1] / (k[j] - k[j - 1]);
                return w[j - 1] + u * (w[j] - w[j - 1]);
            }
        }
        return w[k.length - 1];
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }
}

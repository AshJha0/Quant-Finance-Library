package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Finite-difference pricer for European options under Dupire local
 * volatility. In x = ln S and time to expiry τ the price solves
 *
 * <pre>
 *   V_τ = ½σ²(t, e^x) V_xx + (r − q − ½σ²) V_x − r V,   t = T − τ
 * </pre>
 *
 * <p>Crank-Nicolson in τ with Rannacher start (Rannacher 1984; Giles and
 * Carter 2006, "Convergence analysis of Crank-Nicolson and Rannacher
 * time-marching", J. Comp. Finance 9(4)): the first two Crank-Nicolson steps
 * are replaced by four implicit Euler half steps, which damps the
 * oscillation the payoff kink otherwise leaves in the Greeks. Uniform x grid
 * centred on ln K (so the kink sits on a node) with half-width
 * |ln(S/K)| + 8 ATM standard deviations; Dirichlet boundaries at the
 * discounted intrinsic forward values. Local vol is evaluated at the middle
 * of each step. The price at spot is quadratic interpolation over the three
 * nearest nodes. Second order in Δx and Δτ.</p>
 */
public final class LocalVolPde {

    private final DupireLocalVol localVol;

    public LocalVolPde(DupireLocalVol localVol) {
        if (localVol == null) {
            throw new IllegalArgumentException("localVol must not be null");
        }
        this.localVol = localVol;
    }

    /**
     * European option price.
     *
     * @param spaceNodes number of x nodes (&ge; 11)
     * @param timeSteps  number of Crank-Nicolson steps (&ge; 2)
     */
    public double price(OptionType type, double strike, double expiry, int spaceNodes, int timeSteps) {
        if (!(strike > 0) || !(expiry > 0) || spaceNodes < 11 || timeSteps < 2) {
            throw new IllegalArgumentException("strike, expiry > 0; spaceNodes >= 11; timeSteps >= 2");
        }
        TotalVarianceSurface s = localVol.surface();
        double r = s.rate();
        double q = s.carry();
        double spot = s.spot();
        double xk = Math.log(strike);
        double half = Math.abs(Math.log(spot / strike)) + 8 * Math.sqrt(s.totalVariance(expiry, 0));
        int nx = spaceNodes | 1;   // odd, so ln K is the middle node
        int mid = nx / 2;
        double dx = half / mid;
        double[] x = new double[nx];
        double[] v = new double[nx];
        for (int i = 0; i < nx; i++) {
            x[i] = xk + (i - mid) * dx;
            v[i] = Math.max(0, type.sign() * (Math.exp(x[i]) - strike));
        }
        double dTau = expiry / timeSteps;
        double tau = 0;
        // Rannacher: four implicit half steps, then Crank-Nicolson.
        for (int k = 0; k < 4; k++) {
            step(v, x, dx, tau, 0.5 * dTau, 1.0, type, strike, expiry, r, q);
            tau += 0.5 * dTau;
        }
        for (int k = 2; k < timeSteps; k++) {
            step(v, x, dx, tau, dTau, 0.5, type, strike, expiry, r, q);
            tau += dTau;
        }
        double pos = (Math.log(spot) - x[0]) / dx;
        int i = (int) Math.round(pos);
        i = Math.max(1, Math.min(nx - 2, i));
        double u = pos - i;
        return v[i] + 0.5 * u * (v[i + 1] - v[i - 1]) + 0.5 * u * u * (v[i + 1] - 2 * v[i] + v[i - 1]);
    }

    /** One θ-scheme step from τ to τ + h (θ = 1 implicit Euler, ½ Crank-Nicolson). */
    private void step(double[] v, double[] x, double dx, double tau, double h, double theta,
                      OptionType type, double strike, double expiry, double r, double q) {
        int n = v.length;
        double tMid = expiry - (tau + 0.5 * h);
        double[] lo = new double[n];
        double[] di = new double[n];
        double[] up = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double sig = localVol.localVol(Math.max(tMid, 0), Math.exp(x[i]));
            double a = 0.5 * sig * sig / (dx * dx);
            double b = (r - q - 0.5 * sig * sig) / (2 * dx);
            lo[i] = a - b;
            di[i] = -2 * a - r;
            up[i] = a + b;
        }
        double[] rhs = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double lv = lo[i] * v[i - 1] + di[i] * v[i] + up[i] * v[i + 1];
            rhs[i] = v[i] + (1 - theta) * h * lv;
        }
        double tauNew = tau + h;
        double sNew0 = Math.exp(x[0]);
        double sNewN = Math.exp(x[n - 1]);
        double dq = Math.exp(-q * tauNew);
        double dr = Math.exp(-r * tauNew);
        double b0 = type == OptionType.CALL ? 0 : Math.max(0, strike * dr - sNew0 * dq);
        double bN = type == OptionType.CALL ? Math.max(0, sNewN * dq - strike * dr) : 0;
        // Thomas algorithm on (I − θhL) v_new = rhs, Dirichlet ends.
        double[] cp = new double[n];
        double[] dp = new double[n];
        cp[0] = 0;
        dp[0] = b0;
        for (int i = 1; i < n - 1; i++) {
            double aa = -theta * h * lo[i];
            double bb = 1 - theta * h * di[i];
            double cc = -theta * h * up[i];
            double m = bb - aa * cp[i - 1];
            cp[i] = cc / m;
            dp[i] = (rhs[i] - aa * dp[i - 1]) / m;
        }
        v[n - 1] = bN;
        for (int i = n - 2; i >= 1; i--) {
            v[i] = dp[i] - cp[i] * v[i + 1];
        }
        v[0] = b0;
    }
}

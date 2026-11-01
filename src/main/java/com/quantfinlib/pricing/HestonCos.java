package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Heston European options by the COS method of Fang and Oosterlee (2008, "A
 * novel pricing method for European options based on Fourier-cosine series
 * expansions", SIAM J. Sci. Comput. 31(2), 826-848), with analytic Greeks and
 * an analytic parameter gradient.
 *
 * <p>The idea: the density of {@code y = ln(S_T/K)} is replaced by a cosine
 * series on a finite interval {@code [a, b]}, whose coefficients are read off
 * the characteristic function, and the payoff's cosine coefficients are known
 * in closed form. For a put (eq. 30 and 33 of the paper),</p>
 *
 * <pre>
 *   put = K e^{-rT} sum'_{k=0}^{N-1} Re[ phi(u_k) e^{i u_k (x - a)} ] V_k,   u_k = k pi / (b - a)
 *   V_k = 2/(b - a) (psi_k(a, 0) - chi_k(a, 0)),   x = ln(S/K)
 * </pre>
 *
 * <p>where the prime halves the k = 0 term and chi, psi are the exponential
 * and unit cosine integrals of eq. 22-23. The call follows from put-call
 * parity, which avoids the cancellation the paper reports for calls with a
 * wide interval. {@code phi} is the Heston characteristic function of
 * {@code ln(S_T/S_0)} in the paper's eq. 32 form,</p>
 *
 * <pre>
 *   phi(w) = exp(i w (r-q) T + v0/sigma^2 (1 - e^{-DT})/(1 - G e^{-DT}) (kappa - i rho sigma w - D))
 *          * exp(kappa theta/sigma^2 [T (kappa - i rho sigma w - D) - 2 ln((1 - G e^{-DT})/(1 - G))])
 *   D = sqrt((kappa - i rho sigma w)^2 + (w^2 + i w) sigma^2),   G = (kappa - i rho sigma w - D)/(kappa - i rho sigma w + D)
 * </pre>
 *
 * <p>which is the "little Heston trap" form (Albrecher et al. 2007) and needs
 * no branch tracking with the principal square root and logarithm. The
 * interval is {@code [x + c1 - L sqrt(c2), x + c1 + L sqrt(c2)]} with the
 * first two cumulants of the paper's Table 11 and {@code L = 12}, c4 omitted as
 * the paper recommends for Heston.</p>
 *
 * <p>Greeks: with the interval held fixed, the expansion is an explicit
 * function of {@code x} and of the five parameters. Delta and gamma
 * differentiate {@code e^{i u_k x}}; the parameter gradient differentiates
 * {@code phi} by forward-mode automatic differentiation over complex dual
 * numbers, so every derivative is exact for the truncated series (no bump
 * size). The interval itself moves with spot and parameters; at the default
 * {@code N} the price is converged far below the size of that effect, and the
 * tests check the analytic values against central differences of the full
 * pricer. Research lane; allocates a few small objects per call.</p>
 */
public final class HestonCos {

    /** Default number of cosine terms. */
    public static final int DEFAULT_TERMS = 256;
    /** Default truncation width in standard deviations. */
    public static final double DEFAULT_TRUNCATION = 12;

    /** Number of model parameters in the gradient, in {@link Heston.Params} order. */
    static final int NP = 5;

    private HestonCos() {
    }

    /** European price with the default number of terms and truncation. */
    public static double price(OptionType type, double spot, double strike, double rate,
                               double divYield, double timeYears, Heston.Params p) {
        return price(type, spot, strike, rate, divYield, timeYears, p, DEFAULT_TERMS,
                DEFAULT_TRUNCATION);
    }

    /**
     * European price.
     *
     * @param terms      number of cosine terms N, at least 8
     * @param truncation interval half-width L in units of sqrt(c2), positive
     */
    public static double price(OptionType type, double spot, double strike, double rate,
                               double divYield, double timeYears, Heston.Params p,
                               int terms, double truncation) {
        double[] r = expand(spot, strike, rate, divYield, timeYears, p, terms, truncation, false);
        return type == OptionType.PUT ? r[0] : r[0] + parityShift(spot, strike, rate, divYield, timeYears);
    }

    /** Delta dV/dS from the expansion (analytic in x = ln(S/K)). */
    public static double delta(OptionType type, double spot, double strike, double rate,
                               double divYield, double timeYears, Heston.Params p) {
        double[] r = expand(spot, strike, rate, divYield, timeYears, p, DEFAULT_TERMS,
                DEFAULT_TRUNCATION, false);
        double putDelta = r[1] / spot;
        return type == OptionType.PUT ? putDelta : putDelta + Math.exp(-divYield * timeYears);
    }

    /** Gamma d2V/dS2 (same for calls and puts). */
    public static double gamma(double spot, double strike, double rate, double divYield,
                               double timeYears, Heston.Params p) {
        double[] r = expand(spot, strike, rate, divYield, timeYears, p, DEFAULT_TERMS,
                DEFAULT_TRUNCATION, false);
        return (r[2] - r[1]) / (spot * spot);
    }

    /**
     * Sensitivity to the initial variance, dV/dv0 (same for calls and puts).
     * Multiply by {@code 2 sqrt(v0)} for the sensitivity to initial volatility.
     */
    public static double vegaV0(double spot, double strike, double rate, double divYield,
                                double timeYears, Heston.Params p) {
        return parameterGradient(spot, strike, rate, divYield, timeYears, p)[4];
    }

    /**
     * Gradient of the price with respect to (kappa, theta, sigmaV, rho, v0),
     * identical for calls and puts since parity does not involve the model.
     * Returns a new array of length 5.
     */
    public static double[] parameterGradient(double spot, double strike, double rate,
                                             double divYield, double timeYears, Heston.Params p) {
        return parameterGradient(spot, strike, rate, divYield, timeYears, p, DEFAULT_TERMS,
                DEFAULT_TRUNCATION);
    }

    static double[] parameterGradient(double spot, double strike, double rate, double divYield,
                                      double timeYears, Heston.Params p, int terms,
                                      double truncation) {
        double[] r = expand(spot, strike, rate, divYield, timeYears, p, terms, truncation, true);
        double[] g = new double[NP];
        System.arraycopy(r, 3, g, 0, NP);
        return g;
    }

    /** Price and gradient in one pass: element 0 is the price, 1..5 the gradient. */
    static double[] priceAndGradient(OptionType type, double spot, double strike, double rate,
                                     double divYield, double timeYears, Heston.Params p) {
        double[] r = expand(spot, strike, rate, divYield, timeYears, p, DEFAULT_TERMS,
                DEFAULT_TRUNCATION, true);
        double[] out = new double[NP + 1];
        out[0] = type == OptionType.PUT ? r[0]
                : r[0] + parityShift(spot, strike, rate, divYield, timeYears);
        System.arraycopy(r, 3, out, 1, NP);
        return out;
    }

    private static double parityShift(double spot, double strike, double rate, double divYield,
                                      double t) {
        return spot * Math.exp(-divYield * t) - strike * Math.exp(-rate * t);
    }

    /** First two cumulants of ln(S_T/S_0) (Fang-Oosterlee 2008, Table 11). */
    static double[] cumulants(double rate, double divYield, double t, Heston.Params p) {
        double lam = p.kappa(), th = p.theta(), eta = p.sigmaV(), rho = p.rho(), u0 = p.v0();
        double e1 = Math.exp(-lam * t);
        double c1 = (rate - divYield) * t + (1 - e1) * (th - u0) / (2 * lam) - 0.5 * th * t;
        double c2 = (eta * t * lam * e1 * (u0 - th) * (8 * lam * rho - 4 * eta)
                + lam * rho * eta * (1 - e1) * (16 * th - 8 * u0)
                + 2 * th * lam * t * (-4 * lam * rho * eta + eta * eta + 4 * lam * lam)
                + eta * eta * ((th - 2 * u0) * e1 * e1 + th * (6 * e1 - 7) + 2 * u0)
                + 8 * lam * lam * (u0 - th) * (1 - e1)) / (8 * lam * lam * lam);
        return new double[]{c1, Math.abs(c2)};
    }

    /**
     * Core expansion for the put. Returns {put, dV/dx, d2V/dx2, dV/dparam_0..4}
     * (the gradient entries are zero unless {@code withGradient}).
     */
    private static double[] expand(double spot, double strike, double rate, double divYield,
                                   double t, Heston.Params p, int terms, double truncation,
                                   boolean withGradient) {
        if (!(spot > 0) || !(strike > 0) || !(t > 0) || !Double.isFinite(spot)
                || !Double.isFinite(strike) || !Double.isFinite(t) || !Double.isFinite(rate)
                || !Double.isFinite(divYield)) {
            throw new IllegalArgumentException("invalid market inputs");
        }
        if (terms < 8 || !(truncation > 0) || !Double.isFinite(truncation)) {
            throw new IllegalArgumentException("need terms >= 8 and a positive finite truncation");
        }
        double x = Math.log(spot / strike);
        double[] cum = cumulants(rate, divYield, t, p);
        double half = truncation * Math.sqrt(cum[1]);
        double a = x + cum[0] - half;
        double b = x + cum[0] + half;
        double width = b - a;
        double d = Math.max(a, Math.min(0, b));      // put payoff lives on y in [a, min(0, b)]
        double scale = strike * Math.exp(-rate * t);

        Dual kappa = Dual.param(p.kappa(), 0, withGradient);
        Dual theta = Dual.param(p.theta(), 1, withGradient);
        Dual sigma = Dual.param(p.sigmaV(), 2, withGradient);
        Dual rho = Dual.param(p.rho(), 3, withGradient);
        Dual v0 = Dual.param(p.v0(), 4, withGradient);
        Dual sigma2 = sigma.mul(sigma);
        Dual kappaThetaOverS2 = kappa.mul(theta).div(sigma2);
        Dual v0OverS2 = v0.div(sigma2);
        Dual rhoSigma = rho.mul(sigma);

        double v = 0, dx = 0, dxx = 0;
        double[] grad = new double[NP];
        for (int k = 0; k < terms; k++) {
            double u = k * Math.PI / width;
            double vk = 2 / width * (psi(k, a, d, a, width) - chi(k, a, d, a, width));
            if (vk == 0) {
                continue;
            }
            double w = k == 0 ? 0.5 : 1;
            Dual phi = characteristic(u, rate - divYield, t, kappa, sigma2, kappaThetaOverS2,
                    v0OverS2, rhoSigma);
            // e^{i u (x - a)}
            double ph = u * (x - a);
            double cr = Math.cos(ph), ci = Math.sin(ph);
            double re = phi.re * cr - phi.im * ci;
            double im = phi.re * ci + phi.im * cr;
            double f = w * vk;
            v += f * re;
            dx += f * (-u * im);              // Re[(i u) z] = -u Im z
            dxx += f * (-u * u * re);         // Re[(i u)^2 z] = -u^2 Re z
            if (withGradient) {
                for (int j = 0; j < NP; j++) {
                    grad[j] += f * (phi.dre[j] * cr - phi.dim[j] * ci);
                }
            }
        }
        double[] out = new double[3 + NP];
        out[0] = Math.max(0, scale * v);
        out[1] = scale * dx;
        out[2] = scale * dxx;
        for (int j = 0; j < NP; j++) {
            out[3 + j] = scale * grad[j];
        }
        if (!Double.isFinite(out[0]) || !Double.isFinite(out[1])) {
            throw new ArithmeticException("COS expansion produced a non-finite value");
        }
        return out;
    }

    /** Heston characteristic function of ln(S_T/S_0) at real u, as a dual over the parameters. */
    private static Dual characteristic(double u, double mu, double t, Dual kappa, Dual sigma2,
                                       Dual kts2, Dual v0s2, Dual rhoSigma) {
        // beta = kappa - i rho sigma u
        Dual beta = kappa.sub(rhoSigma.mulImag(u));
        // D = sqrt(beta^2 + (u^2 + i u) sigma^2)
        Dual dd = beta.mul(beta).add(sigma2.mulComplex(u * u, u)).sqrt();
        Dual bMinusD = beta.sub(dd);
        Dual g = bMinusD.div(beta.add(dd));
        Dual eDt = dd.scale(-t).exp();
        Dual oneMinusGe = Dual.one(kappa).sub(g.mul(eDt));
        Dual oneMinusG = Dual.one(kappa).sub(g);
        Dual dTerm = v0s2.mul(Dual.one(kappa).sub(eDt)).div(oneMinusGe).mul(bMinusD);
        Dual cTerm = kts2.mul(bMinusD.scale(t).sub(oneMinusGe.div(oneMinusG).log().scale(2)));
        Dual expo = cTerm.add(dTerm);
        expo.im += u * mu * t;
        return expo.exp();
    }

    /** chi_k(c, d): integral of e^y cos(k pi (y - a)/(b - a)) over [c, d] (eq. 22). */
    private static double chi(int k, double c, double d, double a, double width) {
        if (d <= c) {
            return 0;
        }
        double w = k * Math.PI / width;
        double ed = Math.exp(d), ec = Math.exp(c);
        return (Math.cos(w * (d - a)) * ed - Math.cos(w * (c - a)) * ec
                + w * Math.sin(w * (d - a)) * ed - w * Math.sin(w * (c - a)) * ec) / (1 + w * w);
    }

    /** psi_k(c, d): integral of cos(k pi (y - a)/(b - a)) over [c, d] (eq. 23). */
    private static double psi(int k, double c, double d, double a, double width) {
        if (d <= c) {
            return 0;
        }
        if (k == 0) {
            return d - c;
        }
        double w = k * Math.PI / width;
        return (Math.sin(w * (d - a)) - Math.sin(w * (c - a))) / w;
    }

    /**
     * Complex number carrying its partial derivatives with respect to the five
     * Heston parameters (forward-mode automatic differentiation). Every
     * operation applies the complex chain rule {@code d f(z) = f'(z) dz}.
     */
    static final class Dual {
        double re;
        double im;
        final double[] dre;
        final double[] dim;

        private Dual(double re, double im, double[] dre, double[] dim) {
            this.re = re;
            this.im = im;
            this.dre = dre;
            this.dim = dim;
        }

        static Dual param(double value, int index, boolean track) {
            Dual z = new Dual(value, 0, new double[NP], new double[NP]);
            if (track) {
                z.dre[index] = 1;
            }
            return z;
        }

        static Dual one(Dual like) {
            return new Dual(1, 0, new double[like.dre.length], new double[like.dre.length]);
        }

        private static Dual blank(double re, double im) {
            return new Dual(re, im, new double[NP], new double[NP]);
        }

        Dual add(Dual o) {
            Dual z = blank(re + o.re, im + o.im);
            for (int j = 0; j < NP; j++) {
                z.dre[j] = dre[j] + o.dre[j];
                z.dim[j] = dim[j] + o.dim[j];
            }
            return z;
        }

        Dual sub(Dual o) {
            Dual z = blank(re - o.re, im - o.im);
            for (int j = 0; j < NP; j++) {
                z.dre[j] = dre[j] - o.dre[j];
                z.dim[j] = dim[j] - o.dim[j];
            }
            return z;
        }

        Dual mul(Dual o) {
            Dual z = blank(re * o.re - im * o.im, re * o.im + im * o.re);
            for (int j = 0; j < NP; j++) {
                z.dre[j] = dre[j] * o.re - dim[j] * o.im + re * o.dre[j] - im * o.dim[j];
                z.dim[j] = dre[j] * o.im + dim[j] * o.re + re * o.dim[j] + im * o.dre[j];
            }
            return z;
        }

        /** this * (cr + i ci) for a constant complex number. */
        Dual mulComplex(double cr, double ci) {
            Dual z = blank(re * cr - im * ci, re * ci + im * cr);
            for (int j = 0; j < NP; j++) {
                z.dre[j] = dre[j] * cr - dim[j] * ci;
                z.dim[j] = dre[j] * ci + dim[j] * cr;
            }
            return z;
        }

        /** this * (i c). */
        Dual mulImag(double c) {
            return mulComplex(0, c);
        }

        Dual scale(double c) {
            return mulComplex(c, 0);
        }

        Dual div(Dual o) {
            double den = o.re * o.re + o.im * o.im;
            double qr = (re * o.re + im * o.im) / den;
            double qi = (im * o.re - re * o.im) / den;
            Dual z = blank(qr, qi);
            // d(a/b) = (da - q db) / b
            for (int j = 0; j < NP; j++) {
                double nr = dre[j] - (qr * o.dre[j] - qi * o.dim[j]);
                double ni = dim[j] - (qr * o.dim[j] + qi * o.dre[j]);
                z.dre[j] = (nr * o.re + ni * o.im) / den;
                z.dim[j] = (ni * o.re - nr * o.im) / den;
            }
            return z;
        }

        Dual exp() {
            double m = Math.exp(re);
            double er = m * Math.cos(im), ei = m * Math.sin(im);
            Dual z = blank(er, ei);
            for (int j = 0; j < NP; j++) {
                z.dre[j] = er * dre[j] - ei * dim[j];
                z.dim[j] = er * dim[j] + ei * dre[j];
            }
            return z;
        }

        /** Principal logarithm; derivative dz / z. */
        Dual log() {
            double den = re * re + im * im;
            Dual z = blank(0.5 * Math.log(den), Math.atan2(im, re));
            for (int j = 0; j < NP; j++) {
                z.dre[j] = (dre[j] * re + dim[j] * im) / den;
                z.dim[j] = (dim[j] * re - dre[j] * im) / den;
            }
            return z;
        }

        /** Principal square root (non-negative real part); derivative dz / (2 sqrt z). */
        Dual sqrt() {
            double mod = Math.hypot(re, im);
            double sr = Math.sqrt(0.5 * (mod + re));
            double si = sr > 0 ? im / (2 * sr) : Math.sqrt(0.5 * (mod - re)) * Math.signum(im);
            Dual z = blank(sr, si);
            double den = 4 * (sr * sr + si * si);
            if (den > 0) {
                for (int j = 0; j < NP; j++) {
                    // dz / (2 s) = dz * conj(s) / (2 |s|^2)
                    z.dre[j] = 2 * (dre[j] * sr + dim[j] * si) / den;
                    z.dim[j] = 2 * (dim[j] * sr - dre[j] * si) / den;
                }
            }
            return z;
        }
    }
}

package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Heston (1993) stochastic-volatility pricing — the canonical answer to
 * Black-Scholes' one visible lie, the flat smile. Variance follows its
 * own mean-reverting square-root process, correlated with spot:
 *
 * <pre>  dS = (r−q)S dt + √v S dW₁
 *  dv = κ(θ − v) dt + σᵥ √v dW₂,   d⟨W₁,W₂⟩ = ρ dt</pre>
 *
 * so the model PRODUCES a smile: ρ &lt; 0 tilts it (equity skew — spot
 * down, vol up), σᵥ fattens the wings, κ/θ set how fast and where
 * variance mean-reverts.
 *
 * <p>Pricing is semi-analytic: the European call is two probabilities
 * recovered from the model's characteristic function by numerical
 * integration. This implementation uses the "little Heston trap"
 * formulation (Albrecher et al. 2007), which is numerically stable for
 * long maturities where the original 1993 branch-cut form explodes, and
 * fixed-step Simpson integration on a damped integrand — deterministic,
 * allocation-light, and accurate to well past test tolerance for
 * practical parameter ranges.</p>
 *
 * <p><b>Honesty notes:</b> the Feller condition {@code 2κθ ≥ σᵥ²} keeps
 * the variance strictly positive; parameters violating it are accepted
 * (markets calibrate there constantly) but the Monte Carlo cross-check
 * in the tests uses full-truncation Euler for exactly that reason. No
 * calibration is shipped — calibrating five parameters to a vol surface
 * is an optimization-layer exercise ({@code optimization} package tools
 * apply); this class prices given parameters. Research lane.</p>
 */
public final class Heston {

    /** Model parameters. {@code feller()} tells you which regime you are in. */
    public record Params(double kappa, double theta, double sigmaV, double rho, double v0) {

        public Params {
            if (!(kappa > 0) || !(theta > 0) || !(sigmaV > 0) || !(v0 > 0)
                    || !(rho >= -1 && rho <= 1)
                    || kappa == Double.POSITIVE_INFINITY || theta == Double.POSITIVE_INFINITY
                    || sigmaV == Double.POSITIVE_INFINITY || v0 == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "need kappa, theta, sigmaV, v0 > 0 (finite) and rho in [-1, 1]");
            }
        }

        /** The Feller ratio 2κθ/σᵥ²; ≥ 1 keeps variance strictly positive. */
        public double feller() {
            return 2 * kappa * theta / (sigmaV * sigmaV);
        }
    }

    private static final int BASE_INTEGRATION_STEPS = 4_096;   // Simpson: must be even
    // Window sized for the 20%-vol / 1y reference; the integrand's decay
    // scale is ~1/(σ_eff·√T), NOT parameter-free, so probability() stretches
    // both the window and the step count for short-dated / low-vol inputs
    // (a fixed 200 silently truncates a 1-week 4%-vol option's integral).
    private static final double BASE_INTEGRATION_LIMIT = 200;
    private static final double REFERENCE_SIGMA_SQRT_T = 0.2;
    private static final double MAX_STRETCH = 64;

    private Heston() {
    }

    private static void requireMarketInputs(double spot, double strike, double rate,
                                            double divYield, double timeYears) {
        if (!(spot > 0) || !(strike > 0) || !(timeYears > 0)
                || spot == Double.POSITIVE_INFINITY || strike == Double.POSITIVE_INFINITY
                || !Double.isFinite(rate) || !Double.isFinite(divYield)
                || timeYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("invalid market inputs");
        }
    }

    /** European call under Heston (semi-analytic). */
    public static double call(double spot, double strike, double rate, double divYield,
                              double timeYears, Params p) {
        requireMarketInputs(spot, strike, rate, divYield, timeYears);
        double forward = spot * Math.exp((rate - divYield) * timeYears);
        double df = Math.exp(-rate * timeYears);
        double p1 = probability(forward, strike, timeYears, p, true);
        double p2 = probability(forward, strike, timeYears, p, false);
        return df * (forward * p1 - strike * p2);
    }

    /** European put via put-call parity. */
    public static double put(double spot, double strike, double rate, double divYield,
                             double timeYears, Params p) {
        double forward = spot * Math.exp((rate - divYield) * timeYears);
        double df = Math.exp(-rate * timeYears);
        return call(spot, strike, rate, divYield, timeYears, p) - df * (forward - strike);
    }

    /**
     * P₁ (delta-measure) or P₂ (risk-neutral) via Simpson integration of
     * the little-trap characteristic function.
     */
    private static double probability(double forward, double strike, double t,
                                      Params p, boolean first) {
        double logMoneyness = Math.log(forward / strike);
        double sigmaEff = Math.sqrt(Math.max(p.v0(), p.theta()));
        double stretch = Math.min(MAX_STRETCH,
                Math.max(1, REFERENCE_SIGMA_SQRT_T / (sigmaEff * Math.sqrt(t))));
        // Scale the step count with the window so resolution is preserved
        // (an even multiple of an even base stays Simpson-legal).
        int steps = BASE_INTEGRATION_STEPS * (int) Math.ceil(stretch);
        double h = BASE_INTEGRATION_LIMIT * stretch / steps;
        double sum = 0;
        for (int i = 0; i <= steps; i++) {
            double u = i * h + 1e-9;       // dodge the u=0 singularity of the integrand
            double weight = (i == 0 || i == steps) ? 1 : (i % 2 == 1 ? 4 : 2);
            sum += weight * integrand(u, logMoneyness, t, p, first);
        }
        return 0.5 + (h / 3) * sum / Math.PI;
    }

    /** Re{e^{iu·x} φ(u)/(iu)} with the little-trap φ. All complex math inlined. */
    private static double integrand(double u, double x, double t, Params p, boolean first) {
        double kappa = p.kappa();
        double theta = p.theta();
        double sv = p.sigmaV();
        double rho = p.rho();
        double v0 = p.v0();
        // u' and b depend on which probability we are computing.
        double uSign = first ? 0.5 : -0.5;
        double b = first ? kappa - rho * sv : kappa;

        // d = sqrt((rho·sv·iu − b)² + sv²(iu·2·uSign·(−1)... standard form:
        // d² = (b − iρσu)² + σ²(u² − 2i·uSign·u)   (complex arithmetic below)
        double reA = b;
        double imA = -rho * sv * u;                        // (b − iρσu)
        double reA2 = reA * reA - imA * imA;
        double imA2 = 2 * reA * imA;
        double reB = sv * sv * (u * u);                    // σ²u²  (real part add)
        double imB = sv * sv * (-2 * uSign * u);           // σ²(−2i·uSign·u)
        double reD2 = reA2 + reB;
        double imD2 = imA2 + imB;
        // Complex sqrt of (reD2 + i·imD2), principal branch — STABLE form:
        // reD2 = b² + σ²u²(1−ρ²) ≥ 0 always, so the real part is the large
        // component; the imaginary part MUST come from imD2/(2·reD), not
        // from sqrt((mod − re)/2), which underflows to 0 when |imD2| ≪ reD2
        // and silently zeroes the phase slope near u = 0 (a first-node
        // corruption worth ~0.5% of an ATM price — caught by the BS-limit
        // test).
        double modD2 = Math.hypot(reD2, imD2);
        double reD = Math.sqrt((modD2 + reD2) / 2);
        double imD = imD2 / (2 * reD);

        // g₂ = (b − iρσu − d)/(b − iρσu + d)   — the LITTLE-TRAP ratio
        double reNum = reA - reD;
        double imNum = imA - imD;
        double reDen = reA + reD;
        double imDen = imA + imD;
        double den2 = reDen * reDen + imDen * imDen;
        double reG = (reNum * reDen + imNum * imDen) / den2;
        double imG = (imNum * reDen - reNum * imDen) / den2;

        // e^{−d·t}
        double expRe = Math.exp(-reD * t);
        double reEdt = expRe * Math.cos(-imD * t);
        double imEdt = expRe * Math.sin(-imD * t);

        // 1 − g·e^{−dt}  and  1 − g
        double reOneMinusGe = 1 - (reG * reEdt - imG * imEdt);
        double imOneMinusGe = -(reG * imEdt + imG * reEdt);
        double reOneMinusG = 1 - reG;
        double imOneMinusG = -imG;

        // C = (κθ/σ²)·[(b − iρσu − d)t − 2·ln((1 − g·e^{−dt})/(1 − g))]
        double ratioRe;
        double ratioIm;
        {
            double d2 = reOneMinusG * reOneMinusG + imOneMinusG * imOneMinusG;
            ratioRe = (reOneMinusGe * reOneMinusG + imOneMinusGe * imOneMinusG) / d2;
            ratioIm = (imOneMinusGe * reOneMinusG - reOneMinusGe * imOneMinusG) / d2;
        }
        double logModRatio = 0.5 * Math.log(ratioRe * ratioRe + ratioIm * ratioIm);
        // Principal-branch arg is valid ONLY for the little-trap ratio with
        // Re(d) >= 0 (Lord & Kahl: its winding number is zero for all t) —
        // the 1993 g or a flipped d branch would need phase tracking here.
        double argRatio = Math.atan2(ratioIm, ratioRe);
        double coef = kappa * theta / (sv * sv);
        double reC = coef * ((reA - reD) * t - 2 * logModRatio);
        double imC = coef * ((imA - imD) * t - 2 * argRatio);

        // D = ((b − iρσu − d)/σ²) · (1 − e^{−dt})/(1 − g·e^{−dt})
        double reOneMinusEdt = 1 - reEdt;
        double imOneMinusEdt = -imEdt;
        double reFrac;
        double imFrac;
        {
            double d2 = reOneMinusGe * reOneMinusGe + imOneMinusGe * imOneMinusGe;
            reFrac = (reOneMinusEdt * reOneMinusGe + imOneMinusEdt * imOneMinusGe) / d2;
            imFrac = (imOneMinusEdt * reOneMinusGe - reOneMinusEdt * imOneMinusGe) / d2;
        }
        double reDD = ((reA - reD) * reFrac - (imA - imD) * imFrac) / (sv * sv);
        double imDD = ((reA - reD) * imFrac + (imA - imD) * reFrac) / (sv * sv);

        // φ = exp(C + D·v0 + iu·x)   (forward-measure form: no drift term)
        double reExp = reC + reDD * v0;
        double imExp = imC + imDD * v0 + u * x;
        double mod = Math.exp(reExp);
        // integrand = Re{ φ / (iu) } = Re{ φ·(−i)/u } = (mod/u)·sin(imExp)... careful:
        // φ/(iu) = φ·(−i/u); Re{(a+bi)(−i/u)} = b/u.
        return mod * Math.sin(imExp) / u;
    }

    /**
     * Full-truncation Euler Monte Carlo — the pricing cross-check (used
     * by the tests to validate the semi-analytic integral, and usable
     * for payoffs the closed form cannot reach). Deterministic per seed.
     */
    public static double callMonteCarlo(double spot, double strike, double rate,
                                        double divYield, double timeYears, Params p,
                                        int steps, int paths, long seed) {
        requireMarketInputs(spot, strike, rate, divYield, timeYears);
        if (steps < 1 || paths < 1) {
            throw new IllegalArgumentException("need steps >= 1 and paths >= 1");
        }
        java.util.Random rnd = new java.util.Random(seed);
        double dt = timeYears / steps;
        double sqrtDt = Math.sqrt(dt);
        double rhoBar = Math.sqrt(1 - p.rho() * p.rho());
        double drift = (rate - divYield) * dt;
        double sum = 0;
        for (int path = 0; path < paths; path++) {
            double logS = Math.log(spot);
            double v = p.v0();
            for (int i = 0; i < steps; i++) {
                double vPlus = Math.max(v, 0);             // full truncation
                double z1 = rnd.nextGaussian();
                double z2 = p.rho() * z1 + rhoBar * rnd.nextGaussian();
                logS += drift - 0.5 * vPlus * dt + Math.sqrt(vPlus) * sqrtDt * z1;
                v += p.kappa() * (p.theta() - vPlus) * dt
                        + p.sigmaV() * Math.sqrt(vPlus) * sqrtDt * z2;
            }
            sum += Math.max(Math.exp(logS) - strike, 0);
        }
        return Math.exp(-rate * timeYears) * sum / paths;
    }
}

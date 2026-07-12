package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;

/**
 * CDS pricing off a {@link CreditCurve}: the two legs, the par spread,
 * and the upfront that post-2009 standardized contracts actually
 * exchange.
 *
 * <p>A credit default swap is insurance with a running premium: the
 * protection BUYER pays {@code spread} per year (quarterly, accruing to
 * the default date) and receives {@code 1 - R} of notional if the name
 * defaults before maturity. The pricing identities, per unit notional:</p>
 *
 * <pre>
 *   riskyAnnuity  = sum dt * DF(t_i) * Q(t_i)  +  accrual-on-default term
 *   premiumLegPv  = spread * riskyAnnuity
 *   protectionPv  = (1 - R) * sum DF(t_i) * (Q(t_{i-1}) - Q(t_i))
 *   parSpread     = protectionPv / riskyAnnuity     (zero-upfront spread)
 *   upfront(S_c)  = protectionPv - S_c * riskyAnnuity
 * </pre>
 *
 * <p>Positive upfront means the protection buyer pays points up front
 * (the contract's fixed coupon {@code S_c} is too small for the risk —
 * the standard 100bp coupon on a 300bp name). The risky annuity is also
 * the desk's "risky DV01": the PnL per 1bp of spread move, which is why
 * it gets its own accessor rather than living inside the leg. Same
 * quarterly discretization as the bootstrap, stated there. Research
 * lane, deterministic.</p>
 */
public final class CdsPricer {

    private CdsPricer() {
    }

    /** PV of 1bp-per-year premium stream per unit spread (the risky annuity / risky DV01 base). */
    public static double riskyAnnuity(CreditCurve credit, YieldCurve discount, double maturityYears) {
        validate(maturityYears);
        double dt = CreditCurve.gridStep();
        double annuity = 0;
        double prevQ = 1;
        for (double t = dt; t <= maturityYears + 1e-12; t += dt) {
            double q = credit.survivalProbability(t);
            double df = discount.discountFactor(t);
            // Full period if it survives; half a period of accrual if it
            // defaults inside the period (the standard convention).
            annuity += dt * df * q + 0.5 * dt * df * (prevQ - q);
            prevQ = q;
        }
        return annuity;
    }

    /** PV of the premium leg at the given running spread. */
    public static double premiumLegPv(CreditCurve credit, YieldCurve discount,
                                      double spread, double maturityYears) {
        if (!(spread > 0) || spread == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spread must be positive and finite, got " + spread);
        }
        return spread * riskyAnnuity(credit, discount, maturityYears);
    }

    /** PV of the protection leg: (1-R) paid at default. */
    public static double protectionLegPv(CreditCurve credit, YieldCurve discount,
                                         double maturityYears) {
        validate(maturityYears);
        double dt = CreditCurve.gridStep();
        double pv = 0;
        double prevQ = 1;
        for (double t = dt; t <= maturityYears + 1e-12; t += dt) {
            double q = credit.survivalProbability(t);
            pv += discount.discountFactor(t) * (prevQ - q);
            prevQ = q;
        }
        return (1 - credit.recovery()) * pv;
    }

    /** The zero-upfront (par) spread for this maturity. */
    public static double parSpread(CreditCurve credit, YieldCurve discount, double maturityYears) {
        return protectionLegPv(credit, discount, maturityYears)
                / riskyAnnuity(credit, discount, maturityYears);
    }

    /**
     * Upfront points (per unit notional) the protection BUYER pays on a
     * contract with fixed running coupon {@code contractSpread}.
     */
    public static double upfront(CreditCurve credit, YieldCurve discount,
                                 double contractSpread, double maturityYears) {
        if (!(contractSpread > 0) || contractSpread == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "contractSpread must be positive and finite, got " + contractSpread);
        }
        return protectionLegPv(credit, discount, maturityYears)
                - contractSpread * riskyAnnuity(credit, discount, maturityYears);
    }

    private static void validate(double maturityYears) {
        if (!(maturityYears > 0) || maturityYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "maturityYears must be positive and finite, got " + maturityYears);
        }
        // The legs are summed on the quarterly grid starting at gridStep();
        // a maturity below one grid step has NO coupon dates, so the annuity
        // and protection leg are both empty -- parSpread would be 0/0 = NaN.
        // Throw instead of leaking NaN (house rule).
        if (maturityYears < CreditCurve.gridStep()) {
            throw new IllegalArgumentException(
                    "maturityYears must be >= the pricing grid step "
                            + CreditCurve.gridStep() + ", got " + maturityYears);
        }
    }
}

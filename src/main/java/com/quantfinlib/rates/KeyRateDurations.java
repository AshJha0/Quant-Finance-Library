package com.quantfinlib.rates;

/**
 * Key-rate durations — WHERE on the curve a bond's rate risk lives.
 * {@code BondPricer.dv01} answers "what does one parallel basis point
 * cost?"; a curve rarely moves in parallel, so risk desks slice that
 * DV01 across the curve's tenors: bump ONE node, hold the rest, reprice.
 * The vector of per-node sensitivities is the hedging recipe (each KRD
 * is offset with the instrument that drives that node), and its sum
 * recovers the parallel duration — a consistency check the tests pin.
 *
 * <p>Mechanics: for each curve tenor, the zero rate at that node is
 * bumped ±1bp (all other nodes fixed — the curve's own interpolation
 * spreads the bump between neighbors, which IS the standard convention),
 * the bond is repriced off each bumped curve, and the central difference
 * gives the sensitivity. Prices per 100 face follow the
 * {@code BondPricer} convention. Research lane; deterministic.</p>
 */
public final class KeyRateDurations {

    private static final double BUMP = 1e-4;              // one basis point

    private KeyRateDurations() {
    }

    /**
     * Per-node price sensitivities of a fixed-coupon bond to a 1bp bump
     * of each curve tenor, in price units per 100 face (positive = the
     * bond LOSES that much when the node rises 1bp — DV01 sign
     * convention). Index i corresponds to {@code tenors()[i]} ascending.
     */
    public static double[] keyRateDv01s(double face, double couponRate, int frequency,
                                        double maturityYears, YieldCurve curve) {
        double[] tenors = curve.tenors().stream().mapToDouble(Double::doubleValue).toArray();
        double[] baseRates = new double[tenors.length];
        for (int i = 0; i < tenors.length; i++) {
            baseRates[i] = curve.zeroRate(tenors[i]);
        }
        double[] krd = new double[tenors.length];
        double[] bumped = baseRates.clone();
        for (int i = 0; i < tenors.length; i++) {
            bumped[i] = baseRates[i] + BUMP;
            double up = BondPricer.priceFromCurve(face, couponRate, frequency,
                    maturityYears, YieldCurve.ofZeroRates(tenors, bumped));
            bumped[i] = baseRates[i] - BUMP;
            double down = BondPricer.priceFromCurve(face, couponRate, frequency,
                    maturityYears, YieldCurve.ofZeroRates(tenors, bumped));
            bumped[i] = baseRates[i];
            krd[i] = (down - up) / 2;      // positive when rates-up hurts
        }
        return krd;
    }

    /**
     * The parallel DV01 off the curve (every node bumped together) — the
     * number the key-rate slices must add back up to, within the
     * curve-interpolation tolerance the tests document.
     */
    public static double parallelDv01(double face, double couponRate, int frequency,
                                      double maturityYears, YieldCurve curve) {
        double[] tenors = curve.tenors().stream().mapToDouble(Double::doubleValue).toArray();
        double[] up = new double[tenors.length];
        double[] down = new double[tenors.length];
        for (int i = 0; i < tenors.length; i++) {
            double base = curve.zeroRate(tenors[i]);
            up[i] = base + BUMP;
            down[i] = base - BUMP;
        }
        double priceUp = BondPricer.priceFromCurve(face, couponRate, frequency,
                maturityYears, YieldCurve.ofZeroRates(tenors, up));
        double priceDown = BondPricer.priceFromCurve(face, couponRate, frequency,
                maturityYears, YieldCurve.ofZeroRates(tenors, down));
        return (priceDown - priceUp) / 2;
    }
}

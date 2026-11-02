package com.quantfinlib.risk.simm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Initial margin following the published structure of the ISDA SIMM
 * methodology.
 *
 * <p><b>Licensing.</b> ISDA SIMM is licensed by ISDA for production use.
 * This class is an independent, uncertified implementation of the publicly
 * described aggregation structure. With {@link SimmParameters#illustrative()}
 * it produces illustrative numbers only.</p>
 *
 * <p><b>Plain idea.</b> Each sensitivity is turned into a weighted
 * sensitivity (risk weight times amount, scaled up if a qualifier is
 * concentrated). Weighted sensitivities are combined with correlations
 * inside a bucket, buckets are combined inside a risk class, risk classes
 * inside a product class, and the product classes are added.</p>
 *
 * <p><b>Formal statement.</b> For delta (and vega, with vega weights and
 * thresholds) in bucket {@code b}:</p>
 * <pre>
 *   CR_q  = max(1, sqrt(|sum_{k in q} s_k| / T_b))        per qualifier q
 *   WS_k  = RW_k * s_k * CR_q(k)
 *   K_b   = sqrt( sum_k sum_l rho_kl f_kl WS_k WS_l ),   rho_kk = f_kk = 1
 *   f_kl  = min(CR_k, CR_l) / max(CR_k, CR_l)
 *   S_b   = max(min(sum_k WS_k, K_b), -K_b)
 *   Margin = sqrt( sum_b K_b^2 + sum_{b != c} gamma_bc g_bc S_b S_c ) + K_Residual
 * </pre>
 * <p>For IR, {@code rho_kl} is the tenor correlation times {@code phi} when
 * the sub-curves differ ({@code IR.rho.inflation} when exactly one side is
 * inflation), and {@code g_bc = min(CR_b, CR_c) / max(CR_b, CR_c)} with
 * {@code CR_b} the currency's concentration factor; for other classes
 * {@code rho_kl} is the same-qualifier or different-qualifier value and
 * {@code g_bc = 1}.</p>
 * <p>Curvature uses the vega rows: {@code CVR_k = SF(t_k) s_k} with
 * {@code SF(t) = 0.5 min(1, 14 days / t)}; {@code K_b} as above with
 * {@code rho^2} and no concentration; {@code theta = min(sum CVR / sum |CVR|, 0)},
 * {@code lambda = (z^2 - 1)(1 + theta) - theta} with {@code z = Phi^-1(0.995)};
 * {@code Curvature = m * max(sum CVR + lambda sqrt(sum K_b^2 + sum gamma^2 S_b S_c), 0)},
 * computed separately for the residual bucket and added, {@code m} the
 * curvature multiplier.</p>
 * <p>Base correlation (Credit Qualifying): {@code WS_q = RW_BC s_q} per
 * qualifier, {@code K = sqrt(sum sum rho_BC WS WS)}.</p>
 * <p>{@code IM_r = Delta + Vega + Curvature + BaseCorr};
 * {@code SIMM_p = sqrt(sum IM_r^2 + sum_{r != s} psi_rs IM_r IM_s)};
 * {@code SIMM = sum_p SIMM_p}.</p>
 *
 * <p>Structure checked against the public ISDA SIMM v2.9+2606 methodology
 * (formulas only; see {@code docs/SIMM.md} for the section map):
 * IR concentration is per currency, excludes cross-currency basis and has
 * {@code f_kl = 1}; equity, commodity and FX vega use
 * {@code sigma_k = RW_k sqrt(365/14) / Phi^-1(0.99)} and the class HVR, with
 * the qualifier as risk factor (expiries summed); IR curvature is scaled by
 * {@code HVR_IR^-2}; FX delta weights, correlations and thresholds can
 * depend on volatility group, calculation currency and category; add-ons
 * via {@link #calculateWithAddOns}.</p>
 *
 * <p><b>Limits</b>: correlations within a non-IR bucket depend only on
 * whether the qualifier matches (or the FX groups); index-volatility
 * special cases beyond the bucket keys are left to the caller's bucketing.
 * Not checked against ISDA's unit tests, which are licensed.</p>
 *
 * <p><b>Worked example</b> (own hand computation, see the tests): one IR
 * delta of 1,000 at 5y with risk weight 50 and no concentration gives
 * {@code WS = 50,000}, {@code K = |WS| = 50,000}, and total SIMM 50,000.</p>
 */
public final class SimmCalculator {

    /** {@code Phi^-1(0.995)}. */
    static final double Z995 = 2.5758293035489004;

    private final SimmParameters params;

    /**
     * @param params calibration to use
     */
    public SimmCalculator(SimmParameters params) {
        this.params = Objects.requireNonNull(params, "params");
    }

    /**
     * Computes SIMM for a portfolio of CRIF rows. Rows with the same
     * product class, risk type, qualifier, bucket and labels are netted.
     *
     * @param crif the sensitivities
     * @return total and breakdown
     */
    public SimmResult calculate(Collection<CrifSensitivity> crif) {
        Map<SimmProductClass, Map<SimmRiskClass, List<CrifSensitivity>>> grouped =
                new EnumMap<>(SimmProductClass.class);
        for (CrifSensitivity s : crif) {
            grouped.computeIfAbsent(s.productClass(), k -> new EnumMap<>(SimmRiskClass.class))
                    .computeIfAbsent(s.riskClass(), k -> new ArrayList<>()).add(s);
        }
        Map<SimmProductClass, SimmResult.ProductClassMargin> out = new EnumMap<>(SimmProductClass.class);
        double total = 0;
        for (var pe : grouped.entrySet()) {
            Map<SimmRiskClass, SimmResult.RiskClassMargin> rcm = new EnumMap<>(SimmRiskClass.class);
            for (var re : pe.getValue().entrySet()) {
                rcm.put(re.getKey(), riskClassMargin(re.getKey(), re.getValue()));
            }
            double m = productClassMargin(rcm);
            out.put(pe.getKey(), new SimmResult.ProductClassMargin(pe.getKey(), m, rcm));
            total += m;
        }
        return new SimmResult(total, out, params.label());
    }

    /**
     * Margin of one risk class from its rows (any product class).
     *
     * @param rc   the risk class
     * @param rows rows of that risk class
     * @return delta, vega, curvature and base-correlation margins
     */
    public SimmResult.RiskClassMargin riskClassMargin(SimmRiskClass rc, Collection<CrifSensitivity> rows) {
        List<Net> delta = new ArrayList<>();
        List<Net> vega = new ArrayList<>();
        List<Net> bc = new ArrayList<>();
        for (Net n : net(rows)) {
            switch (n.measure) {
                case DELTA -> delta.add(n);
                case VEGA -> vega.add(n);
                case BASE_CORRELATION -> bc.add(n);
                default -> throw new IllegalStateException();
            }
        }
        return new SimmResult.RiskClassMargin(rc,
                weightedMargin(rc, delta, "delta"),
                weightedMargin(rc, vega, "vega"),
                curvatureMargin(rc, vega),
                baseCorrMargin(bc));
    }

    private double productClassMargin(Map<SimmRiskClass, SimmResult.RiskClassMargin> rcm) {
        List<SimmRiskClass> keys = new ArrayList<>(rcm.keySet());
        double v = 0;
        for (SimmRiskClass r : keys) {
            for (SimmRiskClass s : keys) {
                double c = r == s ? 1.0 : params.first(
                        "psi." + r.key() + "." + s.key(), "psi." + s.key() + "." + r.key(), "psi");
                v += c * rcm.get(r).total() * rcm.get(s).total();
            }
        }
        return Math.sqrt(Math.max(0, v));
    }

    // ---------------------------------------------------------------- delta / vega

    /** {@code Phi^-1(0.99)}, used in the equity, commodity and FX vega volatility. */
    static final double Z99 = 2.3263478740408408;

    private static boolean volWeightedClass(SimmRiskClass rc) {
        return rc == SimmRiskClass.EQUITY || rc == SimmRiskClass.COMMODITY || rc == SimmRiskClass.FX;
    }

    private double opt(double dflt, String... keys) {
        Double v = params.firstOrNull(keys);
        return v == null ? dflt : v;
    }

    private boolean flag(String key) {
        Double v = params.firstOrNull(key);
        return v != null && v != 0;
    }

    /**
     * Turns vega rows into vega (or curvature) risk factors. For equity,
     * commodity and FX the risk factor is the qualifier: the CRIF amount (the
     * vega dV/dsigma) is multiplied by
     * {@code sigma_k = RW_k sqrt(365/14) / Phi^-1(0.99)} and, for the vega
     * margin only, by the class HVR; rows of the same qualifier are then
     * summed across expiries (each first multiplied by {@code SF(t)} for
     * curvature). For IR and credit each expiry stays its own risk factor.
     */
    private List<Net> vegaRiskFactors(SimmRiskClass rc, List<Net> rows, boolean curvature) {
        if (!volWeightedClass(rc)) {
            List<Net> out = new ArrayList<>();
            for (Net n : rows) {
                double a = curvature ? scaling(expiry(n)) * n.amount : n.amount;
                out.add(new Net(n.measure, n.bucket, n.qualifier, n.label1, n.label2, a));
            }
            return out;
        }
        boolean weighted = flag(rc.key() + ".vega.amountIsVolWeighted");
        double hvr = curvature ? 1.0 : opt(1.0, rc.key() + ".hvr");
        Map<String, Net> m = new LinkedHashMap<>();
        for (Net n : rows) {
            double sigma = weighted ? 1.0 : vegaSigma(rc, n);
            double a = n.amount * sigma * hvr * (curvature ? scaling(expiry(n)) : 1.0);
            String k = n.bucket + "\u0000" + n.qualifier;
            Net old = m.get(k);
            m.put(k, new Net(n.measure, n.bucket, n.qualifier, "", "", (old == null ? 0 : old.amount) + a));
        }
        return new ArrayList<>(m.values());
    }

    private static double expiry(Net n) {
        if (n.label1.isEmpty()) {
            throw new IllegalArgumentException("vega row needs label1 expiry for curvature: " + n.qualifier);
        }
        return SimmParameters.tenorYears(n.label1);
    }

    private double vegaSigma(SimmRiskClass rc, Net n) {
        double rw;
        if (rc == SimmRiskClass.FX) {
            String[] pair = fxPair(n.qualifier);
            rw = opt(Double.NaN, "FX.delta.rw." + fxGroup(pair[0]) + "." + fxGroup(pair[1]));
            if (Double.isNaN(rw)) {
                rw = params.first("FX.delta.rw");
            }
        } else {
            rw = params.first(rc.key() + ".delta.rw." + n.bucket, rc.key() + ".delta.rw");
        }
        return rw * Math.sqrt(365.0 / 14.0) / Z99;
    }

    private static String[] fxPair(String q) {
        String s = q.replace("/", "").trim();
        if (s.length() != 6) {
            throw new IllegalArgumentException("FX vega qualifier must be a currency pair such as EURUSD: " + q);
        }
        return new String[]{s.substring(0, 3), s.substring(3)};
    }

    /** FX volatility group of a currency: {@code High} if flagged, else {@code Regular}. */
    private String fxGroup(String ccy) {
        return flag("FX.highVolGroup." + ccy) ? "High" : "Regular";
    }

    /** FX concentration category of a currency (default 3, the catch-all). */
    private int fxCategory(String ccy) {
        return (int) opt(3, "FX.category." + ccy);
    }

    private String fxCalcCurrency() {
        for (var e : params.values().entrySet()) {
            if (e.getKey().startsWith("FX.calculationCurrency.") && e.getValue() != 0) {
                return e.getKey().substring("FX.calculationCurrency.".length());
            }
        }
        return null;
    }

    private Double threshold(SimmRiskClass rc, String kind, String b, String qualifier) {
        if (rc == SimmRiskClass.FX) {
            Double t = null;
            if ("delta".equals(kind)) {
                t = params.firstOrNull("FX.delta.threshold.cat" + fxCategory(qualifier));
            } else if (qualifier.replace("/", "").length() == 6) {
                String[] p = fxPair(qualifier);
                int i = Math.min(fxCategory(p[0]), fxCategory(p[1]));
                int j = Math.max(fxCategory(p[0]), fxCategory(p[1]));
                t = params.firstOrNull("FX.vega.threshold.cat" + i + ".cat" + j);
            }
            if (t != null) {
                return t;
            }
        }
        return params.firstOrNull(rc.key() + "." + kind + ".threshold." + b, rc.key() + "." + kind + ".threshold");
    }

    private static boolean isXccy(Net n) {
        return "XCcy".equalsIgnoreCase(n.label2);
    }

    private double weightedMargin(SimmRiskClass rc, List<Net> input, String kind) {
        if (input.isEmpty()) {
            return 0;
        }
        boolean ir = rc == SimmRiskClass.INTEREST_RATE;
        List<Net> rows = "vega".equals(kind) ? vegaRiskFactors(rc, input, false) : input;
        Map<String, List<Net>> byBucket = byBucket(rows);
        Map<String, double[]> ks = new LinkedHashMap<>();   // bucket -> {K, S, CRbucket}
        double residual = 0;
        for (var e : byBucket.entrySet()) {
            String b = e.getKey();
            List<Net> ns = e.getValue();
            Map<String, Double> qSum = new TreeMap<>();
            double bucketSum = 0;
            for (Net n : ns) {
                if (ir && isXccy(n)) {
                    continue;   // cross-currency basis is outside IR concentration
                }
                qSum.merge(n.qualifier, n.amount, Double::sum);
                bucketSum += n.amount;
            }
            double crBucket = ir ? concentration(bucketSum, threshold(rc, kind, b, b)) : 1.0;
            int m = ns.size();
            double[] ws = new double[m];
            double[] cr = new double[m];
            double sumWs = 0;
            for (int i = 0; i < m; i++) {
                Net n = ns.get(i);
                if (ir) {
                    cr[i] = isXccy(n) ? 1.0 : crBucket;
                } else {
                    cr[i] = concentration(qSum.get(n.qualifier), threshold(rc, kind, b, n.qualifier));
                }
                double rw = "delta".equals(kind) ? deltaRw(rc, b, n)
                        : params.first(rc.key() + ".vega.rw." + b, rc.key() + ".vega.rw");
                ws[i] = rw * n.amount * cr[i];
                sumWs += ws[i];
            }
            double k2 = 0;
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    if (i == j) {
                        k2 += ws[i] * ws[i];
                    } else {
                        // f_kl is identically 1 in the IR class
                        double f = ir ? 1.0 : Math.min(cr[i], cr[j]) / Math.max(cr[i], cr[j]);
                        k2 += intraRho(rc, b, ns.get(i), ns.get(j), kind) * f * ws[i] * ws[j];
                    }
                }
            }
            double k = Math.sqrt(Math.max(0, k2));
            if (SimmParameters.RESIDUAL.equals(b)) {
                residual += k;
            } else {
                double s = Math.max(Math.min(sumWs, k), -k);
                ks.put(b, new double[]{k, s, crBucket});
            }
        }
        return Math.sqrt(Math.max(0, acrossBuckets(rc, ks, false))) + residual;
    }

    private double deltaRw(SimmRiskClass rc, String b, Net n) {
        if (rc == SimmRiskClass.FX) {
            String calc = fxCalcCurrency();
            if (calc != null && calc.equals(n.qualifier)) {
                return 0.0;   // no FX risk factor for the calculation currency
            }
            if (calc != null) {
                Double rw = params.firstOrNull("FX.delta.rw." + fxGroup(n.qualifier) + "." + fxGroup(calc));
                if (rw != null) {
                    return rw;
                }
            }
        }
        return params.first(
                key(rc, "delta.rw", b, n.label1),
                n.label2.isEmpty() ? null : rc.key() + ".delta.rw." + n.label2,
                rc.key() + ".delta.rw." + b,
                n.label1.isEmpty() ? null : rc.key() + ".delta.rw." + n.label1,
                rc.key() + ".delta.rw");
    }

    private static String key(SimmRiskClass rc, String what, String b, String l1) {
        return l1.isEmpty() ? null : rc.key() + "." + what + "." + b + "." + l1;
    }

    private static double concentration(double sum, Double threshold) {
        if (threshold == null || threshold <= 0) {
            return 1.0;
        }
        return Math.max(1.0, Math.sqrt(Math.abs(sum) / threshold));
    }

    private double acrossBuckets(SimmRiskClass rc, Map<String, double[]> ks, boolean squaredGamma) {
        List<String> bs = new ArrayList<>(ks.keySet());
        double v = 0;
        for (String b : bs) {
            double[] x = ks.get(b);
            v += x[0] * x[0];
            for (String c : bs) {
                if (b.equals(c)) {
                    continue;
                }
                double[] y = ks.get(c);
                double g = params.first(rc.key() + ".gamma." + b + "." + c,
                        rc.key() + ".gamma." + c + "." + b, rc.key() + ".gamma");
                if (squaredGamma) {
                    g = g * g;
                } else if (rc == SimmRiskClass.INTEREST_RATE) {
                    g *= Math.min(x[2], y[2]) / Math.max(x[2], y[2]);
                }
                v += g * x[1] * y[1];
            }
        }
        return v;
    }

    private double irTenorRho(String t1, String t2) {
        if (t1.equals(t2)) {
            return 1.0;
        }
        return params.first("IR.tenorRho." + t1 + "." + t2, "IR.tenorRho." + t2 + "." + t1, "IR.tenorRho");
    }

    private double intraRho(SimmRiskClass rc, String bucket, Net a, Net b, String kind) {
        if (rc == SimmRiskClass.INTEREST_RATE) {
            boolean xa = isXccy(a);
            boolean xb = isXccy(b);
            if (xa || xb) {
                return xa && xb ? 1.0 : params.first("IR.rho.xccy");
            }
            boolean ia = "Inflation".equalsIgnoreCase(a.label2);
            boolean ib = "Inflation".equalsIgnoreCase(b.label2);
            if (ia != ib) {
                return params.first("IR.rho.inflation");
            }
            if (ia) {
                // flat inflation delta nets fully; inflation vega carries expiries
                return irTenorRho(a.label1, b.label1);
            }
            double rho = irTenorRho(a.label1, b.label1);
            return a.label2.equals(b.label2) ? rho : rho * params.first("IR.phi");
        }
        if (rc == SimmRiskClass.FX) {
            Double v = null;
            if (!"delta".equals(kind)) {
                v = params.firstOrNull("FX.rho.vega");
            } else {
                String calc = fxCalcCurrency();
                if (calc != null) {
                    String g = "FX.rho." + fxGroup(calc) + ".";
                    v = params.firstOrNull(g + fxGroup(a.qualifier) + "." + fxGroup(b.qualifier),
                            g + fxGroup(b.qualifier) + "." + fxGroup(a.qualifier));
                }
            }
            if (v != null) {
                return v;
            }
        }
        String w = a.qualifier.equals(b.qualifier) ? "sameQualifier" : "diffQualifier";
        return params.first(rc.key() + ".rho." + w + "." + bucket, rc.key() + ".rho." + w);
    }

    // ---------------------------------------------------------------- curvature

    private double curvatureMargin(SimmRiskClass rc, List<Net> vega) {
        if (vega.isEmpty()) {
            return 0;
        }
        Map<String, List<Net>> byBucket = byBucket(vegaRiskFactors(rc, vega, true));
        Map<String, double[]> ks = new LinkedHashMap<>();
        double[] main = new double[2];   // sumCVR, sumAbs
        double[] res = new double[2];
        double resK = 0;
        for (var e : byBucket.entrySet()) {
            String b = e.getKey();
            if (flag(rc.key() + ".curvature.zeroBucket." + b)) {
                continue;   // e.g. the equity volatility-index bucket
            }
            List<Net> ns = e.getValue();
            int m = ns.size();
            double sum = 0;
            double abs = 0;
            for (Net n : ns) {
                sum += n.amount;
                abs += Math.abs(n.amount);
            }
            double k2 = 0;
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    double r = i == j ? 1.0 : intraRho(rc, b, ns.get(i), ns.get(j), "curvature");
                    k2 += r * r * ns.get(i).amount * ns.get(j).amount;
                }
            }
            double k = Math.sqrt(Math.max(0, k2));
            if (SimmParameters.RESIDUAL.equals(b)) {
                res[0] += sum;
                res[1] += abs;
                resK = k;
            } else {
                main[0] += sum;
                main[1] += abs;
                ks.put(b, new double[]{k, Math.max(Math.min(sum, k), -k), 1.0});
            }
        }
        double mult = opt(1.0, rc.key() + ".curvature.multiplier");
        if (rc == SimmRiskClass.INTEREST_RATE) {
            double hvr = opt(1.0, "IR.hvr");
            mult /= hvr * hvr;
        }
        double c = curvatureFormula(main[0], main[1], Math.sqrt(Math.max(0, acrossBuckets(rc, ks, true))))
                + curvatureFormula(res[0], res[1], resK);
        return mult * c;
    }

    /**
     * {@code max(sumCvr + lambda * root, 0)} with
     * {@code theta = min(sumCvr / sumAbs, 0)} and
     * {@code lambda = (z^2 - 1)(1 + theta) - theta}.
     */
    static double curvatureFormula(double sumCvr, double sumAbs, double root) {
        if (sumAbs == 0) {
            return 0;
        }
        double theta = Math.min(sumCvr / sumAbs, 0);
        double lambda = (Z995 * Z995 - 1) * (1 + theta) - theta;
        return Math.max(sumCvr + lambda * root, 0);
    }

    /**
     * SIMM curvature scaling {@code SF(t) = 0.5 min(1, 14 / days)}, with
     * days = 365 t.
     *
     * @param years option expiry in years
     * @return the scaling
     */
    public static double scaling(double years) {
        double days = years * 365.0;
        return 0.5 * Math.min(1.0, 14.0 / days);
    }

    // ---------------------------------------------------------------- add-ons

    /**
     * SIMM plus the standardised additional initial margin of the
     * methodology's section L: {@code AddOnIM + sum_p (MS_p - 1) SIMM_p},
     * with {@code AddOnIM = addon.fixed + sum_prod addon.factor.<prod> * notional_prod}
     * and {@code MS_p = multiplier.<ProductClass>} (default 1, at least 1).
     *
     * @param crif      the sensitivities
     * @param notionals total absolute notional per add-on product name (may be empty)
     * @return SIMM, the additional margin and their sum
     * @throws IllegalArgumentException if a notional has no factor, or a multiplier is below 1
     */
    public SimmAddOnResult calculateWithAddOns(Collection<CrifSensitivity> crif, Map<String, Double> notionals) {
        SimmResult simm = calculate(crif);
        double add = opt(0.0, "addon.fixed");
        for (var e : notionals.entrySet()) {
            double factor = params.first("addon.factor." + e.getKey());
            add += factor * Math.abs(e.getValue());
        }
        for (var e : simm.productClasses().entrySet()) {
            double ms = opt(1.0, "multiplier." + e.getKey().crifName());
            if (ms < 1.0) {
                throw new IllegalArgumentException("product-class multiplier must be at least 1: " + ms);
            }
            add += (ms - 1.0) * e.getValue().margin();
        }
        return new SimmAddOnResult(simm, add, simm.total() + add);
    }

    // ---------------------------------------------------------------- base correlation

    private double baseCorrMargin(List<Net> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, Double> q = new TreeMap<>();
        for (Net n : rows) {
            q.merge(n.qualifier, n.amount, Double::sum);
        }
        double rho = params.first("CreditQ.baseCorr.rho");
        List<Double> ws = new ArrayList<>();
        q.forEach((fam, s) -> ws.add(params.first("CreditQ.baseCorr.rw." + fam, "CreditQ.baseCorr.rw") * s));
        double v = 0;
        for (int i = 0; i < ws.size(); i++) {
            for (int j = 0; j < ws.size(); j++) {
                v += (i == j ? 1.0 : rho) * ws.get(i) * ws.get(j);
            }
        }
        return Math.sqrt(Math.max(0, v));
    }

    // ---------------------------------------------------------------- netting

    private record Net(SimmRiskMeasure measure, String bucket, String qualifier,
                       String label1, String label2, double amount) {
    }

    private static List<Net> net(Collection<CrifSensitivity> rows) {
        Map<String, Net> m = new LinkedHashMap<>();
        for (CrifSensitivity s : rows) {
            String b = s.effectiveBucket();
            String k = s.measure() + "\u0000" + b + "\u0000" + s.qualifier() + "\u0000"
                    + s.label1() + "\u0000" + s.label2();
            Net old = m.get(k);
            double a = (old == null ? 0 : old.amount) + s.amount();
            m.put(k, new Net(s.measure(), b, s.qualifier(), s.label1(), s.label2(), a));
        }
        return new ArrayList<>(m.values());
    }

    private static Map<String, List<Net>> byBucket(List<Net> rows) {
        Map<String, List<Net>> m = new LinkedHashMap<>();
        for (Net n : rows) {
            m.computeIfAbsent(n.bucket, k -> new ArrayList<>()).add(n);
        }
        return m;
    }
}

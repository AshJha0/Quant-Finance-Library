package com.quantfinlib.risk;

import com.quantfinlib.stats.SpecialFunctions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standardised approach for counterparty credit risk, SA-CCR (Basel
 * Committee, "The standardised approach for measuring counterparty
 * credit risk exposures", BCBS 279, March 2014, Annex 4). Exposure at
 * default of one netting set:
 *
 * <pre>
 *   EAD = alpha * (RC + PFE),  alpha = 1.4                      (para 128)
 *   RC  = max(V - C, 0)                          unmargined     (para 136)
 *   RC  = max(V - C, TH + MTA - NICA, 0)         margined       (para 144)
 *   PFE = multiplier * AddOn_aggregate,  AddOn_aggregate = sum over classes  (paras 146, 150)
 *   multiplier = min(1, F + (1 - F) * exp((V - C) / (2 (1 - F) AddOn_aggregate))),  F = 5%  (para 149)
 * </pre>
 *
 * <p>Trade level (paras 155-159): adjusted notional d_i is the notional
 * times the supervisory duration {@code SD = (exp(-0.05 S) - exp(-0.05 E)) / 0.05}
 * for rates and credit, the foreign leg for FX, price times units for
 * equity and commodity. The maturity factor is
 * {@code MF = sqrt(min(max(M, 10/250), 1))} unmargined and
 * {@code MF = 1.5 sqrt(MPOR / 250)} margined (MPOR in business days, a
 * 250-day year as in the Annex 4a examples). Each trade contributes
 * {@code delta * d * MF}.</p>
 *
 * <p>Asset-class add-ons (paras 165-182), supervisory factors from Table 2:</p>
 * <ul>
 *   <li>Interest rates: hedging set per currency, three maturity buckets
 *   by E (under 1y, 1y-5y, over 5y), effective notional
 *   {@code sqrt(D1^2 + D2^2 + D3^2 + 1.4 D1 D2 + 1.4 D2 D3 + 0.6 D1 D3)},
 *   SF 0.5%, add-on is the sum over currencies.</li>
 *   <li>FX: hedging set per currency pair, SF 4% times the absolute net
 *   effective notional, summed over pairs.</li>
 *   <li>Credit and equity: entity-level add-on SF times net effective
 *   notional; then
 *   {@code sqrt((sum rho_k A_k)^2 + sum (1 - rho_k^2) A_k^2)} with
 *   rho 50% single name, 80% index.</li>
 *   <li>Commodity: commodity-type add-on SF times net effective notional
 *   (40% electricity, 18% other types), the same systematic plus
 *   idiosyncratic formula with rho 40% inside each of the four hedging
 *   sets, summed over sets.</li>
 * </ul>
 *
 * <p>Basis and volatility transactions (paras 162-163) form their own
 * hedging sets inside their asset class, with the supervisory factor
 * times 0.5 (basis) or 5 (volatility); mark a trade with
 * {@link SaCcrTrade#asBasis()} or {@link SaCcrTrade#asVolatility()}.
 * For example a USD 3m/OIS basis swap with adjusted notional 78,694
 * in the over-5y bucket adds 0.5 * 0.5% * 78,694 = 196.7 on its own,
 * where the same trade as an ordinary swap would add 393.5 and net
 * against the other USD swaps.</p>
 *
 * <p>Margin agreements and netting sets (paras 185-187, checked against
 * the March 2014 text):
 * {@link #oneMarginAgreementManyNettingSets} for one agreement covering
 * several netting sets, {@link #subNettingSets} for one netting set
 * under several agreements (or partly unmargined). Collateral C is
 * passed already haircut; {@link SaCcrHaircuts} gives the supervisory
 * haircuts. Times are year
 * fractions the caller computes; the S and E floor of ten business
 * days is applied as 10/250 years. The tests reproduce Annex 4a
 * Examples 1 to 5. Research lane, deterministic.</p>
 */
public final class SaCcr {

    /** Alpha, para 128. */
    public static final double ALPHA = 1.4;
    /** Multiplier floor, para 149. */
    public static final double MULTIPLIER_FLOOR = 0.05;
    /** Supervisory option volatility, interest rates (Table 2). */
    public static final double OPTION_VOL_INTEREST_RATE = 0.50;
    /** Supervisory option volatility, FX (Table 2). */
    public static final double OPTION_VOL_FX = 0.15;
    /** Supervisory option volatility, single-name credit (Table 2). */
    public static final double OPTION_VOL_CREDIT_SINGLE = 1.00;
    /** Supervisory option volatility, credit index (Table 2). */
    public static final double OPTION_VOL_CREDIT_INDEX = 0.80;
    /** Supervisory option volatility, single-name equity (Table 2). */
    public static final double OPTION_VOL_EQUITY_SINGLE = 1.20;
    /** Supervisory option volatility, equity index (Table 2). */
    public static final double OPTION_VOL_EQUITY_INDEX = 0.75;
    /** Supervisory option volatility, electricity (Table 2). */
    public static final double OPTION_VOL_ELECTRICITY = 1.50;
    /** Supervisory option volatility, other commodities (Table 2). */
    public static final double OPTION_VOL_COMMODITY_OTHER = 0.70;

    private static final double SF_IR = 0.005;
    private static final double SF_FX = 0.04;
    private static final double SF_EQUITY_SINGLE = 0.32;
    private static final double SF_EQUITY_INDEX = 0.20;
    private static final double SF_ELECTRICITY = 0.40;
    private static final double SF_COMMODITY_OTHER = 0.18;
    private static final double RHO_SINGLE = 0.5;
    private static final double RHO_INDEX = 0.8;
    private static final double RHO_COMMODITY = 0.4;
    private static final double DAYS_PER_YEAR = 250;
    private static final double TEN_DAYS = 10 / DAYS_PER_YEAR;

    private SaCcr() {
    }

    /**
     * Hedging-set kind of a trade (paras 162-163) and its multiplier on
     * the supervisory factor.
     */
    public enum HedgingSetType {
        /** Ordinary hedging set, factor 1. */
        ORDINARY(1),
        /** Basis transaction, own hedging set per risk-factor pair, factor 0.5. */
        BASIS(0.5),
        /** Volatility transaction, own hedging set, factor 5. */
        VOLATILITY(5);

        private final double factorMultiplier;

        HedgingSetType(double factorMultiplier) {
            this.factorMultiplier = factorMultiplier;
        }

        /** Returns the multiplier applied to the supervisory factor. */
        public double factorMultiplier() {
            return factorMultiplier;
        }
    }

    /** The five SA-CCR asset classes. */
    public enum AssetClass {
        /** Interest-rate derivatives. */
        INTEREST_RATE,
        /** Foreign-exchange derivatives. */
        FX,
        /** Credit derivatives. */
        CREDIT,
        /** Equity derivatives. */
        EQUITY,
        /** Commodity derivatives. */
        COMMODITY
    }

    /** Credit rating buckets and their supervisory factors (Table 2). */
    public enum CreditGrade {
        /** AAA single name, 0.38%. */
        AAA(0.0038, false),
        /** AA single name, 0.38%. */
        AA(0.0038, false),
        /** A single name, 0.42%. */
        A(0.0042, false),
        /** BBB single name, 0.54%. */
        BBB(0.0054, false),
        /** BB single name, 1.06%. */
        BB(0.0106, false),
        /** B single name, 1.6%. */
        B(0.016, false),
        /** CCC single name, 6.0%. */
        CCC(0.06, false),
        /** Investment-grade index, 0.38%. */
        INVESTMENT_GRADE_INDEX(0.0038, true),
        /** Speculative-grade index, 1.06%. */
        SPECULATIVE_GRADE_INDEX(0.0106, true);

        private final double supervisoryFactor;
        private final boolean isIndex;

        CreditGrade(double supervisoryFactor, boolean isIndex) {
            this.supervisoryFactor = supervisoryFactor;
            this.isIndex = isIndex;
        }

        /** Returns the supervisory factor as a decimal. */
        public double supervisoryFactor() {
            return supervisoryFactor;
        }

        /** Returns true for the two index grades. */
        public boolean isIndex() {
            return isIndex;
        }
    }

    /** The four commodity hedging sets (para 161). */
    public enum CommoditySet {
        /** Energy: oil, gas, coal, electricity. */
        ENERGY,
        /** Metals. */
        METALS,
        /** Agricultural. */
        AGRICULTURAL,
        /** Everything else. */
        OTHER
    }

    /**
     * Margin agreement terms for a margined netting set.
     *
     * @param threshold         TH, the exposure below which no variation
     *                          margin is called (&ge; 0)
     * @param minimumTransfer   MTA (&ge; 0)
     * @param nica              net independent collateral amount (may be
     *                          negative when the bank posts more than it holds)
     * @param mporBusinessDays  margin period of risk in business days
     *                          (10 + N - 1 for re-margining every N days)
     */
    public record Margin(double threshold, double minimumTransfer, double nica,
                         double mporBusinessDays) {
        /** Validates the terms. */
        public Margin {
            if (!(threshold >= 0) || !(minimumTransfer >= 0)
                    || threshold == Double.POSITIVE_INFINITY
                    || minimumTransfer == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("threshold and MTA must be >= 0 and finite");
            }
            if (!Double.isFinite(nica)) {
                throw new IllegalArgumentException("nica must be finite, got " + nica);
            }
            if (!(mporBusinessDays > 0) || mporBusinessDays == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "mporBusinessDays must be positive and finite, got " + mporBusinessDays);
            }
        }
    }

    /**
     * SA-CCR outputs for one netting set.
     *
     * @param marketValue    V, the net market value
     * @param replacementCost RC
     * @param addOnInterestRate rates add-on
     * @param addOnFx        FX add-on
     * @param addOnCredit    credit add-on
     * @param addOnEquity    equity add-on
     * @param addOnCommodity commodity add-on
     * @param addOnAggregate sum of the five
     * @param multiplier     the PFE multiplier
     * @param pfe            multiplier times aggregate add-on
     * @param ead            alpha times (RC + PFE)
     */
    public record Result(double marketValue, double replacementCost, double addOnInterestRate,
                         double addOnFx, double addOnCredit, double addOnEquity,
                         double addOnCommodity, double addOnAggregate, double multiplier,
                         double pfe, double ead) {
    }

    /**
     * EAD of an unmargined netting set.
     *
     * @param trades            the trades of the netting set
     * @param collateralHeld    C, haircut value of net collateral held
     *                          (negative when posted)
     * @return the result
     */
    public static Result unmargined(List<SaCcrTrade> trades, double collateralHeld) {
        return compute(trades, collateralHeld, null);
    }

    /**
     * EAD of a margined netting set.
     *
     * @param trades         the trades of the netting set
     * @param collateralHeld C, haircut value of net collateral held,
     *                       variation margin plus NICA
     * @param margin         margin agreement terms
     * @return the result
     */
    public static Result margined(List<SaCcrTrade> trades, double collateralHeld, Margin margin) {
        return compute(trades, collateralHeld, Objects.requireNonNull(margin, "margin"));
    }

    private static Result compute(List<SaCcrTrade> trades, double collateral, Margin margin) {
        Objects.requireNonNull(trades, "trades");
        if (trades.isEmpty()) {
            throw new IllegalArgumentException("netting set has no trades");
        }
        if (!Double.isFinite(collateral)) {
            throw new IllegalArgumentException("collateral must be finite, got " + collateral);
        }
        double v = 0;
        for (SaCcrTrade t : trades) {
            v += Objects.requireNonNull(t, "trade").marketValue();
        }
        double rc = Math.max(v - collateral, 0);
        if (margin != null) {
            rc = Math.max(rc, margin.threshold() + margin.minimumTransfer() - margin.nica());
        }
        double ir = addOnInterestRate(trades, margin);
        double fx = addOnFx(trades, margin);
        double credit = addOnCreditOrEquity(trades, margin, AssetClass.CREDIT);
        double equity = addOnCreditOrEquity(trades, margin, AssetClass.EQUITY);
        double commodity = addOnCommodity(trades, margin);
        double aggregate = ir + fx + credit + equity + commodity;
        double multiplier = multiplier(v - collateral, aggregate);
        double pfe = multiplier * aggregate;
        return new Result(v, rc, ir, fx, credit, equity, commodity, aggregate, multiplier,
                pfe, ALPHA * (rc + pfe));
    }

    /**
     * One margin agreement covering several netting sets (RC from
     * BCBS 279 para 186, PFE from para 187, both checked against the
     * March 2014 text).
     * The agreement's collateral cannot be split between the netting
     * sets, so {@code RC_MA = max(sum_NS max(V_NS, 0) - C_MA, 0)}, and
     * {@code PFE_MA = sum_NS PFE_NS} with each netting set's PFE computed
     * as if unmargined. Each multiplier uses that set's own V_NS with no
     * collateral, since C_MA belongs to no single set; para 187 says only
     * that each PFE follows the unmargined methodology, so the choice of
     * V_NS for the multiplier is this library's reading.
     *
     * <p>Example (own hand computation): netting sets with V of 100 and
     * -40 under one agreement holding C = 30 give
     * RC = max(100 + 0 - 30, 0) = 70. Netting the two sets first would
     * give 30, which the rule does not allow.</p>
     *
     * @param nettingSets    the netting sets, each non-empty
     * @param collateralHeld C_MA, haircut net collateral under the agreement
     * @return a result whose add-on fields are sums over the netting sets
     *         and whose multiplier is PFE divided by the aggregate add-on
     */
    public static Result oneMarginAgreementManyNettingSets(List<List<SaCcrTrade>> nettingSets,
                                                           double collateralHeld) {
        Objects.requireNonNull(nettingSets, "nettingSets");
        if (nettingSets.isEmpty()) {
            throw new IllegalArgumentException("no netting sets");
        }
        if (!Double.isFinite(collateralHeld)) {
            throw new IllegalArgumentException("collateral must be finite, got " + collateralHeld);
        }
        double v = 0;
        double positive = 0;
        double[] sum = new double[6];
        for (List<SaCcrTrade> ns : nettingSets) {
            Result r = compute(ns, 0, null);
            v += r.marketValue();
            positive += Math.max(r.marketValue(), 0);
            accumulate(sum, r);
        }
        return combine(v, Math.max(positive - collateralHeld, 0), sum);
    }

    /**
     * One part of a netting set for {@link #subNettingSets}: the trades
     * under one margin agreement (or the unmargined trades when
     * {@code margin} is null) and the haircut collateral that belongs to
     * them.
     *
     * @param trades         the trades of the sub-netting set
     * @param collateralHeld haircut collateral held for these trades
     * @param margin         the margin agreement, or null for unmargined
     */
    public record SubNettingSet(List<SaCcrTrade> trades, double collateralHeld, Margin margin) {
        /** Validates and copies the trade list. */
        public SubNettingSet {
            trades = List.copyOf(Objects.requireNonNull(trades, "trades"));
            if (trades.isEmpty()) {
                throw new IllegalArgumentException("sub-netting set has no trades");
            }
            if (!Double.isFinite(collateralHeld)) {
                throw new IllegalArgumentException("collateral must be finite, got " + collateralHeld);
            }
        }
    }

    /**
     * One netting set under several margin agreements, or mixing
     * margined and unmargined trades (BCBS 279 para 185). The netting set
     * is divided into sub-netting sets aligned with their agreements, and
     * para 185 states that this applies to both RC and PFE. So each part
     * gets its own {@code RC_k} (margined: {@code max(V_k - C_k, TH_k +
     * MTA_k - NICA_k, 0)}; unmargined: {@code max(V_k - C_k, 0)}) and its
     * own PFE with its own maturity factors and multiplier from V_k - C_k,
     * and both are summed over the parts. Until 2.3 the RC was taken for
     * the netting set as a whole with summed floors; that reading did not
     * match para 185 and was corrected.
     *
     * <p>Example (own hand computation): a margined part with V = -30,
     * C = 0, TH + MTA - NICA = 5 and an unmargined part with V = 50 give
     * RC = 5 + 50 = 55, where netting the parts first would give 20.</p>
     *
     * @param parts the sub-netting sets
     * @return a result whose add-on fields are sums over the parts and
     *         whose multiplier is PFE divided by the aggregate add-on
     */
    public static Result subNettingSets(List<SubNettingSet> parts) {
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("no sub-netting sets");
        }
        double v = 0;
        double rc = 0;
        double[] sum = new double[6];
        for (SubNettingSet part : parts) {
            Objects.requireNonNull(part, "part");
            Result r = compute(part.trades(), part.collateralHeld(), part.margin());
            v += r.marketValue();
            rc += r.replacementCost();
            accumulate(sum, r);
        }
        return combine(v, rc, sum);
    }

    private static void accumulate(double[] sum, Result r) {
        sum[0] += r.addOnInterestRate();
        sum[1] += r.addOnFx();
        sum[2] += r.addOnCredit();
        sum[3] += r.addOnEquity();
        sum[4] += r.addOnCommodity();
        sum[5] += r.pfe();
    }

    private static Result combine(double v, double rc, double[] sum) {
        double aggregate = sum[0] + sum[1] + sum[2] + sum[3] + sum[4];
        double pfe = sum[5];
        double multiplier = aggregate > 0 ? pfe / aggregate : 1;
        return new Result(v, rc, sum[0], sum[1], sum[2], sum[3], sum[4], aggregate, multiplier,
                pfe, ALPHA * (rc + pfe));
    }

    /**
     * The PFE multiplier of para 149.
     *
     * @param vMinusC   V - C
     * @param aggregate aggregate add-on (&ge; 0); zero gives 1
     * @return the multiplier in [0.05, 1]
     */
    public static double multiplier(double vMinusC, double aggregate) {
        if (!Double.isFinite(vMinusC) || !(aggregate >= 0) || aggregate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("need finite V - C and aggregate add-on >= 0");
        }
        if (vMinusC >= 0 || aggregate == 0) {
            return 1;
        }
        double f = MULTIPLIER_FLOOR;
        return Math.min(1, f + (1 - f) * Math.exp(vMinusC / (2 * (1 - f) * aggregate)));
    }

    /**
     * Supervisory duration of para 157, with S and E in years.
     *
     * @param startYears S (0 when started; a positive S is floored at ten business days)
     * @param endYears   E (floored at ten business days)
     * @return SD in years
     */
    public static double supervisoryDuration(double startYears, double endYears) {
        if (!(startYears >= 0) || !(endYears >= startYears) || endYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("need 0 <= S <= E finite");
        }
        double s = startYears > 0 ? Math.max(startYears, TEN_DAYS) : 0;
        double e = Math.max(endYears, TEN_DAYS);
        return (Math.exp(-0.05 * s) - Math.exp(-0.05 * e)) / 0.05;
    }

    /**
     * Supervisory option delta of para 159 (Black-Scholes delta with the
     * supervisory volatility, zero rates).
     *
     * @param call       true for a call, false for a put
     * @param bought     true when the bank bought the option
     * @param underlying P, the underlying price (forward, spot, swap rate), &gt; 0
     * @param strike     K, &gt; 0
     * @param expiryYears T, latest exercise date, &gt; 0
     * @param vol        supervisory volatility, &gt; 0
     * @return the signed delta
     */
    public static double optionDelta(boolean call, boolean bought, double underlying,
                                     double strike, double expiryYears, double vol) {
        if (!(underlying > 0) || !(strike > 0) || !(expiryYears > 0) || !(vol > 0)
                || !Double.isFinite(underlying * strike * expiryYears * vol)) {
            throw new IllegalArgumentException("need positive finite P, K, T and vol");
        }
        double d1 = (Math.log(underlying / strike) + 0.5 * vol * vol * expiryYears)
                / (vol * Math.sqrt(expiryYears));
        double delta = call ? SpecialFunctions.normCdf(d1) : -SpecialFunctions.normCdf(-d1);
        return bought ? delta : -delta;
    }

    /**
     * Supervisory delta of a CDO tranche, para 159:
     * {@code 15 / ((1 + 14 A)(1 + 14 D))}, positive for purchased protection.
     *
     * @param attachment A in [0, 1)
     * @param detachment D in (A, 1]
     * @param purchased  true for long protection
     * @return the signed delta
     */
    public static double cdoTrancheDelta(double attachment, double detachment, boolean purchased) {
        if (!(attachment >= 0) || !(detachment > attachment) || !(detachment <= 1)) {
            throw new IllegalArgumentException("need 0 <= A < D <= 1");
        }
        double d = 15 / ((1 + 14 * attachment) * (1 + 14 * detachment));
        return purchased ? d : -d;
    }

    /**
     * Maturity factor of paras 164 and 171.
     *
     * @param maturityYears M
     * @param margin        null for unmargined
     * @return MF
     */
    public static double maturityFactor(double maturityYears, Margin margin) {
        if (margin != null) {
            return 1.5 * Math.sqrt(margin.mporBusinessDays() / DAYS_PER_YEAR);
        }
        if (!(maturityYears >= 0) || maturityYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("maturity must be >= 0 and finite");
        }
        return Math.sqrt(Math.min(Math.max(maturityYears, TEN_DAYS), 1));
    }

    private static double effective(SaCcrTrade t, Margin margin) {
        double d = t.notional();
        if (t.assetClass() == AssetClass.INTEREST_RATE || t.assetClass() == AssetClass.CREDIT) {
            d *= supervisoryDuration(t.startYears(), t.endYears());
        }
        return t.delta() * d * maturityFactor(t.maturityYears(), margin);
    }

    private static double addOnInterestRate(List<SaCcrTrade> trades, Margin margin) {
        Map<String, double[]> buckets = new LinkedHashMap<>();
        for (SaCcrTrade t : trades) {
            if (t.assetClass() != AssetClass.INTEREST_RATE) {
                continue;
            }
            double[] d = buckets.computeIfAbsent(t.hedgingSetType().ordinal() + "|" + t.hedgingSet(),
                    k -> new double[]{0, 0, 0, t.hedgingSetType().factorMultiplier()});
            int b = t.endYears() < 1 ? 0 : (t.endYears() <= 5 ? 1 : 2);
            d[b] += effective(t, margin);
        }
        double addOn = 0;
        for (double[] d : buckets.values()) {
            double sq = d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
                    + 1.4 * d[0] * d[1] + 1.4 * d[1] * d[2] + 0.6 * d[0] * d[2];
            addOn += d[3] * SF_IR * Math.sqrt(Math.max(sq, 0));
        }
        return addOn;
    }

    private static double addOnFx(List<SaCcrTrade> trades, Margin margin) {
        Map<String, Double> sets = new LinkedHashMap<>();
        for (SaCcrTrade t : trades) {
            if (t.assetClass() == AssetClass.FX) {
                sets.merge(t.hedgingSetType().ordinal() + "|" + t.hedgingSet(),
                        t.hedgingSetType().factorMultiplier() * effective(t, margin), Double::sum);
            }
        }
        double addOn = 0;
        for (double en : sets.values()) {
            addOn += SF_FX * Math.abs(en);
        }
        return addOn;
    }

    private static double addOnCreditOrEquity(List<SaCcrTrade> trades, Margin margin,
                                              AssetClass cls) {
        // per hedging-set type: entity -> {effective notional, SF, rho}
        Map<HedgingSetType, Map<String, double[]>> sets = new LinkedHashMap<>();
        for (SaCcrTrade t : trades) {
            if (t.assetClass() != cls) {
                continue;
            }
            double sf;
            if (cls == AssetClass.CREDIT) {
                sf = t.creditGrade().supervisoryFactor();
            } else {
                sf = t.index() ? SF_EQUITY_INDEX : SF_EQUITY_SINGLE;
            }
            double fsf = sf * t.hedgingSetType().factorMultiplier();
            double rho = t.index() ? RHO_INDEX : RHO_SINGLE;
            double[] e = sets.computeIfAbsent(t.hedgingSetType(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(t.hedgingSet(), k -> new double[]{0, fsf, rho});
            if (Double.compare(e[1], fsf) != 0 || Double.compare(e[2], rho) != 0) {
                throw new IllegalArgumentException("entity " + t.hedgingSet()
                        + " has trades with different grades or index flags");
            }
            e[0] += effective(t, margin);
        }
        double addOn = 0;
        for (Map<String, double[]> entities : sets.values()) {
            addOn += systematicPlusIdiosyncratic(entities);
        }
        return addOn;
    }

    private static double addOnCommodity(List<SaCcrTrade> trades, Margin margin) {
        Map<String, Map<String, double[]>> sets = new LinkedHashMap<>();
        for (SaCcrTrade t : trades) {
            if (t.assetClass() != AssetClass.COMMODITY) {
                continue;
            }
            String type = t.hedgingSet().trim();
            double sf = t.commoditySet() == CommoditySet.ENERGY
                    && type.equalsIgnoreCase("electricity") ? SF_ELECTRICITY : SF_COMMODITY_OTHER;
            double fsf = sf * t.hedgingSetType().factorMultiplier();
            double[] e = sets.computeIfAbsent(t.hedgingSetType().ordinal() + "|" + t.commoditySet(),
                            k -> new LinkedHashMap<>())
                    .computeIfAbsent(type.toLowerCase(java.util.Locale.ROOT),
                            k -> new double[]{0, fsf, RHO_COMMODITY});
            e[0] += effective(t, margin);
        }
        double addOn = 0;
        for (Map<String, double[]> types : sets.values()) {
            addOn += systematicPlusIdiosyncratic(types);
        }
        return addOn;
    }

    private static double systematicPlusIdiosyncratic(Map<String, double[]> components) {
        double systematic = 0;
        double idiosyncratic = 0;
        for (double[] e : components.values()) {
            double a = e[1] * e[0];
            systematic += e[2] * a;
            idiosyncratic += (1 - e[2] * e[2]) * a * a;
        }
        return Math.sqrt(systematic * systematic + idiosyncratic);
    }
}

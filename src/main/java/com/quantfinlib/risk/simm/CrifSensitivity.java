package com.quantfinlib.risk.simm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One CRIF-like sensitivity row.
 *
 * <p>The CRIF (Common Risk Interchange Format) row carries a product class,
 * a risk type such as {@code Risk_IRCurve}, a qualifier (currency, issuer,
 * ticker), a bucket, two labels (tenor and sub-curve for rates), and an
 * amount in the calculation currency. The risk type fixes both the
 * {@link SimmRiskClass} and the {@link SimmRiskMeasure}.</p>
 *
 * <p>Supported risk types: {@code Risk_IRCurve}, {@code Risk_Inflation}
 * (treated as an IR delta with label2 {@code Inflation}), {@code Risk_IRVol},
 * {@code Risk_CreditQ}, {@code Risk_CreditVol}, {@code Risk_BaseCorr},
 * {@code Risk_CreditNonQ}, {@code Risk_CreditVolNonQ}, {@code Risk_Equity},
 * {@code Risk_EquityVol}, {@code Risk_Commodity}, {@code Risk_CommodityVol},
 * {@code Risk_FX}, {@code Risk_FXVol}.</p>
 *
 * @param productClass the product class the trade belongs to
 * @param riskType     CRIF risk type, e.g. {@code Risk_IRCurve}
 * @param qualifier    currency, issuer, ticker or currency pair
 * @param bucket       SIMM bucket; for IR the currency; may be empty for FX
 * @param label1       tenor (delta) or option expiry (vega); may be empty
 * @param label2       IR sub-curve (e.g. {@code Libor3m}); may be empty
 * @param amount       sensitivity in the calculation currency
 */
public record CrifSensitivity(SimmProductClass productClass, String riskType,
                              String qualifier, String bucket, String label1,
                              String label2, double amount) {

    private static final Map<String, SimmRiskClass> CLASS = new HashMap<>();
    private static final Map<String, SimmRiskMeasure> MEASURE = new HashMap<>();

    static {
        map("Risk_IRCurve", SimmRiskClass.INTEREST_RATE, SimmRiskMeasure.DELTA);
        map("Risk_Inflation", SimmRiskClass.INTEREST_RATE, SimmRiskMeasure.DELTA);
        map("Risk_IRVol", SimmRiskClass.INTEREST_RATE, SimmRiskMeasure.VEGA);
        map("Risk_CreditQ", SimmRiskClass.CREDIT_QUALIFYING, SimmRiskMeasure.DELTA);
        map("Risk_CreditVol", SimmRiskClass.CREDIT_QUALIFYING, SimmRiskMeasure.VEGA);
        map("Risk_BaseCorr", SimmRiskClass.CREDIT_QUALIFYING, SimmRiskMeasure.BASE_CORRELATION);
        map("Risk_CreditNonQ", SimmRiskClass.CREDIT_NON_QUALIFYING, SimmRiskMeasure.DELTA);
        map("Risk_CreditVolNonQ", SimmRiskClass.CREDIT_NON_QUALIFYING, SimmRiskMeasure.VEGA);
        map("Risk_Equity", SimmRiskClass.EQUITY, SimmRiskMeasure.DELTA);
        map("Risk_EquityVol", SimmRiskClass.EQUITY, SimmRiskMeasure.VEGA);
        map("Risk_Commodity", SimmRiskClass.COMMODITY, SimmRiskMeasure.DELTA);
        map("Risk_CommodityVol", SimmRiskClass.COMMODITY, SimmRiskMeasure.VEGA);
        map("Risk_FX", SimmRiskClass.FX, SimmRiskMeasure.DELTA);
        map("Risk_FXVol", SimmRiskClass.FX, SimmRiskMeasure.VEGA);
    }

    private static void map(String t, SimmRiskClass c, SimmRiskMeasure m) {
        CLASS.put(t.toLowerCase(Locale.ROOT), c);
        MEASURE.put(t.toLowerCase(Locale.ROOT), m);
    }

    /**
     * Validates the row. Null text fields become empty strings.
     */
    public CrifSensitivity {
        Objects.requireNonNull(productClass, "productClass");
        Objects.requireNonNull(riskType, "riskType");
        if (!CLASS.containsKey(riskType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported CRIF risk type: " + riskType);
        }
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite: " + amount);
        }
        qualifier = qualifier == null ? "" : qualifier.trim();
        bucket = bucket == null ? "" : bucket.trim();
        label1 = label1 == null ? "" : label1.trim();
        label2 = label2 == null ? "" : label2.trim();
        if ("risk_inflation".equalsIgnoreCase(riskType) && label2.isEmpty()) {
            label2 = "Inflation";
        }
    }

    /** @return the SIMM risk class implied by the risk type */
    public SimmRiskClass riskClass() {
        return CLASS.get(riskType.toLowerCase(Locale.ROOT));
    }

    /** @return the SIMM measure implied by the risk type */
    public SimmRiskMeasure measure() {
        return MEASURE.get(riskType.toLowerCase(Locale.ROOT));
    }

    /**
     * The bucket used for aggregation: for IR the currency (the bucket
     * column if given, else the qualifier); for FX always {@code 1};
     * otherwise the bucket column.
     *
     * @return the effective bucket
     */
    public String effectiveBucket() {
        return switch (riskClass()) {
            case INTEREST_RATE -> bucket.isEmpty() ? qualifier : bucket;
            case FX -> "1";
            default -> bucket;
        };
    }

    /**
     * Parses comma-separated CRIF text. The first non-blank line is a
     * header; recognised columns (case-insensitive) are ProductClass,
     * RiskType, Qualifier, Bucket, Label1, Label2 and Amount (or AmountUSD).
     * Other columns are ignored. No quoting is supported.
     *
     * @param in the CSV text
     * @return the rows in file order
     * @throws IOException on read failure
     * @throws IllegalArgumentException on a malformed row
     */
    public static List<CrifSensitivity> parseCsv(Reader in) throws IOException {
        BufferedReader r = in instanceof BufferedReader b ? b : new BufferedReader(in);
        List<CrifSensitivity> out = new ArrayList<>();
        Map<String, Integer> col = null;
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (col == null) {
                col = new HashMap<>();
                for (int i = 0; i < f.length; i++) {
                    col.put(f[i].trim().toLowerCase(Locale.ROOT), i);
                }
                if (!col.containsKey("amount") && col.containsKey("amountusd")) {
                    col.put("amount", col.get("amountusd"));
                }
                for (String req : new String[]{"productclass", "risktype", "amount"}) {
                    if (!col.containsKey(req)) {
                        throw new IllegalArgumentException("CRIF header missing column " + req);
                    }
                }
                continue;
            }
            try {
                out.add(new CrifSensitivity(
                        SimmProductClass.fromCrif(cell(f, col, "productclass")),
                        cell(f, col, "risktype"),
                        cell(f, col, "qualifier"),
                        cell(f, col, "bucket"),
                        cell(f, col, "label1"),
                        cell(f, col, "label2"),
                        Double.parseDouble(cell(f, col, "amount"))));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("CRIF line " + n + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static String cell(String[] f, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= f.length ? "" : f[i].trim();
    }
}

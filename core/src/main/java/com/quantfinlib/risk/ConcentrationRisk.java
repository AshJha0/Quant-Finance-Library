package com.quantfinlib.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concentration risk metrics over exposures (by asset, counterparty, sector,
 * currency, ...): Herfindahl-Hirschman index, effective number of positions,
 * top-N share, and single-name limit breaches.
 */
public final class ConcentrationRisk {

    private ConcentrationRisk() {
    }

    /** Herfindahl-Hirschman index of |exposure| shares; 1/N (diversified) .. 1 (single name). */
    public static double herfindahlIndex(double[] exposures) {
        double total = 0;
        for (double e : exposures) {
            total += Math.abs(e);
        }
        if (total == 0) {
            return 0;
        }
        double hhi = 0;
        for (double e : exposures) {
            double share = Math.abs(e) / total;
            hhi += share * share;
        }
        return hhi;
    }

    /** Effective number of equally-weighted positions: 1 / HHI. */
    public static double effectivePositions(double[] exposures) {
        double hhi = herfindahlIndex(exposures);
        return hhi == 0 ? 0 : 1 / hhi;
    }

    /** Combined |exposure| share of the largest {@code n} positions. */
    public static double topNShare(double[] exposures, int n) {
        double total = 0;
        double[] abs = new double[exposures.length];
        for (int i = 0; i < exposures.length; i++) {
            abs[i] = Math.abs(exposures[i]);
            total += abs[i];
        }
        if (total == 0) {
            return 0;
        }
        java.util.Arrays.sort(abs);
        double top = 0;
        for (int i = abs.length - 1; i >= Math.max(0, abs.length - n); i--) {
            top += abs[i];
        }
        return top / total;
    }

    /** |Exposure| share per group key. */
    public static Map<String, Double> shares(Map<String, Double> exposureByGroup) {
        double total = 0;
        for (double e : exposureByGroup.values()) {
            total += Math.abs(e);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : exposureByGroup.entrySet()) {
            out.put(e.getKey(), total == 0 ? 0 : Math.abs(e.getValue()) / total);
        }
        return out;
    }

    /** Group keys whose share exceeds the single-name concentration limit. */
    public static List<String> limitBreaches(Map<String, Double> exposureByGroup, double maxShare) {
        List<String> out = new ArrayList<>();
        shares(exposureByGroup).forEach((k, share) -> {
            if (share > maxShare) {
                out.add(k);
            }
        });
        return out;
    }
}

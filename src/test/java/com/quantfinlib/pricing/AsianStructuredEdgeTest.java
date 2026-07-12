package com.quantfinlib.pricing;

import org.junit.jupiter.api.Test;

import static com.quantfinlib.pricing.BlackScholes.OptionType.CALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Limit pins the first Asian/structured layer left open: the
 * Kemna-Vorst geometric and Turnbull-Wakeman arithmetic prices converge
 * as vol goes to zero (the AM-GM gap is O(vol^2)), tiny expiry collapses
 * both to intrinsic, and every structured note moves in vol with the
 * sign of its embedded option position.
 */
class AsianStructuredEdgeTest {

    private static final double S = 100, R = 0.05, Q = 0.01;

    @Test
    void kemnaVorstAndTurnbullWakemanAgreeAtLowVolAndDivergeWithVol() {
        // With r = q the fixing forwards are all S, so the drift Jensen
        // term vanishes and the two prices differ ONLY through the AM-GM
        // gap between the average's distributions — which scales with
        // vol^2: sub-penny at 1% vol, visibly larger at 40%. (With r != q
        // the gap has a vol-independent drift floor, ~0.007 here at
        // g = 4%, which is why this pin sets g = 0.)
        int n = 12;
        double t = 1.0;
        double r = 0.03, q = 0.03;
        double geoLow = AsianOption.geometricPrice(CALL, S, 100, r, q, 0.01, t, n);
        double arithLow = AsianOption.arithmeticPrice(CALL, S, 100, r, q, 0.01, t, n);
        double gapLow = arithLow - geoLow;
        assertTrue(gapLow >= 0, "AM-GM: arithmetic >= geometric");
        assertTrue(gapLow < 1e-3, "1% vol, zero carry gap: KV ~ TW, gap=" + gapLow);

        double geoHigh = AsianOption.geometricPrice(CALL, S, 100, r, q, 0.40, t, n);
        double arithHigh = AsianOption.arithmeticPrice(CALL, S, 100, r, q, 0.40, t, n);
        double gapHigh = arithHigh - geoHigh;
        assertTrue(gapHigh > 10 * gapLow,
                "vol^2 scaling: 40%-vol gap " + gapHigh + " vs 1%-vol gap " + gapLow);
    }

    @Test
    void tinyExpiryCollapsesToIntrinsic() {
        // T = 1e-6: every fixing is (essentially) spot, so the ITM call is
        // worth its intrinsic S - K and the OTM call nothing.
        double t = 1e-6;
        assertEquals(10.0, AsianOption.geometricPrice(CALL, S, 90, R, Q, 0.25, t, 4), 1e-3);
        assertEquals(10.0, AsianOption.arithmeticPrice(CALL, S, 90, R, Q, 0.25, t, 4), 1e-3);
        assertEquals(0.0, AsianOption.geometricPrice(CALL, S, 110, R, Q, 0.25, t, 4), 1e-6);
        assertEquals(0.0, AsianOption.arithmeticPrice(CALL, S, 110, R, Q, 0.25, t, 4), 1e-6);
    }

    @Test
    void structuredNotesMoveInVolWithTheirEmbeddedOptionSign() {
        double t = 1.0;
        // Reverse convertible = bond MINUS put: more vol, dearer put,
        // cheaper note.
        double rcLow = StructuredNotes.reverseConvertible(1000, 0.09, S, 90, R, Q, 0.15, t);
        double rcMid = StructuredNotes.reverseConvertible(1000, 0.09, S, 90, R, Q, 0.25, t);
        double rcHigh = StructuredNotes.reverseConvertible(1000, 0.09, S, 90, R, Q, 0.35, t);
        assertTrue(rcLow > rcMid && rcMid > rcHigh,
                "short put: note value must fall as vol rises");

        // Capital-protected note = bond PLUS participation * call: more
        // vol, dearer call, dearer note.
        double cpLow = StructuredNotes.capitalProtectedNote(1000, 0.95, 1.0, S, R, Q, 0.15, t);
        double cpHigh = StructuredNotes.capitalProtectedNote(1000, 0.95, 1.0, S, R, Q, 0.35, t);
        assertTrue(cpHigh > cpLow, "long call: note value must rise with vol");

        // Discount certificate = stock MINUS call: falls with vol.
        double dcLow = StructuredNotes.discountCertificate(S, 110, R, Q, 0.15, t);
        double dcHigh = StructuredNotes.discountCertificate(S, 110, R, Q, 0.35, t);
        assertTrue(dcHigh < dcLow, "short call: certificate must fall with vol");
    }
}

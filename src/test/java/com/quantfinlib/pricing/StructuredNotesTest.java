package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structured notes pinned by their own replications: each product must
 * equal its decomposition into bond + vanilla pieces to machine
 * precision, and every solver must round-trip.
 */
class StructuredNotesTest {

    private static final double S = 100, R = 0.03, Q = 0.01, VOL = 0.25, T = 1.0;

    @Test
    void reverseConvertibleEqualsBondMinusPutReplication() {
        double par = 1000, coupon = 0.09, k = 90;
        double note = StructuredNotes.reverseConvertible(par, coupon, S, k, R, Q, VOL, T);
        double replication = par * 1.09 * Math.exp(-R * T)
                - (par / k) * BlackScholes.price(OptionType.PUT, S, k, R, Q, VOL, T);
        assertEquals(replication, note, 1e-12, "the note IS its replication");
        // The fat coupon is put premium: the note is worth LESS than the
        // same bond without the embedded short put.
        assertTrue(note < par * 1.09 * Math.exp(-R * T));
        // Zero vol, strike comfortably below spot: the put is worthless
        // and the note is pure bond + coupon.
        assertEquals(par * 1.09 * Math.exp(-R * T),
                StructuredNotes.reverseConvertible(par, coupon, S, 60, R, Q, 0, T), 1e-9);
        // Short put = long the stock.
        assertTrue(StructuredNotes.reverseConvertibleDelta(par, S, k, R, Q, VOL, T) > 0);
    }

    @Test
    void capitalProtectedNoteFloorsAndParticipationSolverRoundTrips() {
        double par = 1000, protection = 0.95;
        // Zero vol: the ATM call under carry Q > 0 still has forward value;
        // use the floor identity instead at zero participation.
        assertEquals(protection * par * Math.exp(-R * T),
                StructuredNotes.capitalProtectedNote(par, protection, 0, S, R, Q, VOL, T), 1e-12);

        // Solver round trip: the participation the budget affords reprices
        // to exactly that budget.
        double issuePrice = 990;
        double p = StructuredNotes.participationFor(par, protection, issuePrice, S, R, Q, VOL, T);
        assertTrue(p > 0);
        assertEquals(issuePrice,
                StructuredNotes.capitalProtectedNote(par, protection, p, S, R, Q, VOL, T), 1e-9);

        // The zero-rate era lesson: same budget, lower rates -> the bond
        // floor eats more of it -> thinner participation.
        double pLowRate = StructuredNotes.participationFor(par, protection, issuePrice,
                S, 0.001, Q, VOL, T);
        assertTrue(pLowRate < p, "low rates starve participation: " + pLowRate + " vs " + p);

        // A budget below the protected floor is unbuildable.
        assertThrows(IllegalArgumentException.class, () -> StructuredNotes.participationFor(
                par, 1.0, 900, S, 0.001, Q, VOL, T));
    }

    @Test
    void discountCertificateIsTheCoveredCall() {
        double cap = 110;
        double cert = StructuredNotes.discountCertificate(S, cap, R, Q, VOL, T);
        double coveredCall = S * Math.exp(-Q * T)
                - BlackScholes.price(OptionType.CALL, S, cap, R, Q, VOL, T);
        assertEquals(coveredCall, cert, 1e-12);
        // The discount to (carry-adjusted) spot is the call premium: cert < S e^{-qT}.
        assertTrue(cert < S * Math.exp(-Q * T));
        // A cap far above spot gives away almost nothing: cert -> S e^{-qT}.
        assertEquals(S * Math.exp(-Q * T),
                StructuredNotes.discountCertificate(S, 10_000, R, Q, VOL, T), 1e-6);
        double delta = StructuredNotes.discountCertificateDelta(S, cap, R, Q, VOL, T);
        assertTrue(delta > 0 && delta < 1, "long stock short call: delta in (0,1), got " + delta);
    }

    @Test
    void structuredNoteGates() {
        assertThrows(IllegalArgumentException.class, () -> StructuredNotes.reverseConvertible(
                1000, -0.01, S, 90, R, Q, VOL, T));
        assertThrows(IllegalArgumentException.class, () -> StructuredNotes.capitalProtectedNote(
                1000, 1.5, 0.5, S, R, Q, VOL, T));
        assertThrows(IllegalArgumentException.class, () -> StructuredNotes.discountCertificate(
                S, 0, R, Q, VOL, T));
        assertThrows(IllegalArgumentException.class, () -> StructuredNotes.reverseConvertible(
                1000, 0.09, S, 90, R, Q, Double.NaN, T));
    }
}

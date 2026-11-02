/**
 * Credit curves, CDS pricing and credit spreads.
 * {@link com.quantfinlib.credit.CreditCurve}
 * bootstraps piecewise-constant hazard rates from CDS par spreads (the
 * credit analogue of the rates bootstrap — every input reprices exactly,
 * and the credit triangle {@code spread ~ hazard * (1 - recovery)} is
 * pinned by test); {@link com.quantfinlib.credit.CdsPricer} prices the
 * two legs, the par spread and the standardized-contract upfront, and
 * exposes the risky annuity (risky DV01);
 * {@link com.quantfinlib.credit.CreditSpreads} translates bond prices
 * into Z-spreads over the risk-free curve, the number that pairs with
 * the CDS par spread to form the cds-bond basis.
 * {@link com.quantfinlib.credit.IsdaCdsModel} is the ISDA CDS Standard
 * Model; {@link com.quantfinlib.credit.ExposureSimulator} and
 * {@link com.quantfinlib.credit.MultiCurrencyExposureSimulator} simulate
 * netting-set exposure, and {@link com.quantfinlib.credit.CvaApproximator}
 * turns it into CVA, DVA, bilateral and wrong-way CVA. Research lane;
 * discretization choices stated on each class.
 */
@NullMarked
package com.quantfinlib.credit;

import org.jspecify.annotations.NullMarked;

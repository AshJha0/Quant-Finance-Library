# Docs notes for worker smile (W2.1-W2.3)

## LEARN
- SVI: w(k) = a + b(rho(k-m) + sqrt((k-m)^2 + sigma^2)), k = ln(K/F), w = sigma_BS^2 T (Gatheral 2004; Gatheral-Jacquier 2014 eq. 3.1). Butterfly-free iff g(k) = (1 - k w'/(2w))^2 - w'^2/4 (1/w + 1/4) + w''/2 >= 0 (GJ 2014 lemma 2.2). Worked example: Vogt's parameters (a=-0.0410, b=0.1331, rho=0.3060, m=0.3586, sigma=0.4153) are admissible yet g < 0 (min about -0.033).
- SSVI: w = theta/2 (1 + rho phi k + sqrt((phi k + rho)^2 + 1 - rho^2)), phi = eta/(theta^gamma (1+theta)^(1-gamma)). With gamma in [0, 1/2] and eta(1+|rho|) <= 2 it is free of static arbitrage for any non-decreasing theta_t (GJ 2014 theorems 4.1, 4.2, remark 4.4). An SSVI slice is a raw SVI slice with a = theta(1-rho^2)/2, b = theta phi/2, m = -rho/phi, sigma = sqrt(1-rho^2)/phi.
- Dupire in total variance (Gatheral 2006 eq. 1.10): sigma_loc^2 = dw/dT / g(y). The denominator is the butterfly factor, the numerator the calendar condition.
- Exact vanna-volga: solve the 3x3 vega/vanna/volga system for the pillar weights (Castagna-Mercurio 2007); for vanillas it coincides with the closed-form log-strike weights. One-touch: TV + p_survival * sum x_i cost_i (Wystup 2006).

## COOKBOOK
- `SsviSurface s = SsviSurface.calibrate(spot, r, q, expiries, strikes, vols, null); s.impliedVol(1.0, 95);`
- `double p = new LocalVolPde(new DupireLocalVol(s)).price(OptionType.PUT, 90, 1.0, 601, 400);`
- `LocalVolMonteCarlo.Result r = new LocalVolMonteCarlo(new DupireLocalVol(s)).price(OptionType.CALL, 110, 1.0, 20000, 100, 7L);`
- `SviSlice fit = SviSlice.calibrate(k, w, vegaWeights);  fit.minDensityFactor(-2, 2, 2001) >= 0`
- `VannaVolgaTouch.oneTouch(new VannaVolga(strikes, vols, r, q, t), spot, barrier, 1_000_000);`

## README / domain docs
- Pricing feature list: SVI/SSVI surfaces with arbitrage checks, Dupire local vol with MC and PDE pricers, exact vanna-volga and VV one-touch.
- Remove the VolSurface javadoc/doc line "SVI/SSVI fitting and Dupire local vol are not implemented" (left untouched in code to keep this change additive; docs phase may update it).
- Tolerances pinned by tests: PDE reprices SSVI vanillas within 2e-3 implied vol (601 x 400 grid); MC within 4 standard errors plus 0.5 % of price (20k antithetic pairs, 100 steps); flat surface local vol equals sigma to 1e-8.

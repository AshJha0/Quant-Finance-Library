### Added
- `pricing.SviSlice`: raw SVI (Gatheral 2004) with the Gatheral-Jacquier density factor g(k) and Levenberg-Marquardt calibration with parameter bounds, caller weights and a butterfly penalty.
- `pricing.SsviSurface`: SSVI with power-law phi; constructor enforces the Gatheral-Jacquier theorem 4.1/4.2 bounds, calibration of rho, eta, gamma to vol quotes.
- `pricing.SviSurface`: per-expiry SVI slices interpolated linearly in total variance at fixed log-moneyness.
- `pricing.TotalVarianceSurface`: common surface interface with implied vol, price, calendar and butterfly grid checks.
- `pricing.DupireLocalVol`: Dupire local vol in Gatheral's total-variance form from any total-variance surface.
- `pricing.LocalVolMonteCarlo` (log-Euler, seeded, antithetic) and `pricing.LocalVolPde` (Crank-Nicolson with Rannacher start) European pricers under local vol.
- `pricing.VannaVolga`: exact 3x3 vega/vanna/volga weights (`exactWeights`, `weightsFor`, `priceExact`), `pillarSmileCost` and pillar accessors.
- `fx.VannaVolgaTouch`: vanna-volga adjusted one-touch with survival-probability weighting.

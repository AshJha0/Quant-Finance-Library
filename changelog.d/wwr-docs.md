# wwr docs notes

- Credit/xVA domain doc and LEARN: `CvaApproximator.cvaWrongWay` is a per-bucket Gaussian copula. Bucket i uses driver X_i = W(t_i)/sqrt(t_i) uncollateralised, or X_i = (W(t_i) - W(t_i - MPR))/sqrt(MPR) under a CSA; P(bucket i | X_i) = Phi((Phi^{-1}(PD_i) + rho X_i)/sqrt(1-rho^2)) - Phi((Phi^{-1}(PD_{i-1}) + rho X_i)/sqrt(1-rho^2)). Sources: Hull and White (2012), "CVA and wrong-way risk", FAJ 68(5); Gregory (2015) ch. 17. Marginal PDs are preserved; rho = 0 gives the independent CVA.
- COOKBOOK example: 5y annual payer swap, HW a = 0.05, sigma = 0.01, zero-threshold CSA with 10-day MPR, 5y CDS 200bp, LGD 0.6, 4000 paths, seed 9: independent CVA 0.0194, rho = 0.5 wrong-way CVA above it; negative rho lowers it.
- Limits to state: no single joint default time across buckets; under a large threshold the increment driver ignores the level dependence.

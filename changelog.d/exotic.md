### Added
- `TouchOption.oneTouchPayAtHit`: one-touch paying at the hitting time (Reiner-Rubinstein 1991 rebate formula), up or down, domestic and foreign rates.
- `TouchOption.doubleNoTouch`, `doubleOneTouch` and `doubleNoTouchProbability`: double-barrier touches by the Ikeda-Kunitomo (1992) image series, truncated at |n| <= 50.
- `HestonCos`: Heston European pricer by the Fang-Oosterlee (2008) COS method with analytic delta, gamma, dV/dv0 and full parameter gradient.
- `HestonCalibrator`: Levenberg-Marquardt calibration of Heston to an implied-vol grid using the analytic COS gradient.
- `AsianOption.turnbullWakemanSeasoned`, `curranPrice` and `levyPrice`: arithmetic Asians with arbitrary and partly fixed schedules (Turnbull-Wakeman 1991, Curran 1994, Levy 1992).
- `DividendTree`: European and American options with discrete cash dividends on a recombining CRR tree (Vellekoop-Nieuwenhuis 2006).
- `DividendSchedule.exTime(i)` and `amount(i)` accessors.

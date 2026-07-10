package com.quantfinlib.execution;

/**
 * The futures roll — the trade every futures position must do and most
 * do badly: move from the expiring front contract to the back over the
 * roll window, following the LIQUIDITY MIGRATION rather than fighting
 * it. Rolling everything on day one pays wide back-month spreads;
 * waiting for expiry pays the congestion of everyone else's last day.
 * The algo tracks a migration curve — the cumulative fraction of open
 * interest that has moved by each day — and rolls in step:
 *
 * <pre>  target(day) = round(position · curve[day]);  due = target − rolled</pre>
 *
 * <p>The default curve is the classic roll S-shape (slow start,
 * concentrated middle, complete before the final day's scramble). Each
 * day's due quantity executes as a CALENDAR SPREAD — sell front / buy
 * back for a long — which is exactly a {@link SpreadExecutionAlgo} with
 * ratio 1 and the calendar spread's own legging cap. Deterministic,
 * single-threaded, research/warm lane.</p>
 */
public final class FuturesRollAlgo {

    private final long position;
    private final double[] cumulativeCurve;
    private long rolled;

    /**
     * @param positionContracts contracts to roll (positive; direction is
     *                          the caller's ticket), &gt; 0
     * @param cumulativeMigration cumulative fraction migrated by end of
     *                            each roll day: non-decreasing, in
     *                            (0, 1], final entry exactly 1
     */
    public FuturesRollAlgo(long positionContracts, double[] cumulativeMigration) {
        if (positionContracts <= 0) {
            throw new IllegalArgumentException("positionContracts must be > 0");
        }
        if (cumulativeMigration.length == 0) {
            throw new IllegalArgumentException("need at least one roll day");
        }
        double prev = 0;
        for (double c : cumulativeMigration) {
            if (!(c >= prev) || c > 1) {
                throw new IllegalArgumentException(
                        "migration curve must be non-decreasing within (0, 1]");
            }
            prev = c;
        }
        if (cumulativeMigration[cumulativeMigration.length - 1] != 1.0) {
            throw new IllegalArgumentException(
                    "the curve must END at exactly 1 — a roll that does not complete "
                            + "is a delivery notice waiting to happen");
        }
        this.position = positionContracts;
        this.cumulativeCurve = cumulativeMigration.clone();
    }

    /**
     * The classic S-curve over {@code days} roll days: slow start,
     * concentrated middle, fully complete at the end — smoothstep
     * {@code 3x² − 2x³} sampled at each day's close.
     */
    public static double[] defaultMigration(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("need >= 1 roll day");
        }
        double[] curve = new double[days];
        for (int d = 0; d < days; d++) {
            double x = (d + 1) / (double) days;
            curve[d] = x * x * (3 - 2 * x);
        }
        curve[days - 1] = 1.0;                 // exact, not 0.999999
        return curve;
    }

    /**
     * Contracts due on {@code day} (0-based): the migration target minus
     * what has already rolled. Falling behind earlier days simply makes
     * later days' due larger — the roll always catches up to the curve.
     */
    public long dueOnDay(int day) {
        if (day < 0 || day >= cumulativeCurve.length) {
            throw new IllegalArgumentException("day " + day + " outside the roll window");
        }
        long target = Math.round(position * cumulativeCurve[day]);
        return Math.max(0, target - rolled);
    }

    /** Records rolled contracts (calendar-spread fills). */
    public void onRolled(long contracts) {
        if (contracts < 0 || rolled + contracts > position) {
            throw new IllegalArgumentException("rolled " + contracts
                    + " would exceed the position");
        }
        rolled += contracts;
    }

    public long rolled() {
        return rolled;
    }

    public long remaining() {
        return position - rolled;
    }

    public boolean done() {
        return rolled == position;
    }

    public int rollDays() {
        return cumulativeCurve.length;
    }
}

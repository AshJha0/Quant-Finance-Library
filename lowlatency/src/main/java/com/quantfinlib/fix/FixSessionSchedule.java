package com.quantfinlib.fix;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * When a FIX session may be logged on, and where one session period ends and
 * the next begins.
 *
 * <p>A FIX session is a sequence of messages numbered from 1, and it usually
 * outlives one TCP connection: a counterparty reconnecting at 10:00 after a
 * drop at 09:59 continues the same numbering. The schedule says when that
 * numbering restarts. FIX 4.4 Volume 2 ("FIX Session") leaves session
 * duration to bilateral agreement; the two common agreements are a daily
 * session (for example 07:00 to 17:30 Europe/London, or the FX day 17:00 to
 * 17:00 America/New_York) and a weekly session (Sunday 17:00 to Friday 17:00
 * New York).</p>
 *
 * <p>Formally, a period starts at each occurrence of the start time (daily) or
 * of the start day and time (weekly) in the schedule's zone, and lasts until
 * the next occurrence of the end time, or of the end day and time. A period
 * whose end equals its start lasts a full day (daily) or a full week (weekly),
 * so the session is always open and only the sequence-number roll happens at
 * the boundary. Times are local wall-clock times: across a daylight-saving
 * change the period is one hour shorter or longer in UTC, and a start time
 * that falls in a spring-forward gap moves forward by the gap
 * ({@link ZonedDateTime#of} resolution).</p>
 *
 * <p>Example: {@code daily(17:00, 17:00, America/New_York)} at
 * 2026-07-10T20:59Z (16:59 New York) is in the period that began
 * 2026-07-09T21:00Z; one minute later a new period begins, and a
 * {@link FixSession} running with this schedule resets both sequence
 * numbers.</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
public final class FixSessionSchedule {

    private final LocalTime startTime;
    private final LocalTime endTime;
    private final DayOfWeek startDay;   // null for a daily schedule
    private final DayOfWeek endDay;
    private final ZoneId zone;

    private FixSessionSchedule(DayOfWeek startDay, LocalTime startTime, DayOfWeek endDay,
                               LocalTime endTime, ZoneId zone) {
        this.startDay = startDay;
        this.startTime = Objects.requireNonNull(startTime, "startTime");
        this.endDay = endDay;
        this.endTime = Objects.requireNonNull(endTime, "endTime");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * A session every day from {@code start} to {@code end} local time. An
     * {@code end} at or before {@code start} ends on the following day.
     */
    public static FixSessionSchedule daily(LocalTime start, LocalTime end, ZoneId zone) {
        return new FixSessionSchedule(null, start, null, end, zone);
    }

    /**
     * A weekly session from {@code startDay start} to {@code endDay end} local
     * time; the same day and time at both ends means a full week.
     */
    public static FixSessionSchedule weekly(DayOfWeek startDay, LocalTime start,
                                            DayOfWeek endDay, LocalTime end, ZoneId zone) {
        return new FixSessionSchedule(Objects.requireNonNull(startDay, "startDay"), start,
                Objects.requireNonNull(endDay, "endDay"), end, zone);
    }

    /** True for a weekly schedule. */
    public boolean isWeekly() {
        return startDay != null;
    }

    public LocalTime startTime() {
        return startTime;
    }

    public LocalTime endTime() {
        return endTime;
    }

    /** Start day of a weekly schedule, or null for a daily one. */
    public DayOfWeek startDay() {
        return startDay;
    }

    /** End day of a weekly schedule, or null for a daily one. */
    public DayOfWeek endDay() {
        return endDay;
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * Epoch milliseconds at which the latest period starting at or before
     * {@code epochMillis} began. Two instants with the same value belong to the
     * same session period, whether or not the session is open at either.
     */
    public long sessionStartMillis(long epochMillis) {
        return periodStart(epochMillis).toInstant().toEpochMilli();
    }

    /** Epoch milliseconds at which the period containing or preceding {@code epochMillis} closes. */
    public long sessionEndMillis(long epochMillis) {
        return periodEnd(periodStart(epochMillis)).toInstant().toEpochMilli();
    }

    /** True when a logon is allowed at {@code epochMillis}. */
    public boolean isInSession(long epochMillis) {
        return epochMillis < sessionEndMillis(epochMillis);
    }

    /** True when both instants fall in the same session period. */
    public boolean isSameSession(long a, long b) {
        return sessionStartMillis(a) == sessionStartMillis(b);
    }

    private ZonedDateTime periodStart(long epochMillis) {
        Instant t = Instant.ofEpochMilli(epochMillis);
        LocalDate d = t.atZone(zone).toLocalDate();
        int lookback = isWeekly() ? 7 : 1;
        // The latest start at or before t lies on one of the last lookback + 1
        // local dates; one more date covers a start moved by a DST shift.
        for (int back = 0; back <= lookback + 1; back++) {
            LocalDate candidate = d.minusDays(back);
            if (isWeekly() && candidate.getDayOfWeek() != startDay) {
                continue;
            }
            ZonedDateTime start = ZonedDateTime.of(candidate, startTime, zone);
            if (!start.toInstant().isAfter(t)) {
                return start;
            }
        }
        throw new IllegalStateException("no session start found before " + t);
    }

    private ZonedDateTime periodEnd(ZonedDateTime start) {
        int days;
        if (isWeekly()) {
            days = Math.floorMod(endDay.getValue() - startDay.getValue(), 7);
            if (days == 0 && !endTime.isAfter(startTime)) {
                days = 7;
            }
        } else {
            days = endTime.isAfter(startTime) ? 0 : 1;
        }
        return ZonedDateTime.of(start.toLocalDate().plusDays(days), endTime, zone);
    }

    @Override
    public String toString() {
        return isWeekly()
                ? "weekly " + startDay + " " + startTime + " to " + endDay + " " + endTime + " " + zone
                : "daily " + startTime + " to " + endTime + " " + zone;
    }
}

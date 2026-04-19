package com.devodox.stopatestimate.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResetWindowScheduleTest {

    @Test
    void weeklyWindowUsesConfiguredDayAndHour() {
        ResetWindowSchedule schedule = new ResetWindowSchedule(
                "WEEKLY",
                DayOfWeek.MONDAY,
                null,
                9,
                null,
                ZoneId.of("UTC"));

        ResetWindow window = schedule.currentWindow(Instant.parse("2026-04-15T12:00:00Z"));

        assertEquals(Instant.parse("2026-04-13T09:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-04-20T09:00:00Z"), window.endExclusive());
        assertEquals(window.endExclusive(), window.nextResetAt());
    }

    @Test
    void monthlyWindowClampsZeroDayOfMonthToFirstDay() {
        ResetWindowSchedule schedule = new ResetWindowSchedule(
                "MONTHLY",
                null,
                0,
                0,
                null,
                ZoneId.of("UTC"));

        ResetWindow window = schedule.currentWindow(Instant.parse("2026-04-15T12:00:00Z"));

        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), window.endExclusive());
    }

    // ----- TEST-11: month-end clamping in February (leap + non-leap) and DST spring-forward -----

    @Test
    void monthlyDayOfMonth31ClampsToFebruary28InNonLeapYear() {
        ResetWindowSchedule schedule = new ResetWindowSchedule(
                "MONTHLY",
                null,
                31,
                0,
                null,
                ZoneId.of("UTC"));

        // "now" = mid-February 2026 (non-leap). dayOfMonth=31 in Feb clamps to 28.
        ResetWindow window = schedule.currentWindow(Instant.parse("2026-02-15T12:00:00Z"));

        // start rolls back to Jan 31 because Feb 28 is after "now"; end is Feb 28 clamp.
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-02-28T00:00:00Z"), window.endExclusive());
    }

    @Test
    void monthlyDayOfMonth31ClampsToFebruary29InLeapYear() {
        ResetWindowSchedule schedule = new ResetWindowSchedule(
                "MONTHLY",
                null,
                31,
                0,
                null,
                ZoneId.of("UTC"));

        // 2028 is a leap year. "now" = mid-February 2028. dayOfMonth=31 clamps to 29.
        ResetWindow window = schedule.currentWindow(Instant.parse("2028-02-15T12:00:00Z"));

        assertEquals(Instant.parse("2028-01-31T00:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2028-02-29T00:00:00Z"), window.endExclusive());
    }

    @Test
    void monthlyHandlesDstSpringForwardInAmericaNewYork() {
        // America/New_York spring-forward 2026 = 2026-03-08, where the local clock jumps from
        // 01:59-05:00 directly to 03:00-04:00. A 02:00 wall-clock reset on that day hits the
        // non-existent hour. ZonedDateTime.withHour resolves gaps by **retaining the pre-transition
        // offset** (−05:00), so 02:00 local becomes instant 07:00Z — one hour later than the
        // equally-plausible post-transition resolution 06:00Z. Pinning this behavior here guards
        // against surprise if we ever swap in `java.time` strict resolution.
        ResetWindowSchedule schedule = new ResetWindowSchedule(
                "MONTHLY",
                null,
                8,
                2,
                null,
                ZoneId.of("America/New_York"));

        ResetWindow window = schedule.currentWindow(Instant.parse("2026-03-10T12:00:00Z"));

        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), window.startInclusive());
        // April has no DST transition on day 8; 02:00 America/New_York (−04:00 after DST) = 06:00Z.
        assertEquals(Instant.parse("2026-04-08T06:00:00Z"), window.endExclusive());
    }

    @Test
    void yearlyWindowRespectsConfiguredMonthAndDay() {
        ResetWindowSchedule schedule = new ResetWindowSchedule(
                "YEARLY",
                null,
                5,
                3,
                Month.JULY,
                ZoneId.of("UTC"));

        ResetWindow window = schedule.currentWindow(Instant.parse("2026-08-01T00:00:00Z"));

        assertEquals(Instant.parse("2026-07-05T03:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2027-07-05T03:00:00Z"), window.endExclusive());
    }
}

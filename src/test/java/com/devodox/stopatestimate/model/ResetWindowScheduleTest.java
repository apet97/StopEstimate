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

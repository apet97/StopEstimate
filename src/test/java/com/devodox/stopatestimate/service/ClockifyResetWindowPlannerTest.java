package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.ResetWindowSchedule;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClockifyResetWindowPlannerTest {

    private final ClockifyResetWindowPlanner planner = new ClockifyResetWindowPlanner();

    @Test
    void nullZoneFallsBackToUtc() {
        ResetWindowSchedule schedule = planner.deriveSchedule("MONTHLY", null, (ZoneId) null);
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void explicitZoneIsThreadedThrough() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ResetWindowSchedule schedule = planner.deriveSchedule("WEEKLY", null, berlin);
        assertThat(schedule.zoneId()).isEqualTo(berlin);
    }

    @Test
    void legacyNoZoneOverloadStillDefaultsToUtc() {
        ResetWindowSchedule schedule = planner.deriveSchedule("YEARLY", null);
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void resetOptionFromEstimateDrivesIntervalWithCustomZone() {
        // Pins the non-UTC boundary: a WEEKLY schedule in Europe/Berlin must carry Berlin's zone so
        // DST transitions (spring-forward / fall-back) don't slide the reset boundary by an hour.
        JsonObject estimateReset = new JsonObject();
        estimateReset.addProperty("interval", "WEEKLY");
        estimateReset.addProperty("dayOfWeek", "MONDAY");
        estimateReset.addProperty("hour", 3);

        ResetWindowSchedule schedule = planner.deriveSchedule(
                null, estimateReset, ZoneId.of("Europe/Berlin"));

        assertThat(schedule.interval()).isEqualTo("WEEKLY");
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("Europe/Berlin"));
    }
}

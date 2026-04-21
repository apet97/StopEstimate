package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ResetWindow;
import com.devodox.stopatestimate.model.ResetWindowSchedule;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;
import java.util.Locale;

@Component
public class ClockifyResetWindowPlanner {

    public ResetWindow currentWindow(ProjectCaps caps, Instant now) {
        if (caps == null || caps.resetWindowSchedule() == null) {
            return ResetWindowSchedule.none().currentWindow(now);
        }
        return caps.resetWindowSchedule().currentWindow(now);
    }

    public ResetWindowSchedule deriveSchedule(String defaultResetCadence, JsonObject estimateReset, String... resetOptions) {
        return deriveSchedule(defaultResetCadence, estimateReset, null, resetOptions);
    }

    /**
     * Overload that threads the workspace timezone through so DST-sensitive reset boundaries
     * (e.g. "first of the month at 00:00 local") align with how users see them in Clockify's UI.
     * Passing {@code null} for {@code zone} falls back to UTC, matching legacy behaviour.
     */
    public ResetWindowSchedule deriveSchedule(
            String defaultResetCadence,
            JsonObject estimateReset,
            ZoneId zone,
            String... resetOptions) {
        String interval = null;
        if (estimateReset != null) {
            interval = ClockifyJson.string(estimateReset, "interval").orElse(null);
        }
        if (interval == null) {
            for (String resetOption : resetOptions) {
                if (resetOption != null && !resetOption.isBlank()) {
                    interval = resetOption;
                    break;
                }
            }
        }
        if (interval == null || interval.isBlank()) {
            interval = defaultResetCadence;
        }
        if (interval == null || interval.isBlank() || "NONE".equalsIgnoreCase(interval)) {
            return ResetWindowSchedule.none();
        }

        DayOfWeek dayOfWeek = estimateReset == null ? null : ClockifyJson.string(estimateReset, "dayOfWeek")
                .map(value -> DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT)))
                .orElse(null);
        Integer dayOfMonth = estimateReset == null || !estimateReset.has("dayOfMonth")
                ? null
                : Math.max(1, estimateReset.get("dayOfMonth").getAsInt());
        Integer hour = estimateReset == null || !estimateReset.has("hour")
                ? null
                : Math.max(0, estimateReset.get("hour").getAsInt());
        Month month = estimateReset == null ? null : ClockifyJson.string(estimateReset, "month")
                .map(value -> Month.valueOf(value.toUpperCase(Locale.ROOT)))
                .orElse(null);

        return new ResetWindowSchedule(
                interval.toUpperCase(Locale.ROOT),
                dayOfWeek,
                dayOfMonth,
                hour,
                month,
                zone == null ? ZoneId.of("UTC") : zone);
    }
}

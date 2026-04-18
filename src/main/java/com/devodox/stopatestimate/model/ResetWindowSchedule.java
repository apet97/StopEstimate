package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

public record ResetWindowSchedule(
		String interval,
		DayOfWeek dayOfWeek,
		Integer dayOfMonth,
		Integer hourOfDay,
		Month month,
		ZoneId zoneId) implements Serializable {

	public static ResetWindowSchedule none() {
		return new ResetWindowSchedule(null, null, null, null, null, ZoneId.of("UTC"));
	}

	public boolean hasReset() {
		return interval != null && !interval.isBlank();
	}

	public ResetWindow currentWindow(Instant now) {
		if (!hasReset()) {
			return new ResetWindow(Instant.EPOCH, now, null);
		}

		ZonedDateTime zonedNow = now.atZone(zoneId == null ? ZoneId.of("UTC") : zoneId);
		ZonedDateTime start;
		ZonedDateTime end;
		String normalized = interval.toUpperCase(Locale.ROOT);
		switch (normalized) {
			case "WEEKLY" -> {
				DayOfWeek targetDay = dayOfWeek == null ? DayOfWeek.MONDAY : dayOfWeek;
				start = zonedNow.with(TemporalAdjusters.previousOrSame(targetDay))
						.withHour(hourOrDefault())
						.withMinute(0)
						.withSecond(0)
						.withNano(0);
				if (start.isAfter(zonedNow)) {
					start = start.minusWeeks(1);
				}
				end = start.plusWeeks(1);
			}
			case "MONTHLY" -> {
				start = zonedNow.withDayOfMonth(validDayOfMonth(zonedNow))
						.withHour(hourOrDefault())
						.withMinute(0)
						.withSecond(0)
						.withNano(0);
				if (start.isAfter(zonedNow)) {
					ZonedDateTime previous = zonedNow.minusMonths(1);
					start = previous.withDayOfMonth(validDayOfMonth(previous))
							.withHour(hourOrDefault())
							.withMinute(0)
							.withSecond(0)
							.withNano(0);
				}
				ZonedDateTime nextMonth = start.plusMonths(1);
				end = nextMonth.withDayOfMonth(validDayOfMonth(nextMonth))
						.withHour(hourOrDefault())
						.withMinute(0)
						.withSecond(0)
						.withNano(0);
			}
			case "YEARLY" -> {
				Month targetMonth = month == null ? Month.JANUARY : month;
				int configuredDay = dayOfMonth == null ? 1 : dayOfMonth;
				start = zonedNow.withMonth(targetMonth.getValue())
						.withDayOfMonth(Math.min(configuredDay, targetMonth.length(zonedNow.toLocalDate().isLeapYear())))
						.withHour(hourOrDefault())
						.withMinute(0)
						.withSecond(0)
						.withNano(0);
				if (start.isAfter(zonedNow)) {
					start = start.minusYears(1);
				}
				ZonedDateTime nextYear = start.plusYears(1);
				Month nextMonthValue = month == null ? nextYear.getMonth() : month;
				end = nextYear.withMonth(nextMonthValue.getValue())
						.withDayOfMonth(Math.min(configuredDay, nextMonthValue.length(nextYear.toLocalDate().isLeapYear())))
						.withHour(hourOrDefault())
						.withMinute(0)
						.withSecond(0)
						.withNano(0);
			}
			default -> {
				return new ResetWindow(Instant.EPOCH, now, null);
			}
		}
		return new ResetWindow(start.toInstant(), end.toInstant(), end.toInstant());
	}

	private int validDayOfMonth(ZonedDateTime reference) {
		int day = dayOfMonth == null ? 1 : Math.max(1, dayOfMonth);
		return Math.min(day, reference.toLocalDate().lengthOfMonth());
	}

	private int hourOrDefault() {
		return hourOfDay == null ? 0 : hourOfDay;
	}
}

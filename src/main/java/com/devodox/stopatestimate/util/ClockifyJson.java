package com.devodox.stopatestimate.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class ClockifyJson {

	private ClockifyJson() {
	}

	public static Optional<String> string(JsonObject object, String field) {
		if (object == null || field == null || !object.has(field)) {
			return Optional.empty();
		}
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull()) {
			return Optional.empty();
		}
		String text = value.getAsString();
		return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
	}

	public static String requiredString(JsonObject object, String field) {
		return string(object, field)
				.orElseThrow(() -> new IllegalArgumentException("Missing required field: " + field));
	}

	public static Optional<Boolean> bool(JsonObject object, String field) {
		if (object == null || field == null || !object.has(field)) {
			return Optional.empty();
		}
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull()) {
			return Optional.empty();
		}
		return Optional.of(value.getAsBoolean());
	}

	public static JsonObject object(JsonObject object, String field) {
		if (object == null || field == null || !object.has(field)) {
			return null;
		}
		JsonElement value = object.get(field);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	public static JsonArray array(JsonObject object, String field) {
		if (object == null || field == null || !object.has(field)) {
			return null;
		}
		JsonElement value = object.get(field);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	public static long durationMillis(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return 0L;
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			return value.getAsLong();
		}
		String text = value.getAsString();
		if (text == null || text.isBlank()) {
			return 0L;
		}
		if (text.startsWith("P")) {
			return Duration.parse(text).toMillis();
		}
		return Long.parseLong(text);
	}

	public static BigDecimal decimal(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return BigDecimal.ZERO;
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			return value.getAsBigDecimal();
		}
		String text = value.getAsString();
		return text == null || text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
	}

	public static Instant instant(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return null;
		}
		String text = value.getAsString();
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	public static Optional<String> findFirstString(JsonElement root, String... keys) {
		Set<String> targetKeys = new LinkedHashSet<>();
		for (String key : keys) {
			if (key != null && !key.isBlank()) {
				targetKeys.add(key);
			}
		}
		if (targetKeys.isEmpty()) {
			return Optional.empty();
		}
		return findFirstString(root, targetKeys);
	}

	public static String normalizeWebhookPath(String rawPath) {
		if (rawPath == null || rawPath.isBlank()) {
			return rawPath;
		}
		String path = rawPath;
		try {
			URI uri = URI.create(rawPath);
			if (uri.getPath() != null && !uri.getPath().isBlank()) {
				path = uri.getPath();
			}
		} catch (IllegalArgumentException ignored) {
			// Fall through to raw path.
		}
		// Canonicalize: strip a leading /webhook/ prefix if present. Clockify's INSTALLED payload
		// can deliver the webhook path either as a full URL ("https://.../webhook/new-timer-started")
		// or as the bare relative path ("/new-timer-started"), depending on how the addon was
		// registered. Collapsing both to the bare path keeps persisted keys and lookups stable
		// across that shape drift.
		if (path.startsWith("/webhook/")) {
			path = path.substring("/webhook".length());
		}
		return path;
	}

	private static Optional<String> findFirstString(JsonElement root, Set<String> targetKeys) {
		if (root == null || root instanceof JsonNull || root.isJsonNull()) {
			return Optional.empty();
		}
		if (root.isJsonObject()) {
			JsonObject object = root.getAsJsonObject();
			for (String key : targetKeys) {
				Optional<String> direct = string(object, key);
				if (direct.isPresent()) {
					return direct;
				}
			}
			for (String key : object.keySet()) {
				Optional<String> nested = findFirstString(object.get(key), targetKeys);
				if (nested.isPresent()) {
					return nested;
				}
			}
			return Optional.empty();
		}
		if (root.isJsonArray()) {
			for (JsonElement item : root.getAsJsonArray()) {
				Optional<String> nested = findFirstString(item, targetKeys);
				if (nested.isPresent()) {
					return nested;
				}
			}
		}
		return Optional.empty();
	}
}

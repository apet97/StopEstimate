package com.devodox.stopatestimate.util;

import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Surfaces drift when Clockify adds unknown top-level fields to a payload envelope the addon
 * parses. Logs at WARN so operators notice, but never rejects — Clockify may legitimately extend
 * an event shape and we don't want the addon to 4xx its way through a rollout. A per-(context,
 * unknown-field-set) fingerprint is cached for the JVM lifetime so a stable spec addition logs
 * once, not per request.
 */
public final class ClockifyPayloadDrift {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private ClockifyPayloadDrift() {
    }

    /**
     * Warn about top-level fields in {@code payload} that are not listed in {@code knownFields}.
     * Intended for the Clockify lifecycle envelopes (INSTALLED, DELETED, STATUS_CHANGED,
     * SETTINGS_UPDATED) where the allowlist is small and stable.
     *
     * @param log          caller-supplied logger so the WARN line is attributed to the caller's
     *                     class rather than this utility.
     * @param context      short human-readable label included in the WARN line (e.g.
     *                     {@code "lifecycle.installed"}).
     * @param payload      the parsed JSON object.
     * @param knownFields  fields the caller knows about and reads. Unknown keys trigger the warning.
     */
    public static void warnUnknownTopLevelFields(
            Logger log, String context, JsonObject payload, Collection<String> knownFields) {
        if (payload == null || knownFields == null) {
            return;
        }
        TreeSet<String> unknown = new TreeSet<>();
        for (String key : payload.keySet()) {
            if (!knownFields.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        String fingerprint = context + ":" + String.join(",", unknown);
        if (SEEN.add(fingerprint)) {
            // First observation of this (context, unknown-set) — log once, then suppress.
            log.warn("Clockify payload drift for {} — unknown top-level fields: {}. "
                    + "Audit SPEC.md and update the known-field allowlist.", context, unknown);
        }
    }

    /** Exposed for tests so each case starts with a clean dedup cache. */
    public static void resetSeen() {
        SEEN.clear();
    }
}

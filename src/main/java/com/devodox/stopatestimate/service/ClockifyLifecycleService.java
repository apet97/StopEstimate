package com.devodox.stopatestimate.service;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import com.devodox.stopatestimate.config.AddonProperties;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.WebhookCredential;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.devodox.stopatestimate.store.InstallationStore;
import com.devodox.stopatestimate.store.LockSnapshotStore;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ClockifyLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(ClockifyLifecycleService.class);

    private final TokenVerificationService tokenVerificationService;
    private final InstallationStore installationStore;
    private final LockSnapshotStore lockSnapshotStore;
    private final CutoffJobStore cutoffJobStore;
    private final ProjectLockService projectLockService;
    private final AddonProperties addonProperties;
    private final Map<String, String> webhookPathToEvent;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final Gson gson = new Gson();

    public ClockifyLifecycleService(
            TokenVerificationService tokenVerificationService,
            InstallationStore installationStore,
            LockSnapshotStore lockSnapshotStore,
            CutoffJobStore cutoffJobStore,
            ProjectLockService projectLockService,
            AddonProperties addonProperties,
            Map<String, String> webhookPathToEvent,
            Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.tokenVerificationService = tokenVerificationService;
        this.installationStore = installationStore;
        this.lockSnapshotStore = lockSnapshotStore;
        this.cutoffJobStore = cutoffJobStore;
        this.projectLockService = projectLockService;
        this.addonProperties = addonProperties;
        this.webhookPathToEvent = webhookPathToEvent;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void handleInstalled(String rawBody, String lifecycleToken) {
        JsonObject payload = parsePayload(rawBody);
        Map<String, Object> claims = verifyLifecycleToken(lifecycleToken);

        String workspaceId = ClockifyJson.requiredString(payload, "workspaceId");
        String addonId = ClockifyJson.requiredString(payload, "addonId");
        assertMatches(workspaceId, claimString(claims, ClockifySignatureParser.CLAIM_WORKSPACE_ID), "workspace");
        assertMatches(addonId, claimString(claims, ClockifySignatureParser.CLAIM_ADDON_ID), "addon");

        String installationToken = ClockifyJson.requiredString(payload, "authToken");
        Map<String, Object> installationClaims = tokenVerificationService.verifyAndParseClaims(installationToken);
        Instant now = clock.instant();

        boolean enabled = extractBooleanSetting(payload, "enabled").orElse(true);
        String defaultResetCadence = extractStringSetting(payload, "defaultResetCadence").orElse(addonProperties.getDefaultResetCadence());

        Optional<InstallationRecord> existing = installationStore.findByWorkspaceId(workspaceId);
        Instant installedAt = existing.map(InstallationRecord::installedAt).orElse(now);
        String timezone = existing.map(InstallationRecord::timezone).orElse(null);

        InstallationRecord installation = new InstallationRecord(
                workspaceId,
                addonId,
                ClockifyJson.string(payload, "addonUserId").orElse(null),
                claimString(installationClaims, ClockifySignatureParser.CLAIM_USER_ID)
                        .orElse(ClockifyJson.string(payload, "asUser").orElse(null)),
                installationToken,
                claimString(installationClaims, ClockifySignatureParser.CLAIM_BACKEND_URL)
                        .orElse(ClockifyJson.string(payload, "apiUrl").orElseThrow(() -> new IllegalArgumentException("Missing backend URL"))),
                claimString(installationClaims, ClockifySignatureParser.CLAIM_REPORTS_URL)
                        .orElseThrow(() -> new IllegalArgumentException("Missing reports URL")),
                extractWebhookTokens(payload),
                AddonStatus.ACTIVE,
                enabled,
                "ENFORCE",
                normalizeCadence(defaultResetCadence),
                installedAt,
                now,
                timezone);
        installationStore.save(installation);

        if (!installation.active() || !installation.enforcing()) {
            suppressEnforcementQuietly(installation);
            return;
        }
        // Publish after commit so the reconcile listener sees the persisted installation.
        // Clockify's API gateway may not have activated the installation token by the time
        // this callback arrives; the listener attempts reconcile once and falls back to a
        // 2s/5s/10s async backoff on failure. Scheduler 60s tick remains the final backstop.
        eventPublisher.publishEvent(new LifecycleReconcileRequestedEvent(
                workspaceId, "lifecycle:installed", true));
    }

    @Transactional
    public void handleDeleted(String rawBody, String lifecycleToken) {
        JsonObject payload = parsePayload(rawBody);
        Map<String, Object> claims = verifyLifecycleToken(lifecycleToken);
        String workspaceId = ClockifyJson.requiredString(payload, "workspaceId");
        assertMatches(workspaceId, claimString(claims, ClockifySignatureParser.CLAIM_WORKSPACE_ID), "workspace");

        installationStore.deleteByWorkspaceId(workspaceId);
        lockSnapshotStore.deleteByWorkspaceId(workspaceId);
        cutoffJobStore.deleteByWorkspaceId(workspaceId);
    }

    @Transactional
    public void handleStatusChanged(String rawBody, String lifecycleToken) {
        JsonObject payload = parsePayload(rawBody);
        Map<String, Object> claims = verifyLifecycleToken(lifecycleToken);
        String workspaceId = ClockifyJson.requiredString(payload, "workspaceId");
        assertMatches(workspaceId, claimString(claims, ClockifySignatureParser.CLAIM_WORKSPACE_ID), "workspace");

        InstallationRecord installation = installationStore.findByWorkspaceId(workspaceId).orElse(null);
        if (installation == null) {
            // SPEC §8: STATUS_CHANGED for an unknown workspace is a safe-ignore 200, not 400.
            log.debug("STATUS_CHANGED for unknown workspace {} — safe-ignoring.", workspaceId);
            return;
        }
        AddonStatus status = "ACTIVE".equalsIgnoreCase(ClockifyJson.string(payload, "status").orElse(""))
                ? AddonStatus.ACTIVE
                : AddonStatus.INACTIVE;
        InstallationRecord updated = installation.withStatus(status, clock.instant());
        installationStore.save(updated);

        if (!updated.active() || !updated.enforcing()) {
            suppressEnforcementQuietly(updated);
            return;
        }
        // No backoff here — the token is known-good by now; defer failures to the scheduler.
        eventPublisher.publishEvent(new LifecycleReconcileRequestedEvent(
                workspaceId, "lifecycle:status-changed", false));
    }

    @Transactional
    public void handleSettingsUpdated(String rawBody, String lifecycleToken) {
        JsonObject payload = parsePayload(rawBody);
        Map<String, Object> claims = verifyLifecycleToken(lifecycleToken);
        String workspaceId = ClockifyJson.requiredString(payload, "workspaceId");
        assertMatches(workspaceId, claimString(claims, ClockifySignatureParser.CLAIM_WORKSPACE_ID), "workspace");

        InstallationRecord installation = installationStore.findByWorkspaceId(workspaceId).orElse(null);
        if (installation == null) {
            // SPEC §8: SETTINGS_UPDATED for an unknown workspace is a safe-ignore 200, not 400.
            log.debug("SETTINGS_UPDATED for unknown workspace {} — safe-ignoring.", workspaceId);
            return;
        }
        boolean enabled = extractBooleanSetting(payload, "enabled").orElse(installation.enabled());
        String defaultResetCadence = extractStringSetting(payload, "defaultResetCadence").orElse(installation.defaultResetCadenceValue());

        InstallationRecord updated = installation.withSettings(
                enabled,
                "ENFORCE",
                normalizeCadence(defaultResetCadence),
                clock.instant());
        installationStore.save(updated);

        if (!updated.active() || !updated.enforcing()) {
            suppressEnforcementQuietly(updated);
            return;
        }
        eventPublisher.publishEvent(new LifecycleReconcileRequestedEvent(
                workspaceId, "lifecycle:settings-updated", false));
    }

    private void suppressEnforcementQuietly(InstallationRecord installation) {
        try {
            suppressEnforcement(installation);
        } catch (RuntimeException e) {
            log.warn("Enforcement suppression deferred for workspace {}", installation.workspaceId(), e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<InstallationRecord> findInstallation(String workspaceId) {
        return installationStore.findByWorkspaceId(workspaceId);
    }

    @Transactional(readOnly = true)
    public Iterable<InstallationRecord> findAllInstallations() {
        return installationStore.findAllRecords();
    }

    @Transactional(readOnly = true)
    public Iterable<InstallationRecord> findActiveInstallations() {
        return installationStore.findActiveRecords();
    }

    private void suppressEnforcement(InstallationRecord installation) {
        // cutoffJobStore.deleteByWorkspaceId is the single operation EstimateGuardService's
        // cancelWorkspaceJobs performed — call it directly to avoid reintroducing the
        // lifecycle→guard cycle.
        cutoffJobStore.deleteByWorkspaceId(installation.workspaceId());
        projectLockService.unlockWorkspaceProjects(installation);
    }

    private JsonObject parsePayload(String rawBody) {
        try {
            JsonElement element = gson.fromJson(rawBody, JsonElement.class);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("Lifecycle payload must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new IllegalArgumentException("Malformed lifecycle payload", e);
        }
    }

    private Map<String, Object> verifyLifecycleToken(String lifecycleToken) {
        if (lifecycleToken == null || lifecycleToken.isBlank()) {
            throw new ClockifyRequestAuthException("Missing X-Addon-Lifecycle-Token header");
        }
        try {
            Map<String, Object> claims = tokenVerificationService.verifyAndParseClaims(lifecycleToken);
            if (claims == null || claims.isEmpty()) {
                throw new ClockifyRequestAuthException("Invalid lifecycle token");
            }
            return claims;
        } catch (InvalidAddonTokenException e) {
            throw new ClockifyRequestAuthException("Invalid lifecycle token", e);
        }
    }

    private Map<String, WebhookCredential> extractWebhookTokens(JsonObject payload) {
        Map<String, WebhookCredential> tokens = new LinkedHashMap<>();
        JsonArray webhooks = ClockifyJson.array(payload, "webhooks");
        if (webhooks == null) {
            return tokens;
        }
        for (JsonElement webhook : webhooks) {
            if (!webhook.isJsonObject()) {
                continue;
            }
            JsonObject webhookObject = webhook.getAsJsonObject();
            String path = ClockifyJson.string(webhookObject, "path")
                    .map(ClockifyJson::normalizeWebhookPath)
                    .orElse(null);
            String authToken = ClockifyJson.string(webhookObject, "authToken").orElse(null);
            if (path != null && authToken != null) {
                // Event type is resolved by path against the authoritative manifest mapping; the
                // Clockify INSTALLED payload doesn't carry a reliable eventType per webhook entry.
                String eventType = webhookPathToEvent.get(path);
                tokens.put(path, new WebhookCredential(eventType, authToken));
            }
        }
        return tokens;
    }

    private Optional<Boolean> extractBooleanSetting(JsonObject payload, String id) {
        JsonArray settings = ClockifyJson.array(payload, "settings");
        if (settings == null) {
            return Optional.empty();
        }
        for (JsonElement setting : settings) {
            if (!setting.isJsonObject()) {
                continue;
            }
            JsonObject object = setting.getAsJsonObject();
            if (id.equals(ClockifyJson.string(object, "id").orElse(null)) && object.has("value") && !object.get("value").isJsonNull()) {
                JsonElement value = object.get("value");
                if (!value.isJsonPrimitive()) {
                    return Optional.empty();
                }
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    return Optional.of(primitive.getAsBoolean());
                }
                if (primitive.isString()) {
                    return Optional.of(Boolean.parseBoolean(primitive.getAsString().trim()));
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractStringSetting(JsonObject payload, String id) {
        JsonArray settings = ClockifyJson.array(payload, "settings");
        if (settings == null) {
            return Optional.empty();
        }
        for (JsonElement setting : settings) {
            if (!setting.isJsonObject()) {
                continue;
            }
            JsonObject object = setting.getAsJsonObject();
            if (id.equals(ClockifyJson.string(object, "id").orElse(null)) && object.has("value") && !object.get("value").isJsonNull()) {
                return Optional.of(object.get("value").getAsString());
            }
        }
        return Optional.empty();
    }

    private Optional<String> claimString(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    private void assertMatches(String actual, Optional<String> expected, String label) {
        // Fail closed when the claim is missing — a token without the expected claim must not
        // silently pass the identity check just because the required value was absent.
        if (expected.isEmpty() || !expected.get().equals(actual)) {
            throw new ClockifyRequestAuthException("Lifecycle token " + label + " mismatch");
        }
    }

    private String normalizeCadence(String value) {
        String raw = value == null || value.isBlank() ? addonProperties.getDefaultResetCadence() : value;
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT);
    }
}

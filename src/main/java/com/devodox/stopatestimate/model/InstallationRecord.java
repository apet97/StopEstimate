package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

public record InstallationRecord(
        String workspaceId,
        String addonId,
        String addonUserId,
        String ownerUserId,
        String installationToken,
        String backendUrl,
        String reportsUrl,
        Map<String, WebhookCredential> webhookAuthTokens,
        AddonStatus status,
        boolean enabled,
        String enforcementMode,
        String defaultResetCadence,
        Instant installedAt,
        Instant updatedAt,
        String timezone) implements Serializable {

    public InstallationRecord(
            String workspaceId,
            String addonId,
            String addonUserId,
            String ownerUserId,
            String installationToken,
            String backendUrl,
            String reportsUrl,
            Map<String, WebhookCredential> webhookAuthTokens,
            AddonStatus status,
            boolean enabled,
            String enforcementMode,
            String defaultResetCadence,
            Instant installedAt,
            Instant updatedAt) {
        this(
                workspaceId,
                addonId,
                addonUserId,
                ownerUserId,
                installationToken,
                backendUrl,
                reportsUrl,
                webhookAuthTokens,
                status,
                enabled,
                enforcementMode,
                defaultResetCadence,
                installedAt,
                updatedAt,
                null);
    }

    public InstallationRecord withStatus(AddonStatus newStatus, Instant now) {
        return new InstallationRecord(
                workspaceId,
                addonId,
                addonUserId,
                ownerUserId,
                installationToken,
                backendUrl,
                reportsUrl,
                webhookAuthTokens,
                newStatus,
                enabled,
                enforcementMode,
                defaultResetCadence,
                installedAt,
                now,
                timezone);
    }

    public InstallationRecord withSettings(boolean newEnabled, String newEnforcementMode, String newDefaultResetCadence, Instant now) {
        return new InstallationRecord(
                workspaceId,
                addonId,
                addonUserId,
                ownerUserId,
                installationToken,
                backendUrl,
                reportsUrl,
                webhookAuthTokens,
                status,
                newEnabled,
                newEnforcementMode,
                newDefaultResetCadence,
                installedAt,
                now,
                timezone);
    }

    public InstallationRecord withTimezone(String newTimezone) {
        return new InstallationRecord(
                workspaceId,
                addonId,
                addonUserId,
                ownerUserId,
                installationToken,
                backendUrl,
                reportsUrl,
                webhookAuthTokens,
                status,
                enabled,
                enforcementMode,
                defaultResetCadence,
                installedAt,
                updatedAt,
                newTimezone);
    }

    public InstallationRecord withUpdatedAt(Instant now) {
        return new InstallationRecord(
                workspaceId,
                addonId,
                addonUserId,
                ownerUserId,
                installationToken,
                backendUrl,
                reportsUrl,
                webhookAuthTokens,
                status,
                enabled,
                enforcementMode,
                defaultResetCadence,
                installedAt,
                now,
                timezone);
    }

    public boolean active() {
        return status == AddonStatus.ACTIVE && enabled;
    }

    public boolean enforcing() {
        // Stop @ Estimate has no observe-only mode — when the addon is active, enforcement is on.
        return active();
    }

    public String enforcementModeValue() {
        // Always ENFORCE; surfaced for operational sidebar display.
        return "ENFORCE";
    }

    public String defaultResetCadenceValue() {
        return defaultResetCadence == null || defaultResetCadence.isBlank() ? "MONTHLY" : defaultResetCadence.toUpperCase();
    }
}

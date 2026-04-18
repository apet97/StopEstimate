package com.devodox.stopatestimate.config;

import com.cake.clockify.addonsdk.clockify.model.ClockifyManifest;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyComponent;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyLifecycleEvent;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyScope;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifySetting;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifySettings;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifySettingsTab;
import com.cake.clockify.addonsdk.clockify.model.v1_3.ClockifyWebhook;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Manifest schema version 1.3 restricts the webhook event enum to 22 events — it does NOT include
 * {@code PROJECT_UPDATED}, {@code PROJECT_DELETED}, {@code EXPENSE_CREATED}, {@code EXPENSE_UPDATED},
 * {@code EXPENSE_DELETED}, or {@code EXPENSE_RESTORED}. Those events exist in Clockify's runtime
 * Webhooks API ({@code POST /v1/workspaces/{id}/webhooks}) but cannot be declared in a 1.3 manifest.
 * Stop @ Estimate therefore declares only the 5 timer/time-entry events here; project and expense
 * changes that affect cap state are picked up by {@code CutoffJobScheduler}'s periodic reconcile
 * (default 60s), which is acceptable because hard-stop latency is bounded by that interval.
 */
@Configuration
public class AddonManifestConfiguration {

    @Bean
    public ClockifyManifest clockifyManifest(
            AddonProperties properties,
            ClockifyComponent sidebarComponent,
            ClockifyLifecycleEvent installedEvent,
            ClockifyLifecycleEvent deletedEvent,
            ClockifyLifecycleEvent statusChangedEvent,
            ClockifyLifecycleEvent settingsUpdatedEvent,
            ClockifyWebhook newTimerStartedWebhook,
            ClockifyWebhook timerStoppedWebhook,
            ClockifyWebhook newTimeEntryWebhook,
            ClockifyWebhook timeEntryUpdatedWebhook,
            ClockifyWebhook timeEntryDeletedWebhook,
            ClockifySettings addonSettings) {
        return ClockifyManifest.v1_3Builder()
                .key(properties.getKey())
                .name(properties.getName())
                .baseUrl(properties.getBaseUrl())
                .requireProPlan()
                .description(properties.getDescription())
                .components(List.of(sidebarComponent))
                .lifecycle(List.of(installedEvent, deletedEvent, statusChangedEvent, settingsUpdatedEvent))
                .webhooks(List.of(
                        newTimerStartedWebhook,
                        timerStoppedWebhook,
                        newTimeEntryWebhook,
                        timeEntryUpdatedWebhook,
                        timeEntryDeletedWebhook))
                .settings(addonSettings)
                .scopes(List.of(
                        ClockifyScope.TIME_ENTRY_READ,
                        ClockifyScope.TIME_ENTRY_WRITE,
                        ClockifyScope.PROJECT_READ,
                        ClockifyScope.PROJECT_WRITE,
                        ClockifyScope.USER_READ,
                        ClockifyScope.EXPENSE_READ,
                        // Required by ClockifyReportsApiClient calls (summary + expense reports).
                        ClockifyScope.REPORTS_READ))
                .build();
    }

    @Bean
    public ClockifyComponent sidebarComponent(AddonProperties properties) {
        return ClockifyComponent.builder()
                .sidebar()
                .allowAdmins()
                .path("/sidebar")
                .label(properties.getSidebarLabel())
                .build();
    }

    @Bean
    public ClockifyLifecycleEvent installedEvent() {
        return ClockifyLifecycleEvent.builder().path("/lifecycle/installed").onInstalled().build();
    }

    @Bean
    public ClockifyLifecycleEvent deletedEvent() {
        return ClockifyLifecycleEvent.builder().path("/lifecycle/deleted").onDeleted().build();
    }

    @Bean
    public ClockifyLifecycleEvent statusChangedEvent() {
        return ClockifyLifecycleEvent.builder().path("/lifecycle/status-changed").onStatusChanged().build();
    }

    @Bean
    public ClockifyLifecycleEvent settingsUpdatedEvent() {
        return ClockifyLifecycleEvent.builder().path("/lifecycle/settings-updated").onSettingsUpdated().build();
    }

    @Bean
    public ClockifyWebhook newTimerStartedWebhook() {
        return ClockifyWebhook.builder().onNewTimerStarted().path("/webhook/new-timer-started").build();
    }

    @Bean
    public ClockifyWebhook timerStoppedWebhook() {
        return ClockifyWebhook.builder().onTimerStopped().path("/webhook/timer-stopped").build();
    }

    @Bean
    public ClockifyWebhook newTimeEntryWebhook() {
        return ClockifyWebhook.builder().onNewTimeEntry().path("/webhook/new-time-entry").build();
    }

    @Bean
    public ClockifyWebhook timeEntryUpdatedWebhook() {
        return ClockifyWebhook.builder().onTimeEntryUpdated().path("/webhook/time-entry-updated").build();
    }

    @Bean
    public ClockifyWebhook timeEntryDeletedWebhook() {
        return ClockifyWebhook.builder().onTimeEntryDeleted().path("/webhook/time-entry-deleted").build();
    }

    // Note: PROJECT_UPDATED, EXPENSE_CREATED, EXPENSE_UPDATED, EXPENSE_DELETED, and EXPENSE_RESTORED
    // are not declarable under manifest schema 1.3. Their HTTP handlers remain in WebhookController
    // as no-op endpoints so adding them here later (via a schema upgrade or runtime registration)
    // does not require re-wiring. Reconcile for these classes of events runs via CutoffJobScheduler.

    /**
     * Authoritative path → event-type mapping for the webhooks declared in this manifest. Used at
     * install time to populate {@code webhook_registrations.event_type}. Keep in lockstep with the
     * {@code ClockifyWebhook} beans above — the integration test asserts parity.
     */
    @Bean
    public Map<String, String> webhookPathToEvent() {
        // Keys are in the canonical form returned by ClockifyJson.normalizeWebhookPath — the bare
        // route path without the /webhook/ prefix. The controller still listens at /webhook/*;
        // only the persisted lookup key is normalized.
        return Map.of(
                "/new-timer-started",  "NEW_TIMER_STARTED",
                "/timer-stopped",      "TIMER_STOPPED",
                "/new-time-entry",     "NEW_TIME_ENTRY",
                "/time-entry-updated", "TIME_ENTRY_UPDATED",
                "/time-entry-deleted", "TIME_ENTRY_DELETED"
        );
    }

    @Bean
    public ClockifySetting enabledSetting() {
        return ClockifySetting.builder()
                .id("enabled")
                .name("Stop @ Estimate Enabled")
                .allowAdmins()
                .asCheckbox()
                .value(true)
                .description("Master switch for hard-stop enforcement and reconcile.")
                .build();
    }

    @Bean
    public ClockifySetting defaultResetCadenceSetting(AddonProperties properties) {
        return ClockifySetting.builder()
                .id("defaultResetCadence")
                .name("Default Reset Cadence")
                .allowAdmins()
                .asDropdownSingle()
                .value(properties.getDefaultResetCadence())
                .allowedValues(List.of("NONE", "WEEKLY", "MONTHLY", "YEARLY"))
                .description("Fallback reset cadence when a project estimate does not define one.")
                .build();
    }

    @Bean
    public ClockifySettings addonSettings(
            ClockifySetting enabledSetting,
            ClockifySetting defaultResetCadenceSetting) {
        ClockifySettingsTab generalTab = ClockifySettingsTab.builder()
                .id("general")
                .name("General")
                .settings(List.of(enabledSetting, defaultResetCadenceSetting))
                .build();

        return ClockifySettings.builder()
                .tabs(List.of(generalTab))
                .build();
    }
}

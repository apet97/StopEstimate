package com.devodox.stopatestimate.service;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import com.devodox.stopatestimate.config.AddonProperties;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.devodox.stopatestimate.store.InstallationStore;
import com.devodox.stopatestimate.store.LockSnapshotStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-06: covers lifecycle handlers. RS256 verification itself is out of scope here — the token
 * verification service is mocked to return claim maps directly, letting the tests focus on
 * routing, cascade semantics, and setting extraction.
 */
class ClockifyLifecycleServiceTest {

    private static final String WS = "ws-1";
    private static final String ADDON = "addon-1";
    private static final String LIFECYCLE_TOKEN = "lifecycle.jwt.token";
    private static final String INSTALL_TOKEN = "install.jwt.token";

    private TokenVerificationService tokenVerificationService;
    private InstallationStore installationStore;
    private LockSnapshotStore lockSnapshotStore;
    private CutoffJobStore cutoffJobStore;
    private ClockifyCutoffService cutoffService;
    private InstallReconcileRetrier retrier;
    private ProjectLockService projectLockService;
    private ClockifyLifecycleService service;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        tokenVerificationService = Mockito.mock(TokenVerificationService.class);
        installationStore = Mockito.mock(InstallationStore.class);
        lockSnapshotStore = Mockito.mock(LockSnapshotStore.class);
        cutoffJobStore = Mockito.mock(CutoffJobStore.class);
        cutoffService = Mockito.mock(ClockifyCutoffService.class);
        retrier = Mockito.mock(InstallReconcileRetrier.class);
        projectLockService = Mockito.mock(ProjectLockService.class);
        AddonProperties props = new AddonProperties();
        Map<String, String> pathToEvent = new HashMap<>();
        pathToEvent.put("/webhooks/new-time-entry", "NEW_TIME_ENTRY");
        service = new ClockifyLifecycleService(
                tokenVerificationService,
                installationStore,
                lockSnapshotStore,
                cutoffJobStore,
                cutoffService,
                retrier,
                projectLockService,
                props,
                pathToEvent,
                fixedClock);
    }

    @Test
    void handleStatusChangedForUnknownWorkspaceLogsAndReturns() {
        primeLifecycleClaims();
        when(installationStore.findByWorkspaceId(WS)).thenReturn(Optional.empty());

        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", WS);
        payload.addProperty("status", "INACTIVE");

        service.handleStatusChanged(payload.toString(), LIFECYCLE_TOKEN);

        verify(installationStore, never()).save(any(InstallationRecord.class));
        verify(cutoffService, never()).reconcileKnownProjects(anyString(), anyString());
    }

    @Test
    void handleDeletedCascadesAcrossAllThreeStores() {
        primeLifecycleClaims();

        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", WS);

        service.handleDeleted(payload.toString(), LIFECYCLE_TOKEN);

        verify(installationStore).deleteByWorkspaceId(WS);
        verify(lockSnapshotStore).deleteByWorkspaceId(WS);
        verify(cutoffJobStore).deleteByWorkspaceId(WS);
    }

    @Test
    void handleSettingsUpdatedForUnknownWorkspaceReturnsWithoutSaving() {
        primeLifecycleClaims();
        when(installationStore.findByWorkspaceId(WS)).thenReturn(Optional.empty());

        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", WS);

        service.handleSettingsUpdated(payload.toString(), LIFECYCLE_TOKEN);

        verify(installationStore, never()).save(any(InstallationRecord.class));
    }

    @Test
    void handleInstalledStringFalseSettingIsParsedAsBoolean() {
        primeLifecycleClaims();
        when(tokenVerificationService.verifyAndParseClaims(INSTALL_TOKEN))
                .thenReturn(installationTokenClaims());

        JsonObject payload = installedPayload();
        JsonArray settings = new JsonArray();
        settings.add(setting("enabled", "false")); // string "false" rather than boolean
        payload.add("settings", settings);

        service.handleInstalled(payload.toString(), LIFECYCLE_TOKEN);

        ArgumentCaptor<InstallationRecord> captor = ArgumentCaptor.forClass(InstallationRecord.class);
        verify(installationStore).save(captor.capture());
        // extractBooleanSetting's isString() branch must treat "false" as false.
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test
    void handleInstalledPersistsRecordAndReconciles() {
        primeLifecycleClaims();
        when(tokenVerificationService.verifyAndParseClaims(INSTALL_TOKEN))
                .thenReturn(installationTokenClaims());

        JsonObject payload = installedPayload();

        service.handleInstalled(payload.toString(), LIFECYCLE_TOKEN);

        ArgumentCaptor<InstallationRecord> captor = ArgumentCaptor.forClass(InstallationRecord.class);
        verify(installationStore).save(captor.capture());
        InstallationRecord saved = captor.getValue();
        assertThat(saved.workspaceId()).isEqualTo(WS);
        assertThat(saved.addonId()).isEqualTo(ADDON);
        assertThat(saved.status()).isEqualTo(AddonStatus.ACTIVE);
        assertThat(saved.enabled()).isTrue();

        verify(cutoffService).reconcileKnownProjects(WS, "lifecycle:installed");
        verify(retrier).reconcileWithBackoff(WS, "lifecycle:installed");
    }

    @Test
    void handleInstalledMissingAuthTokenThrowsIllegalArgument() {
        primeLifecycleClaims();

        JsonObject payload = installedPayload();
        payload.remove("authToken");

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.handleInstalled(payload.toString(), LIFECYCLE_TOKEN));

        verify(installationStore, never()).save(any(InstallationRecord.class));
    }

    @Test
    void lifecycleTokenWorkspaceMismatchThrowsRequestAuth() {
        when(tokenVerificationService.verifyAndParseClaims(LIFECYCLE_TOKEN))
                .thenReturn(Map.of(
                        ClockifySignatureParser.CLAIM_WORKSPACE_ID, "other-workspace",
                        ClockifySignatureParser.CLAIM_ADDON_ID, ADDON));

        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", WS);

        org.junit.jupiter.api.Assertions.assertThrows(
                ClockifyRequestAuthException.class,
                () -> service.handleDeleted(payload.toString(), LIFECYCLE_TOKEN));
    }

    @Test
    void missingLifecycleTokenThrowsRequestAuth() {
        org.junit.jupiter.api.Assertions.assertThrows(
                ClockifyRequestAuthException.class,
                () -> service.handleDeleted("{\"workspaceId\":\"ws-1\"}", null));
    }

    // ----- fixtures -----

    private void primeLifecycleClaims() {
        when(tokenVerificationService.verifyAndParseClaims(LIFECYCLE_TOKEN))
                .thenReturn(Map.of(
                        ClockifySignatureParser.CLAIM_WORKSPACE_ID, WS,
                        ClockifySignatureParser.CLAIM_ADDON_ID, ADDON));
    }

    private Map<String, Object> installationTokenClaims() {
        return Map.of(
                ClockifySignatureParser.CLAIM_USER_ID, "user-1",
                ClockifySignatureParser.CLAIM_BACKEND_URL, "https://api.clockify.me/api",
                ClockifySignatureParser.CLAIM_REPORTS_URL, "https://reports.api.clockify.me");
    }

    private JsonObject installedPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", WS);
        payload.addProperty("addonId", ADDON);
        payload.addProperty("authToken", INSTALL_TOKEN);
        payload.addProperty("apiUrl", "https://api.clockify.me/api");
        return payload;
    }

    private JsonObject setting(String id, String value) {
        JsonObject s = new JsonObject();
        s.addProperty("id", id);
        s.addProperty("value", value);
        return s;
    }
}

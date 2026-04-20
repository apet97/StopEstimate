package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.WebhookCredential;
import com.devodox.stopatestimate.model.entity.WebhookEventEntity;
import com.devodox.stopatestimate.repository.WebhookEventRepository;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-02: covers {@link ClockifyWebhookService}. The signature RS256 verification itself is
 * tested via {@link TokenVerificationServiceTest}; here we mock the claims map and focus on
 * routing, authz checks, dedup, and payload parsing.
 */
class ClockifyWebhookServiceTest {

    private static final String WS = "ws-1";
    private static final String ADDON = "addon-123";
    private static final String ROUTE = "/webhooks/new-time-entry";
    private static final String SIGNATURE = "mocked.jwt.token";
    private static final String STORED_TOKEN = SIGNATURE; // equality is the contract

    private TokenVerificationService tokenVerificationService;
    private ClockifyLifecycleService lifecycleService;
    private EstimateGuardService cutoffService;
    private WebhookEventRepository webhookEventRepository;
    private ClockifyWebhookService service;

    @BeforeEach
    void setUp() {
        tokenVerificationService = Mockito.mock(TokenVerificationService.class);
        lifecycleService = Mockito.mock(ClockifyLifecycleService.class);
        cutoffService = Mockito.mock(EstimateGuardService.class);
        webhookEventRepository = Mockito.mock(WebhookEventRepository.class);
        service = new ClockifyWebhookService(
                tokenVerificationService, lifecycleService, cutoffService, webhookEventRepository);
    }

    @Test
    void happyPathRoutesNewTimeEntryToReconcileProject() {
        primeValid();

        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        payload.addProperty("workspaceId", WS);

        service.handleWebhook("NEW_TIME_ENTRY", ROUTE, payload.toString(), SIGNATURE, "NEW_TIME_ENTRY");

        verify(cutoffService).reconcileProject(eq(WS), eq("project-1"), eq("webhook:NEW_TIME_ENTRY"), any(JsonObject.class));
    }

    @Test
    void projectUpdatedFallsBackToTopLevelIdFieldAsProjectId() {
        primeValid();

        JsonObject payload = new JsonObject();
        payload.addProperty("id", "project-2");

        service.handleWebhook("PROJECT_UPDATED", ROUTE, payload.toString(), SIGNATURE, "PROJECT_UPDATED");

        verify(cutoffService).reconcileProject(eq(WS), eq("project-2"), eq("webhook:PROJECT_UPDATED"), any(JsonObject.class));
    }

    @Test
    void noProjectIdFallsBackToReconcileKnownProjects() {
        primeValid();

        JsonObject payload = new JsonObject();

        service.handleWebhook("NEW_TIME_ENTRY", ROUTE, payload.toString(), SIGNATURE, "NEW_TIME_ENTRY");

        verify(cutoffService).reconcileKnownProjects(eq(WS), eq("webhook:NEW_TIME_ENTRY"));
        verify(cutoffService, never()).reconcileProject(anyString(), anyString(), anyString(), any());
    }

    @Test
    void missingSignatureHeaderThrowsRequestAuth() {
        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, "{}", null, "NEW_TIME_ENTRY"))
                .isInstanceOf(ClockifyRequestAuthException.class);
    }

    @Test
    void missingEventTypeHeaderThrowsRequestAuth() {
        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, null))
                .isInstanceOf(ClockifyRequestAuthException.class);
    }

    @Test
    void mismatchedEventTypeHeaderThrowsIllegalArgument() {
        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, "SOMETHING_ELSE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidSignatureWrapsInRequestAuth() {
        when(tokenVerificationService.verifyAndParseClaims(SIGNATURE))
                .thenThrow(new InvalidAddonTokenException("bad"));

        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, "NEW_TIME_ENTRY"))
                .isInstanceOf(ClockifyRequestAuthException.class);
    }

    @Test
    void unknownInstallationSilentlyReturns() {
        when(tokenVerificationService.verifyAndParseClaims(SIGNATURE)).thenReturn(validClaims());
        when(lifecycleService.findInstallation(WS)).thenReturn(Optional.empty());

        service.handleWebhook("NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, "NEW_TIME_ENTRY");

        verify(cutoffService, never()).reconcileProject(anyString(), anyString(), anyString(), any());
        verify(cutoffService, never()).reconcileKnownProjects(anyString(), anyString());
    }

    @Test
    void inactiveInstallationSilentlyReturns() {
        when(tokenVerificationService.verifyAndParseClaims(SIGNATURE)).thenReturn(validClaims());
        when(lifecycleService.findInstallation(WS)).thenReturn(Optional.of(installation(false, AddonStatus.INACTIVE)));

        service.handleWebhook("NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, "NEW_TIME_ENTRY");

        verify(cutoffService, never()).reconcileProject(anyString(), anyString(), anyString(), any());
    }

    @Test
    void addonIdMismatchThrowsAccessForbidden() {
        when(tokenVerificationService.verifyAndParseClaims(SIGNATURE))
                .thenReturn(Map.of("workspaceId", WS, "addonId", "different-addon"));
        when(lifecycleService.findInstallation(WS)).thenReturn(Optional.of(installation(true, AddonStatus.ACTIVE)));

        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, "NEW_TIME_ENTRY"))
                .isInstanceOf(ClockifyAccessForbiddenException.class);
    }

    @Test
    void storedWebhookTokenMismatchThrowsAccessForbidden() {
        when(tokenVerificationService.verifyAndParseClaims(SIGNATURE)).thenReturn(validClaims());
        InstallationRecord install = installationWithWebhook("different-token");
        when(lifecycleService.findInstallation(WS)).thenReturn(Optional.of(install));

        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, "{}", SIGNATURE, "NEW_TIME_ENTRY"))
                .isInstanceOf(ClockifyAccessForbiddenException.class);
    }

    @Test
    void payloadWorkspaceMismatchThrowsRequestAuth() {
        primeValid();

        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", "another-workspace");

        assertThatThrownBy(() -> service.handleWebhook(
                "NEW_TIME_ENTRY", ROUTE, payload.toString(), SIGNATURE, "NEW_TIME_ENTRY"))
                .isInstanceOf(ClockifyRequestAuthException.class);
    }

    @Test
    void duplicateDeliveryShortCircuitsBeforeReconcile() {
        primeValid();
        when(webhookEventRepository.save(any(WebhookEventEntity.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        payload.addProperty("eventId", "stable-event-id");

        service.handleWebhook("NEW_TIME_ENTRY", ROUTE, payload.toString(), SIGNATURE, "NEW_TIME_ENTRY");

        verify(cutoffService, never()).reconcileProject(anyString(), anyString(), anyString(), any());
    }

    @Test
    void dedupFallsBackToBodyHashWhenEventIdMissing() {
        primeValid();

        // No eventId, id, or timeEntryId in payload → dedup key must use a sha256 of the raw body.
        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        String rawBody = payload.toString();

        service.handleWebhook("NEW_TIME_ENTRY", ROUTE, rawBody, SIGNATURE, "NEW_TIME_ENTRY");

        ArgumentCaptor<WebhookEventEntity> captor = ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(webhookEventRepository).save(captor.capture());
        // When no eventId/id/timeEntryId is found, the service falls back to "body:<sha256>"
        // without prefixing the event type — see ClockifyWebhookService.isDuplicate.
        String eventId = captor.getValue().getId().getEventId();
        assertThat(eventId).startsWith("body:");
        assertThat(eventId.substring("body:".length())).hasSize(64);
    }

    // ----- fixtures -----

    private void primeValid() {
        when(tokenVerificationService.verifyAndParseClaims(SIGNATURE)).thenReturn(validClaims());
        when(lifecycleService.findInstallation(WS)).thenReturn(Optional.of(installationWithWebhook(STORED_TOKEN)));
    }

    private Map<String, Object> validClaims() {
        return Map.of("workspaceId", WS, "addonId", ADDON);
    }

    private InstallationRecord installation(boolean enabled, AddonStatus status) {
        return new InstallationRecord(
                WS, ADDON, "addon-user", "owner-user", "installation-token",
                "https://api.clockify.me/api", "https://reports.api.clockify.me",
                Map.of(),
                status, enabled, "ENFORCE", "MONTHLY",
                Instant.parse("2026-04-19T10:00:00Z"), Instant.parse("2026-04-19T10:00:00Z"));
    }

    private InstallationRecord installationWithWebhook(String authToken) {
        Map<String, WebhookCredential> tokens = Map.of(
                ROUTE, new WebhookCredential("NEW_TIME_ENTRY", authToken));
        return new InstallationRecord(
                WS, ADDON, "addon-user", "owner-user", "installation-token",
                "https://api.clockify.me/api", "https://reports.api.clockify.me",
                tokens,
                AddonStatus.ACTIVE, true, "ENFORCE", "MONTHLY",
                Instant.parse("2026-04-19T10:00:00Z"), Instant.parse("2026-04-19T10:00:00Z"));
    }
}

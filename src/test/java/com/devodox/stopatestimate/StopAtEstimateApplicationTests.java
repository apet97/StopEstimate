package com.devodox.stopatestimate;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.entity.WebhookRegistrationEntity;
import com.devodox.stopatestimate.repository.WebhookRegistrationRepository;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.devodox.stopatestimate.store.InstallationStore;
import com.devodox.stopatestimate.store.LockSnapshotStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StopAtEstimateApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstallationStore installationStore;

    @Autowired
    private CutoffJobStore cutoffJobStore;

    @Autowired
    private LockSnapshotStore lockSnapshotStore;

    @Autowired
    private WebhookRegistrationRepository webhookRegistrationRepository;

    @MockBean
    private ClockifyBackendApiClient backendApiClient;

    @MockBean
    private ClockifyReportsApiClient reportsApiClient;

    @BeforeEach
    void resetStoresAndMocks() {
        lockSnapshotStore.deleteAllSnapshots();
        cutoffJobStore.deleteAllJobs();
        installationStore.deleteAllRecords();
        Mockito.reset(backendApiClient, reportsApiClient);
        doNothing().when(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        doNothing().when(backendApiClient).updateProjectVisibility(any(), anyString(), Mockito.anyBoolean());
        doNothing().when(backendApiClient).updateProjectMemberships(any(), anyString(), any(), any());
    }

    @Test
    void manifestEndpointReturnsRequiredFields() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.3"))
                .andExpect(jsonPath("$.key").value("stop-at-estimate"))
                .andExpect(jsonPath("$.minimalSubscriptionPlan").value("PRO"))
                .andExpect(jsonPath("$.components[0].type").value("sidebar"))
                .andExpect(jsonPath("$.components[0].accessLevel").value("ADMINS"))
                .andExpect(jsonPath("$.components[0].path").value("/sidebar"))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.lifecycle.length()").value(4))
                // Manifest schema 1.3 does not allow PROJECT_UPDATED or EXPENSE_* events; only the
                // 5 timer/time-entry events are declarable here. Reconcile for the others runs
                // via CutoffJobScheduler's periodic tick.
                .andExpect(jsonPath("$.webhooks.length()").value(5))
                .andExpect(jsonPath("$.scopes.length()").value(7))
                .andExpect(jsonPath("$.settings.tabs[0].settings.length()").value(2))
                .andExpect(jsonPath("$.settings.tabs[0].settings[*].id", containsInAnyOrder("enabled", "defaultResetCadence")))
                .andExpect(jsonPath("$.settings.tabs[0].settings[*].accessLevel", everyItem(is("ADMINS"))));
    }

    @Test
    void webhookTokenFromDifferentRouteIsRejected() throws Exception {
        String workspaceId = "ws-cross-route";
        installWorkspace(workspaceId, TestJwtFactory.webhookToken(workspaceId));

        // The token stored for /webhook/new-time-entry must not authenticate
        // a call to /webhook/timer-stopped.
        mockMvc.perform(post("/webhook/timer-stopped")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", TestJwtFactory.webhookToken(workspaceId))
                        .header("clockify-webhook-event-type", "TIMER_STOPPED")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("webhook_token_mismatch"));
    }

    @Test
    void invalidLifecycleTokenReturns401() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", "not-a-jwt")
                        .content("{\"workspaceId\":\"ws-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lifecycleWorkspaceMismatchReturns401() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken("ws-claims"))
                        .content(installedPayload("ws-body", TestJwtFactory.installationToken("ws-body"), TestJwtFactory.webhookToken("ws-body"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidWebhookSignatureReturns401() throws Exception {
        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", "not-a-jwt")
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void storedWebhookTokenMismatchReturns403() throws Exception {
        String workspaceId = "ws-token-mismatch";
        installWorkspace(workspaceId, TestJwtFactory.webhookToken(workspaceId));

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", TestJwtFactory.webhookToken(workspaceId, Map.of("nonce", "wrong")))
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("webhook_token_mismatch"));
    }

    @Test
    void installStatusSettingsDeleteRoundTrip() throws Exception {
        String workspaceId = "ws-install-delete";
        String installationToken = TestJwtFactory.installationToken(workspaceId);
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayload(workspaceId, installationToken, webhookToken)))
                .andExpect(status().isOk());

        InstallationRecord record = installationStore.findByWorkspaceId(workspaceId).orElseThrow();
        assertThat(record.status()).isEqualTo(AddonStatus.ACTIVE);
        assertThat(record.enabled()).isTrue();
        // Enforcement is hard-wired to ENFORCE — no observe-only mode exists.
        assertThat(record.enforcementModeValue()).isEqualTo("ENFORCE");
        assertThat(record.enforcing()).isTrue();

        mockMvc.perform(post("/lifecycle/status-changed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content("""
                                {
                                  "workspaceId": "%s",
                                  "status": "INACTIVE"
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        assertThat(installationStore.findByWorkspaceId(workspaceId).orElseThrow().status())
                .isEqualTo(AddonStatus.INACTIVE);

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content("""
                                {
                                  "workspaceId": "%s",
                                  "settings": [
                                    {"id": "enabled", "value": false},
                                    {"id": "defaultResetCadence", "value": "YEARLY"}
                                  ]
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        InstallationRecord updated = installationStore.findByWorkspaceId(workspaceId).orElseThrow();
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.defaultResetCadenceValue()).isEqualTo("YEARLY");

        mockMvc.perform(post("/lifecycle/deleted")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content("""
                                {
                                  "workspaceId": "%s"
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        assertThat(installationStore.findByWorkspaceId(workspaceId)).isEmpty();
    }

    @Test
    void reinstallPreservesInstalledAt() throws Exception {
        String workspaceId = "ws-reinstall";
        installWorkspace(workspaceId, TestJwtFactory.webhookToken(workspaceId));
        java.time.Instant firstInstalledAt = installationStore.findByWorkspaceId(workspaceId)
                .orElseThrow()
                .installedAt();

        // Clockify may replay the INSTALLED webhook (retry, manual re-install). The handler must
        // merge: preserve createdAt, refresh tokens + updatedAt. Without this guard, every replay
        // would bounce the install timestamp and break audit trails.
        Thread.sleep(10);
        installWorkspace(workspaceId, TestJwtFactory.webhookToken(workspaceId));

        InstallationRecord afterReinstall = installationStore.findByWorkspaceId(workspaceId).orElseThrow();
        assertThat(afterReinstall.installedAt()).isEqualTo(firstInstalledAt);
        assertThat(afterReinstall.updatedAt()).isAfterOrEqualTo(firstInstalledAt);
    }

    @Test
    void duplicateWebhookDeliveryShortCircuits() throws Exception {
        String workspaceId = "ws-dedup";
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);
        installWorkspace(workspaceId, webhookToken);
        Mockito.reset(backendApiClient, reportsApiClient);
        doNothing().when(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        doNothing().when(backendApiClient).updateProjectVisibility(any(), anyString(), Mockito.anyBoolean());
        doNothing().when(backendApiClient).updateProjectMemberships(any(), anyString(), any(), any());
        stubGuardApis(workspaceId);

        String body = """
                {"workspaceId": "%s", "projectId": "project-1", "id": "time-entry-9"}
                """.formatted(workspaceId);

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content(body))
                .andExpect(status().isOk());

        // Same event + same signature → second delivery is a retry. Must not re-invoke reconcile.
        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content(body))
                .andExpect(status().isOk());

        // The first delivery triggers a project fetch; the second must be short-circuited before
        // any side effect runs. Without dedup, this would be 2 calls.
        Mockito.verify(backendApiClient, Mockito.times(1)).getProject(any(), Mockito.eq("project-1"));
    }

    @Test
    void apiContextRejectsMissingOrInvalidToken() throws Exception {
        mockMvc.perform(get("/api/context"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/context").header("X-Addon-Token", "not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guardProjectsEndpointReturnsSummaries() throws Exception {
        String workspaceId = "ws-guard-projects";
        installWorkspace(workspaceId, TestJwtFactory.webhookToken(workspaceId));
        stubGuardApis(workspaceId);

        mockMvc.perform(get("/api/guard/projects")
                        .header("X-Addon-Token", TestJwtFactory.userToken(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.projects[0].projectId").value("project-1"));
    }

    @Test
    void installPersistsWebhookEventTypes() throws Exception {
        String workspaceId = "ws-event-types";
        String installationToken = TestJwtFactory.installationToken(workspaceId);
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayloadAllWebhooks(workspaceId, installationToken, webhookToken)))
                .andExpect(status().isOk());

        Map<String, String> byPath = new java.util.HashMap<>();
        for (WebhookRegistrationEntity row : webhookRegistrationRepository.findAllByWorkspaceId(workspaceId)) {
            byPath.put(row.getRoutePath(), row.getEventType());
        }
        assertThat(byPath).containsEntry("/new-timer-started", "NEW_TIMER_STARTED");
        assertThat(byPath).containsEntry("/timer-stopped", "TIMER_STOPPED");
        assertThat(byPath).containsEntry("/new-time-entry", "NEW_TIME_ENTRY");
        assertThat(byPath).containsEntry("/time-entry-updated", "TIME_ENTRY_UPDATED");
        assertThat(byPath).containsEntry("/time-entry-deleted", "TIME_ENTRY_DELETED");
        assertThat(byPath.values()).allSatisfy(v -> assertThat(v).isNotNull());
    }

    @Test
    void manualReconcileReturnsSummaries() throws Exception {
        String workspaceId = "ws-reconcile";
        installWorkspace(workspaceId, TestJwtFactory.webhookToken(workspaceId));
        stubGuardApis(workspaceId);

        mockMvc.perform(post("/api/guard/reconcile")
                        .header("X-Addon-Token", TestJwtFactory.userToken(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.projects[0].projectName").value("Guarded Project"));
    }

    private void installWorkspace(String workspaceId, String webhookToken) throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayload(workspaceId, TestJwtFactory.installationToken(workspaceId), webhookToken)))
                .andExpect(status().isOk());
    }

    private void stubGuardApis(String workspaceId) {
        JsonObject projectSummary = new JsonObject();
        projectSummary.addProperty("id", "project-1");
        projectSummary.addProperty("name", "Guarded Project");
        projectSummary.addProperty("public", true);

        JsonObject timeEstimate = new JsonObject();
        timeEstimate.addProperty("active", true);
        timeEstimate.addProperty("estimate", 7200000L);
        timeEstimate.addProperty("resetOption", "MONTHLY");
        projectSummary.add("timeEstimate", timeEstimate);

        JsonObject budgetEstimate = new JsonObject();
        budgetEstimate.addProperty("active", true);
        budgetEstimate.addProperty("estimate", 50000);
        budgetEstimate.addProperty("resetOption", "MONTHLY");
        budgetEstimate.addProperty("includeExpenses", true);
        projectSummary.add("budgetEstimate", budgetEstimate);

        projectSummary.add("memberships", new JsonArray());
        projectSummary.add("userGroups", new JsonArray());
        projectSummary.addProperty("duration", 3600000L);

        when(backendApiClient.listProjects(any(InstallationRecord.class))).thenReturn(java.util.List.of(projectSummary));
        when(backendApiClient.getProject(any(InstallationRecord.class), anyString())).thenReturn(projectSummary);
        when(backendApiClient.listInProgressTimeEntries(any(InstallationRecord.class))).thenReturn(java.util.List.of());

        JsonObject reportTotals = new JsonObject();
        reportTotals.addProperty("totalTime", 3600000L);
        JsonArray amounts = new JsonArray();
        JsonObject cost = new JsonObject();
        cost.addProperty("type", "COST");
        cost.addProperty("amount", 1000);
        amounts.add(cost);
        reportTotals.add("amounts", amounts);
        JsonArray summaryTotals = new JsonArray();
        summaryTotals.add(reportTotals);
        JsonObject summaryReport = new JsonObject();
        summaryReport.add("totals", summaryTotals);

        JsonObject expenseTotals = new JsonObject();
        expenseTotals.addProperty("totalAmount", 100);
        JsonObject expenseReport = new JsonObject();
        expenseReport.add("totals", expenseTotals);

        when(reportsApiClient.generateSummaryReport(any(InstallationRecord.class), any(JsonObject.class))).thenReturn(summaryReport);
        when(reportsApiClient.generateExpenseReport(any(InstallationRecord.class), any(JsonObject.class))).thenReturn(expenseReport);
    }

    private static String installedPayload(String workspaceId, String installationToken, String webhookToken) {
        return """
                {
                  "addonId": "addon-123",
                  "authToken": "%s",
                  "workspaceId": "%s",
                  "asUser": "user-123",
                  "apiUrl": "https://api.clockify.me/api",
                  "addonUserId": "addon-user-123",
                  "settings": [
                    {"id": "enabled", "value": true},
                    {"id": "defaultResetCadence", "value": "MONTHLY"}
                  ],
                  "webhooks": [
                    {
                      "path": "https://example.test/webhook/new-time-entry",
                      "webhookType": "ADDON",
                      "authToken": "%s"
                    }
                  ]
                }
                """.formatted(installationToken, workspaceId, webhookToken);
    }

    private static String installedPayloadAllWebhooks(String workspaceId, String installationToken, String webhookToken) {
        return """
                {
                  "addonId": "addon-123",
                  "authToken": "%s",
                  "workspaceId": "%s",
                  "asUser": "user-123",
                  "apiUrl": "https://api.clockify.me/api",
                  "addonUserId": "addon-user-123",
                  "settings": [
                    {"id": "enabled", "value": true},
                    {"id": "defaultResetCadence", "value": "MONTHLY"}
                  ],
                  "webhooks": [
                    {"path": "https://example.test/webhook/new-timer-started",  "webhookType": "ADDON", "authToken": "%s"},
                    {"path": "https://example.test/webhook/timer-stopped",      "webhookType": "ADDON", "authToken": "%s"},
                    {"path": "https://example.test/webhook/new-time-entry",     "webhookType": "ADDON", "authToken": "%s"},
                    {"path": "https://example.test/webhook/time-entry-updated", "webhookType": "ADDON", "authToken": "%s"},
                    {"path": "https://example.test/webhook/time-entry-deleted", "webhookType": "ADDON", "authToken": "%s"}
                  ]
                }
                """.formatted(installationToken, workspaceId,
                        webhookToken, webhookToken, webhookToken, webhookToken, webhookToken);
    }
}

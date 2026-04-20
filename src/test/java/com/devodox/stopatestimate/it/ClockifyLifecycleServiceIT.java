package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.TestJwtFactory;
import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.scheduler.CutoffJobScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ClockifyLifecycleServiceIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClockifyBackendApiClient backendApiClient;

    @MockBean
    private ClockifyReportsApiClient reportsApiClient;

    @MockBean
    private CutoffJobScheduler ignoredScheduler;

    @BeforeEach
    void resetClockifyMocks() {
        Mockito.reset(backendApiClient, reportsApiClient);
        doNothing().when(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        doNothing().when(backendApiClient).updateProjectVisibility(any(), anyString(), Mockito.anyBoolean());
        doNothing().when(backendApiClient).updateProjectMemberships(any(), anyString(), any(), any());
        when(backendApiClient.filterUsers(any(), anyList(), nullable(String.class), anyString()))
                .thenReturn(List.of(user("owner-user-123")));
        when(backendApiClient.listProjects(any(InstallationRecord.class))).thenReturn(List.of());
        when(backendApiClient.listInProgressTimeEntries(any(InstallationRecord.class))).thenReturn(List.of());
        when(backendApiClient.getWorkspace(any(InstallationRecord.class))).thenReturn(workspace("Europe/Belgrade"));
        when(reportsApiClient.generateSummaryReport(any(InstallationRecord.class), any(JsonObject.class)))
                .thenReturn(summaryReport(0));
    }

    @Test
    void install_thenEventFires_installationTimezoneLockAndCutoffVisible() throws Exception {
        String workspaceId = "ws-install-it";
        String installationToken = TestJwtFactory.installationToken(workspaceId);
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        JsonObject lockProject = project("project-lock", "Locked Project", 3_600_000L);
        JsonObject cutoffProject = project("project-cutoff", "Cutoff Project", 3_600_000L);

        when(backendApiClient.listProjects(any(InstallationRecord.class)))
                .thenReturn(List.of(lockProject, cutoffProject));
        when(backendApiClient.listInProgressTimeEntries(any(InstallationRecord.class)))
                .thenReturn(List.of(runningEntry(
                        "project-cutoff",
                        "user-1",
                        "time-entry-1",
                        Instant.now().minus(Duration.ofMinutes(5)))));
        when(backendApiClient.getProject(any(InstallationRecord.class), anyString()))
                .thenAnswer(invocation -> switch (invocation.getArgument(1, String.class)) {
                    case "project-lock" -> lockProject;
                    case "project-cutoff" -> cutoffProject;
                    default -> throw new IllegalArgumentException("Unexpected project");
                });
        when(reportsApiClient.generateSummaryReport(any(InstallationRecord.class), any(JsonObject.class)))
                .thenAnswer(invocation -> {
                    JsonObject filter = invocation.getArgument(1, JsonObject.class);
                    String projectId = filter.getAsJsonObject("projects")
                            .getAsJsonArray("ids")
                            .get(0)
                            .getAsString();
                    return switch (projectId) {
                        case "project-lock" -> summaryReport(7_200);
                        case "project-cutoff" -> summaryReport(1_200);
                        default -> summaryReport(0);
                    };
                });

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayload(workspaceId, installationToken, webhookToken)))
                .andExpect(status().isOk());

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    assertThat(installationStore.findByWorkspaceId(workspaceId))
                            .isPresent()
                            .get()
                            .extracting(InstallationRecord::timezone)
                            .isEqualTo("Europe/Belgrade");
                    assertThat(countRows(
                            "select count(*) from project_lock_snapshots where workspace_id = ? and project_id = ?",
                            workspaceId,
                            "project-lock")).isEqualTo(1);
                    assertThat(countRows(
                            "select count(*) from cutoff_jobs where workspace_id = ? and project_id = ?",
                            workspaceId,
                            "project-cutoff")).isEqualTo(1);
                    assertThat(countRows(
                            "select count(*) from guard_events where workspace_id = ? and event_type = ?",
                            workspaceId,
                            "LOCKED")).isEqualTo(1);
                    assertThat(countRows(
                            "select count(*) from guard_events where workspace_id = ? and event_type = ?",
                            workspaceId,
                            "CUTOFF_SCHEDULED")).isEqualTo(1);
                });
    }

    @Test
    void reinstall_isIdempotent() throws Exception {
        String workspaceId = "ws-reinstall-it";
        String firstWebhookToken = TestJwtFactory.webhookToken(workspaceId, Map.of("nonce", "first"));
        String secondWebhookToken = TestJwtFactory.webhookToken(workspaceId, Map.of("nonce", "second"));

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayload(
                                workspaceId,
                                TestJwtFactory.installationToken(workspaceId),
                                firstWebhookToken)))
                .andExpect(status().isOk());

        Instant firstInstalledAt = installationStore.findByWorkspaceId(workspaceId)
                .orElseThrow()
                .installedAt();

        Thread.sleep(25L);

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayload(
                                workspaceId,
                                TestJwtFactory.installationToken(workspaceId),
                                secondWebhookToken)))
                .andExpect(status().isOk());

        InstallationRecord record = installationStore.findByWorkspaceId(workspaceId).orElseThrow();

        assertThat(countRows("select count(*) from installations where workspace_id = ?", workspaceId)).isEqualTo(1);
        assertThat(record.installedAt()).isEqualTo(firstInstalledAt);
        assertThat(record.webhookAuthTokens())
                .containsKey("/new-time-entry");
        assertThat(record.webhookAuthTokens().get("/new-time-entry").authToken()).isEqualTo(secondWebhookToken);
    }

    @Test
    void delete_cascadesAllChildTables() throws Exception {
        String workspaceId = "ws-delete-it";
        String installationToken = TestJwtFactory.installationToken(workspaceId);
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        JsonObject lockProject = project("project-lock-delete", "Locked Project", 3_600_000L);
        JsonObject cutoffProject = project("project-cutoff-delete", "Cutoff Project", 3_600_000L);

        when(backendApiClient.listProjects(any(InstallationRecord.class)))
                .thenReturn(List.of(lockProject, cutoffProject));
        when(backendApiClient.listInProgressTimeEntries(any(InstallationRecord.class)))
                .thenReturn(List.of(runningEntry(
                        "project-cutoff-delete",
                        "user-1",
                        "time-entry-delete",
                        Instant.now().minus(Duration.ofMinutes(5)))));
        when(backendApiClient.getProject(any(InstallationRecord.class), anyString()))
                .thenAnswer(invocation -> switch (invocation.getArgument(1, String.class)) {
                    case "project-lock-delete" -> lockProject;
                    case "project-cutoff-delete" -> cutoffProject;
                    default -> throw new IllegalArgumentException("Unexpected project");
                });
        when(reportsApiClient.generateSummaryReport(any(InstallationRecord.class), any(JsonObject.class)))
                .thenAnswer(invocation -> {
                    JsonObject filter = invocation.getArgument(1, JsonObject.class);
                    String projectId = filter.getAsJsonObject("projects")
                            .getAsJsonArray("ids")
                            .get(0)
                            .getAsString();
                    return switch (projectId) {
                        case "project-lock-delete" -> summaryReport(7_200);
                        case "project-cutoff-delete" -> summaryReport(1_200);
                        default -> summaryReport(0);
                    };
                });

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content(installedPayload(workspaceId, installationToken, webhookToken)))
                .andExpect(status().isOk());

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    assertThat(countRows("select count(*) from project_lock_snapshots where workspace_id = ?", workspaceId))
                            .isEqualTo(1);
                    assertThat(countRows("select count(*) from cutoff_jobs where workspace_id = ?", workspaceId))
                            .isEqualTo(1);
                    assertThat(countRows("select count(*) from guard_events where workspace_id = ?", workspaceId))
                            .isGreaterThanOrEqualTo(2);
                });

        mockMvc.perform(post("/lifecycle/deleted")
                        .contentType(APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", TestJwtFactory.lifecycleToken(workspaceId))
                        .content("""
                                {
                                  "workspaceId": "%s"
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        assertThat(countRows("select count(*) from installations where workspace_id = ?", workspaceId)).isEqualTo(0);
        assertThat(countRows("select count(*) from project_lock_snapshots where workspace_id = ?", workspaceId)).isEqualTo(0);
        assertThat(countRows("select count(*) from cutoff_jobs where workspace_id = ?", workspaceId)).isEqualTo(0);
        assertThat(countRows("select count(*) from guard_events where workspace_id = ?", workspaceId)).isEqualTo(0);
    }

    private static JsonObject workspace(String timezone) {
        JsonObject object = new JsonObject();
        object.addProperty("id", "workspace-1");
        object.addProperty("timeZone", timezone);
        return object;
    }

    private static JsonObject user(String userId) {
        JsonObject user = new JsonObject();
        user.addProperty("id", userId);
        return user;
    }

    private static JsonObject runningEntry(String projectId, String userId, String timeEntryId, Instant start) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", timeEntryId);
        entry.addProperty("projectId", projectId);
        entry.addProperty("userId", userId);
        entry.addProperty("billable", false);
        JsonObject interval = new JsonObject();
        interval.addProperty("start", start.toString());
        entry.add("timeInterval", interval);
        return entry;
    }

    private static JsonObject project(String projectId, String projectName, long timeEstimateMs) {
        JsonObject project = new JsonObject();
        project.addProperty("id", projectId);
        project.addProperty("name", projectName);
        project.addProperty("public", true);
        project.add("memberships", new JsonArray());
        project.add("userGroups", new JsonArray());

        JsonObject timeEstimate = new JsonObject();
        timeEstimate.addProperty("active", true);
        timeEstimate.addProperty("estimate", timeEstimateMs);
        timeEstimate.addProperty("resetOption", "MONTHLY");
        project.add("timeEstimate", timeEstimate);
        return project;
    }

    private static JsonObject summaryReport(long totalSeconds) {
        JsonObject totals = new JsonObject();
        totals.addProperty("totalTime", totalSeconds);
        JsonArray totalsArray = new JsonArray();
        totalsArray.add(totals);
        JsonObject report = new JsonObject();
        report.add("totals", totalsArray);
        return report;
    }
}

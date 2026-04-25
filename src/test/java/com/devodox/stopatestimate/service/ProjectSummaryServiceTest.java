package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectGuardSummary;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.ProjectUsage;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.ResetWindow;
import com.devodox.stopatestimate.model.ResetWindowSchedule;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectSummaryServiceTest {

    private ClockifyLifecycleService lifecycleService;
    private ProjectUsageService projectUsageService;
    private ProjectLockService projectLockService;
    private ClockifyBackendApiClient backendApiClient;
    private CutoffJobStore cutoffJobStore;
    private ProjectSummaryService service;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lifecycleService = Mockito.mock(ClockifyLifecycleService.class);
        projectUsageService = Mockito.mock(ProjectUsageService.class);
        projectLockService = Mockito.mock(ProjectLockService.class);
        backendApiClient = Mockito.mock(ClockifyBackendApiClient.class);
        cutoffJobStore = Mockito.mock(CutoffJobStore.class);
        // Real CutoffPlanner: pure function of inputs.
        CutoffPlanner cutoffPlanner = new CutoffPlanner();
        // Real KnownProjectIdsResolver wrapping the mocks — preserves integration coverage of
        // the cache + DB-merge path that previously lived in EstimateGuardService.
        KnownProjectIdsResolver resolver = new KnownProjectIdsResolver(
                backendApiClient, projectLockService, cutoffJobStore);
        service = new ProjectSummaryService(
                lifecycleService, projectUsageService, projectLockService,
                cutoffPlanner, resolver, fixedClock);
    }

    // ----- listProjectSummaries: workspace-wide running-entries batching (N+1 fix) -----

    // Regression guard: previously summarizeProject called loadRunningEntries per project, each
    // of which fired GET /time-entries/status/in-progress against the whole workspace and filtered
    // in memory. For N guarded projects that was N identical HTTP requests per sidebar render.
    // The fix routes summarizeProject through a single loadRunningEntriesByProject call.
    @Test
    void listProjectSummaries_fetchesRunningEntriesOncePerInvocation() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));

        JsonObject projectA = new JsonObject();
        projectA.addProperty("id", "project-A");
        JsonObject projectB = new JsonObject();
        projectB.addProperty("id", "project-B");
        when(backendApiClient.listProjects(any())).thenReturn(List.of(projectA, projectB));
        when(projectLockService.findSnapshots("ws-1")).thenReturn(List.of());
        when(cutoffJobStore.findByWorkspaceId("ws-1")).thenReturn(List.of());

        when(projectUsageService.loadProjectState(any(), eq("project-A")))
                .thenReturn(projectStateFor("project-A"));
        when(projectUsageService.loadProjectState(any(), eq("project-B")))
                .thenReturn(projectStateFor("project-B"));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntriesByProject(any())).thenReturn(Map.of(
                "project-A", List.of(
                        new RunningTimeEntry("ws-1", "project-A", "user-1", "te-A1", fixedClock.instant(), true),
                        new RunningTimeEntry("ws-1", "project-A", "user-2", "te-A2", fixedClock.instant(), true)),
                "project-B", List.of(
                        new RunningTimeEntry("ws-1", "project-B", "user-3", "te-B1", fixedClock.instant(), true))));

        service.listProjectSummaries("ws-1");

        verify(projectUsageService, times(1)).loadRunningEntriesByProject(any());
        // Per-project fetch is obsolete in the summary path — the whole point of the batch is
        // to stop hitting listInProgressTimeEntries N times. If this fires, we regressed.
        verify(projectUsageService, never()).loadRunningEntries(any(), anyString());
    }

    // Correctness: running entries must be attributed to their own project. When project A and
    // project B both have concurrent timers, A's count must not include B's entries and vice
    // versa. Catches a class of bug where a map-routing refactor accidentally spreads one
    // project's list across all projects.
    @Test
    void listProjectSummaries_attributesRunningEntriesToCorrectProject() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));

        JsonObject projectA = new JsonObject();
        projectA.addProperty("id", "project-A");
        JsonObject projectB = new JsonObject();
        projectB.addProperty("id", "project-B");
        when(backendApiClient.listProjects(any())).thenReturn(List.of(projectA, projectB));
        when(projectLockService.findSnapshots("ws-1")).thenReturn(List.of());
        when(cutoffJobStore.findByWorkspaceId("ws-1")).thenReturn(List.of());

        when(projectUsageService.loadProjectState(any(), eq("project-A")))
                .thenReturn(projectStateFor("project-A"));
        when(projectUsageService.loadProjectState(any(), eq("project-B")))
                .thenReturn(projectStateFor("project-B"));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntriesByProject(any())).thenReturn(Map.of(
                "project-A", List.of(
                        new RunningTimeEntry("ws-1", "project-A", "user-1", "te-A1", fixedClock.instant(), true),
                        new RunningTimeEntry("ws-1", "project-A", "user-2", "te-A2", fixedClock.instant(), true)),
                "project-B", List.of(
                        new RunningTimeEntry("ws-1", "project-B", "user-3", "te-B1", fixedClock.instant(), true))));

        List<ProjectGuardSummary> summaries = service.listProjectSummaries("ws-1");

        ProjectGuardSummary a = summaries.stream().filter(s -> "project-A".equals(s.projectId())).findFirst().orElseThrow();
        ProjectGuardSummary b = summaries.stream().filter(s -> "project-B".equals(s.projectId())).findFirst().orElseThrow();
        assertThat(a.runningEntryCount()).isEqualTo(2);
        assertThat(b.runningEntryCount()).isEqualTo(1);
    }

    // A project that happens to have no in-progress timers must still get a summary — the
    // map-lookup has to fall back to an empty list, not throw NPE. Previously loadRunningEntries
    // always returned a (possibly empty) list for any projectId; the batch map omits keys with
    // no entries, so the fallback needs a getOrDefault.
    @Test
    void listProjectSummaries_projectWithoutRunningEntriesStillSummarized() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));

        JsonObject projectA = new JsonObject();
        projectA.addProperty("id", "project-A");
        when(backendApiClient.listProjects(any())).thenReturn(List.of(projectA));
        when(projectLockService.findSnapshots("ws-1")).thenReturn(List.of());
        when(cutoffJobStore.findByWorkspaceId("ws-1")).thenReturn(List.of());

        when(projectUsageService.loadProjectState(any(), eq("project-A")))
                .thenReturn(projectStateFor("project-A"));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        // Empty map — project-A has no running timers.
        when(projectUsageService.loadRunningEntriesByProject(any())).thenReturn(Map.of());

        List<ProjectGuardSummary> summaries = service.listProjectSummaries("ws-1");

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).projectId()).isEqualTo("project-A");
        assertThat(summaries.get(0).runningEntryCount()).isZero();
    }

    private InstallationRecord installation(boolean enabled, AddonStatus status, String mode) {
        return new InstallationRecord(
                "ws-1",
                "addon-123",
                "addon-user",
                "owner-user",
                "installation-token",
                "https://api.clockify.me/api",
                "https://reports.api.clockify.me",
                Map.of(),
                status,
                enabled,
                mode,
                "MONTHLY",
                fixedClock.instant(),
                fixedClock.instant());
    }

    private ProjectState projectStateFor(String projectId) {
        return new ProjectState(
                "ws-1",
                projectId,
                projectId,
                true,
                List.of(),
                List.of(),
                RateInfo.of(BigDecimal.valueOf(1000), "USD"),
                RateInfo.empty(),
                new ProjectCaps(true, 7_200_000L, "MONTHLY", false, true, BigDecimal.valueOf(50_000), "MONTHLY", false, ResetWindowSchedule.none()));
    }
}

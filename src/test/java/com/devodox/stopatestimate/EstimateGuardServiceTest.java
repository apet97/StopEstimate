package com.devodox.stopatestimate;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.GuardEventType;
import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.PendingCutoffJob;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.ProjectUsage;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.ResetWindow;
import com.devodox.stopatestimate.model.ResetWindowSchedule;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.devodox.stopatestimate.model.entity.GuardEventEntity;
import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.devodox.stopatestimate.service.ClockifyLifecycleService;
import com.devodox.stopatestimate.service.CutoffPlanner;
import com.devodox.stopatestimate.service.EstimateGuardService;
import com.devodox.stopatestimate.service.GuardEventRecorder;
import com.devodox.stopatestimate.service.KnownProjectIdsResolver;
import com.devodox.stopatestimate.service.ProjectLockService;
import com.devodox.stopatestimate.service.ProjectUsageService;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

class EstimateGuardServiceTest {

    private ClockifyLifecycleService lifecycleService;
    private ProjectUsageService projectUsageService;
    private ProjectLockService projectLockService;
    private ClockifyBackendApiClient backendApiClient;
    private CutoffJobStore cutoffJobStore;
    private GuardEventRepository guardEventRepository;
    private EstimateGuardService service;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lifecycleService = Mockito.mock(ClockifyLifecycleService.class);
        projectUsageService = Mockito.mock(ProjectUsageService.class);
        projectLockService = Mockito.mock(ProjectLockService.class);
        backendApiClient = Mockito.mock(ClockifyBackendApiClient.class);
        cutoffJobStore = Mockito.mock(CutoffJobStore.class);
        guardEventRepository = Mockito.mock(GuardEventRepository.class);
        // Real GuardEventRecorder wrapping the mock repo so existing
        // verify(guardEventRepository, ...).save(...) assertions still work — the recorder is a
        // pass-through that does the row construction inline.
        GuardEventRecorder guardEventRecorder = new GuardEventRecorder(guardEventRepository);
        // Real CutoffPlanner: pure function of inputs, no collaborators. Using the real one
        // keeps the assess→cutoffPlan→immediateCutoff math under test instead of stubbing it.
        CutoffPlanner cutoffPlanner = new CutoffPlanner();
        // Real KnownProjectIdsResolver wrapping the same mocks — preserves the cache + DB-merge
        // path under test. The 30s TTL is a non-issue inside a single test method.
        KnownProjectIdsResolver knownProjectIdsResolver = new KnownProjectIdsResolver(
                backendApiClient, projectLockService, cutoffJobStore);
        service = new EstimateGuardService(
                lifecycleService, projectUsageService, projectLockService,
                cutoffJobStore, guardEventRecorder, backendApiClient, cutoffPlanner,
                knownProjectIdsResolver, fixedClock);
    }

    @Test
    void belowCapDoesNotLockOrStopTimers() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                3_600_000L,
                BigDecimal.valueOf(1000),
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of());
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "test", null);

        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void timeCapReachedLocksAndStopsTimers() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                7_200_000L,
                BigDecimal.valueOf(1000),
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(600), true)));

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService).lockProject(any(), any(), any());
    }

    @Test
    void budgetCapReachedLocksAndStopsTimers() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                1_800_000L,
                BigDecimal.valueOf(60_000),
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(600), true)));

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService).lockProject(any(), any(), any());
    }

    @Test
    void inactiveOrDisabledWorkspaceDoesNotEnforce() {
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation(false, AddonStatus.INACTIVE, "ENFORCE")));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "test", null);

        verify(projectUsageService, never()).loadProjectState(any(), anyString());
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
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
                java.util.Map.of(),
                status,
                enabled,
                mode,
                "MONTHLY",
                fixedClock.instant(),
                fixedClock.instant());
    }

    private ProjectState projectState() {
        // Default hourly rate drives budget math (Clockify's budgetEstimate is billable, not cost).
        return new ProjectState(
                "ws-1",
                "project-1",
                "Project 1",
                true,
                List.of(),
                List.of(),
                RateInfo.of(BigDecimal.valueOf(1000), "USD"),
                RateInfo.empty(),
                new ProjectCaps(true, 7_200_000L, "MONTHLY", false, true, BigDecimal.valueOf(50_000), "MONTHLY", false, ResetWindowSchedule.none()));
    }

    private ProjectState projectStateWithTimeCap(long timeLimitMs) {
        // Budget cap disabled so only the time branch drives the cutoff plan. Cost rate is left
        // unset — irrelevant for the time-only scenario.
        return new ProjectState(
                "ws-1",
                "project-1",
                "Project 1",
                true,
                List.of(),
                List.of(),
                RateInfo.empty(),
                RateInfo.empty(),
                new ProjectCaps(true, timeLimitMs, "MONTHLY", false, false, BigDecimal.ZERO, "MONTHLY", false, ResetWindowSchedule.none()));
    }

    // Regression: cutoffPlan must subtract elapsed time on running timers so repeated reconciles
    // converge. Before the fix, cutoffAt slid forward by (cap - tracked) on every tick and the
    // scheduler never reached the locking path.
    @Test
    void cutoffAtAccountsForRunningTimerElapsedTime() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(60_000L));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        Instant entryStart = fixedClock.instant().minusSeconds(10);
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", entryStart, true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cutoffJobStore).upsert(eq("ws-1"), eq("project-1"), eq("user-1"), eq("te-1"), cutoffCaptor.capture());
        // 10s already elapsed against a 60s cap → only 50s remain, so cutoffAt is entry.start+60s.
        assertThat(cutoffCaptor.getValue()).isEqualTo(entryStart.plusSeconds(60));
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void elapsedExceedingCapReturnsImmediateLockNow() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(60_000L));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        // 90s elapsed against a 60s cap — completed tracked time is still 0 (summary report only
        // reflects completed entries), but the elapsed subtraction forces lockNow on this tick.
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(90), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        ArgumentCaptor<GuardReason> reasonCaptor = ArgumentCaptor.forClass(GuardReason.class);
        verify(projectLockService).lockProject(any(), any(), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(GuardReason.TIME_CAP_REACHED);
    }

    @Test
    void elapsedBillableExceedingCapReturnsImmediateLockNow() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        // Budget cap $60, no completed usage, hourly rate $1000/h on a billable running timer
        // that's been alive long enough that elapsed billable amount exceeds the cap.
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.valueOf(1000), "USD"),
                RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(60), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        // 600s @ $1000/h = $166 — elapsed billable alone blows past the $60 budget.
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(600), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        ArgumentCaptor<GuardReason> reasonCaptor = ArgumentCaptor.forClass(GuardReason.class);
        verify(projectLockService).lockProject(any(), any(), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(GuardReason.BUDGET_CAP_REACHED);
    }

    // ----- Budget-cap billable-amount path (replaces the cost-rate path) -----

    @Test
    void budgetCapReachedLocksAndStopsTimers_usesHourlyRate() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        // Project state: hourlyRate set, costRate empty. Budget cap $100, no time cap.
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.valueOf(60), "USD"),
                RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(100), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        // Summary already returned billable = $100 (the EARNED amount type extraction is verified
        // separately in ProjectUsageServiceTest); guard must trip on the equality.
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.valueOf(100),
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(60), true)));

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        ArgumentCaptor<GuardReason> reasonCaptor = ArgumentCaptor.forClass(GuardReason.class);
        verify(projectLockService).lockProject(any(), any(), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(GuardReason.BUDGET_CAP_REACHED);
    }

    @Test
    void budgetCapIgnoresCostAmountType() {
        // Simulates the upstream behavior: extractSummaryBillable returns ZERO when the summary
        // response contains only COST entries (verified directly in ProjectUsageServiceTest).
        // Guard must NOT breach because budgetUsage=0 < cap=100.
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.valueOf(60), "USD"),
                RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(100), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of());
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void cutoffAtAccountsForBillableElapsed() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        // Budget cap $60, no completed usage. One billable running timer at $3600/h ($1/s — picked
        // because it divides 3,600,000 ms exactly so BigDecimal rounding doesn't drift the cutoff).
        // Started 30 s ago → elapsed billable = $30, remaining = $30 → 30 s until cap →
        // cutoffAt = entry.start + 60 s. Validates that elapsed billable is subtracted (without
        // it, remaining would stay $60 → cutoffAt = entry.start + 90 s).
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.valueOf(3600), "USD"),
                RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(60), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        Instant entryStart = fixedClock.instant().minusSeconds(30);
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", entryStart, true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cutoffJobStore).upsert(eq("ws-1"), eq("project-1"), eq("user-1"), eq("te-1"), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isEqualTo(entryStart.plusSeconds(60));
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void nonBillableRunningTimerIgnoredForBudgetOnly() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        // Cap $10, hourlyRate $120/h, 30 min elapsed. If billable, elapsed = $60 → instant lock.
        // Because billable=false, the budget branch must add no candidate and no lock occurs.
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.valueOf(120), "USD"),
                RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(10), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(30 * 60), false)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    // ----- Gap 1: guard_events audit trail assertions -----

    @Test
    void auditTrailEmitsLockedAndTimerStoppedOnExceeded() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                7_200_000L,
                BigDecimal.valueOf(1000),
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(600), true)));

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        verify(guardEventRepository, times(2)).save(captor.capture());
        List<GuardEventEntity> saved = captor.getAllValues();
        assertThat(saved).extracting(GuardEventEntity::getEventType)
                .containsExactly(GuardEventType.TIMER_STOPPED.name(), GuardEventType.LOCKED.name());
        assertThat(saved).allSatisfy(e -> {
            assertThat(e.getGuardReason()).isEqualTo(GuardReason.TIME_CAP_REACHED.name());
            assertThat(e.getSource()).isEqualTo("webhook:NEW_TIME_ENTRY");
            assertThat(e.getProjectId()).isEqualTo("project-1");
        });
    }

    @Test
    void auditTrailEmitsUnlockedWhenBelowCaps() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of());
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(true);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        verify(guardEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(GuardEventType.UNLOCKED.name());
        assertThat(captor.getValue().getGuardReason()).isEqualTo(GuardReason.BELOW_CAPS.name());
        assertThat(captor.getValue().getPayloadFingerprint()).isEqualTo("scheduler");
    }

    @Test
    void upsertJobDelegatesToAtomicStoreUpsert() {
        // Post-BUG-05 the race-handling check-delete-insert-fallback is gone — the service simply
        // delegates to cutoffJobStore.upsert, which relies on the uk_cutoff_jobs_workspace_time_entry
        // constraint + ON CONFLICT DO UPDATE to converge concurrent inserts atomically.
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(3_600_000L));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        Instant entryStart = fixedClock.instant().minusSeconds(60);
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", entryStart, true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        verify(cutoffJobStore, times(1)).upsert(eq("ws-1"), eq("project-1"), eq("user-1"), eq("te-1"), any(Instant.class));
        verify(cutoffJobStore, never()).save(any(PendingCutoffJob.class));
        verify(cutoffJobStore, never()).deleteByJobId(anyString());
    }

    @Test
    void auditTrailEmitsCutoffScheduled() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(3_600_000L));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(60), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        verify(guardEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(GuardEventType.CUTOFF_SCHEDULED.name());
        assertThat(captor.getValue().getGuardReason()).isNotNull();
    }

    // ----- TEST-03: budget-cap fail-closed when hourly rate is absent or configured zero -----

    // Absent hourly rate + billable running timer + budget cap active → aggregateRate is zero →
    // SPEC §5 fail-closed: lock immediately with BUDGET_CAP_REACHED.
    @Test
    void missingHourlyRateOnBillableTimerFailsClosed() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        // Budget cap $100, no time cap, rate empty, one billable running timer.
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.empty(), RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(100), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(10), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        ArgumentCaptor<GuardReason> reasonCaptor = ArgumentCaptor.forClass(GuardReason.class);
        verify(projectLockService).lockProject(any(), any(), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(GuardReason.BUDGET_CAP_REACHED);
    }

    // Configured-but-zero hourly rate: present() is true (amount >= 0) but the per-ms rate is 0,
    // so aggregateRate still evaluates to zero → same fail-closed path as "absent" above.
    @Test
    void configuredZeroHourlyRateOnBillableTimerAlsoFailsClosed() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.ZERO, "USD"), RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, true, BigDecimal.valueOf(100), "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(10), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        ArgumentCaptor<GuardReason> reasonCaptor = ArgumentCaptor.forClass(GuardReason.class);
        verify(projectLockService).lockProject(any(), any(), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(GuardReason.BUDGET_CAP_REACHED);
    }

    // ----- TEST-04: multi-timer concurrency + division + stale pruning -----

    // stopRunningEntries dedupes by userId: two running timers owned by the same user produce a
    // single stopRunningTimer call (the endpoint stops the user's *current* timer, not one by id).
    @Test
    void stopRunningEntriesDedupesByUser() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        // Usage already over the time cap → exceeded branch fires stopRunningEntries.
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                8_000_000L, BigDecimal.valueOf(1000), BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(60), true),
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-2", fixedClock.instant().minusSeconds(30), true)));

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        verify(backendApiClient, times(1)).stopRunningTimer(any(), eq("user-1"), anyString());
    }

    // cutoffPlan divides remaining time across concurrent timers. Two timers starting at `now`
    // (zero elapsed) with a 60s cap and 0 tracked → remaining=60s, concurrent=2 → cutoffAt=now+30s.
    @Test
    void cutoffPlanDividesRemainingAcrossConcurrentTimers() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(60_000L));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant(), true),
                new RunningTimeEntry("ws-1", "project-1", "user-2", "te-2", fixedClock.instant(), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cutoffJobStore, times(2))
                .upsert(anyString(), anyString(), anyString(), anyString(), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getAllValues())
                .allSatisfy(instant -> assertThat(instant).isEqualTo(fixedClock.instant().plusMillis(30_000L)));
    }

    // syncCutoffJobs calls deleteStale with the set of still-running time entry ids, so previously
    // scheduled jobs for entries that have since stopped get pruned on this tick.
    @Test
    void syncCutoffJobsDeletesStaleWithKeepSet() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(3_600_000L));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(60), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(false);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        ArgumentCaptor<java.util.Collection<String>> keepCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(cutoffJobStore).deleteStale(eq("ws-1"), eq("project-1"), keepCaptor.capture());
        assertThat(keepCaptor.getValue()).containsExactly("te-1");
    }

    // ----- TEST-05: caps removed → snapshot unlock with NO_ACTIVE_CAPS reason -----

    // ----- TEST-09: immediateCutoff uses entry.start when cap already breached excluding this timer -----

    // When the source is a NEW_TIMER_STARTED webhook and tracked usage (minus the elapsed portion
    // of the freshly-started timer) already exceeds the cap, the cutoff timestamp handed to
    // stopRunningTimer must be the entry's start time so Clockify records zero additional time
    // for this entry — not `now`, which would keep the elapsed portion logged.
    @Test
    void immediateCutoffUsesEntryStartWhenCapAlreadyBreachedExcludingCurrentTimer() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(60_000L));
        // Tracked = 70s (> cap 60s), running entry started 10s ago → trackedBeforeCurrent = 60s
        // >= cap 60s, so immediateCutoff returns entry.start rather than now.
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                70_000L, BigDecimal.ZERO, BigDecimal.ZERO));
        Instant entryStart = fixedClock.instant().minusSeconds(10);
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", entryStart, true)));

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIMER_STARTED", new JsonObject());

        ArgumentCaptor<String> timestampCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendApiClient).stopRunningTimer(any(), anyString(), timestampCaptor.capture());
        // CLOCKIFY_TIMESTAMP pattern: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'. entryStart = now - 10s.
        // fixed clock is 2026-04-16T10:00:00Z → entryStart = 2026-04-16T09:59:50.000Z.
        assertThat(timestampCaptor.getValue()).isEqualTo("2026-04-16T09:59:50.000Z");
    }

    // ----- TEST-07: lockNow path when already locked skips lock-attempt churn -----

    // cutoffPlan returns lockNow=true (tracked + elapsed exceeds cap) with reason TIME_CAP_REACHED,
    // but reason stays BELOW_CAPS at the Assessment level because tracked alone hasn't breached.
    // When isLocked is already true, lockProject and stopRunningTimer must not be called again —
    // cutoffJobStore.deleteByProject is still called to clear any pending job.
    @Test
    void lockNowSkipsSideEffectsWhenAlreadyLocked() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectStateWithTimeCap(60_000L));
        // tracked=30s (< cap=60s, not yet exceeded), running timer elapsed 40s → remaining=-10s
        // → cutoffPlan returns lockNow=true with reason TIME_CAP_REACHED.
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                30_000L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(40), true)));
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(true);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        verify(projectLockService, never()).lockProject(any(), any(), any());
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(cutoffJobStore).deleteByProject("ws-1", "project-1");
    }

    // Reconcile path regression: single-project reconcileProject must still call the per-project
    // loadRunningEntries API, not the batch. The batch exists only for the sidebar; using it from
    // the reconcile hot path would fetch every workspace running entry on every webhook tick.
    @Test
    void reconcileProject_usesPerProjectLoadRunningEntries_notBatch() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(projectState());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                0L, BigDecimal.ZERO, BigDecimal.ZERO));
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of());

        service.reconcileProject("ws-1", "project-1", "webhook:NEW_TIME_ENTRY", new JsonObject());

        verify(projectUsageService, times(1)).loadRunningEntries(any(), eq("project-1"));
        verify(projectUsageService, never()).loadRunningEntriesByProject(any());
    }

    @Test
    void capsRemovedUnlocksWithNoActiveCapsReason() {
        InstallationRecord installation = installation(true, AddonStatus.ACTIVE, "ENFORCE");
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation));
        // No active caps: both timeCapActive and budgetCapActive are false.
        ProjectState state = new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.empty(), RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, false, BigDecimal.ZERO, "MONTHLY", false, ResetWindowSchedule.none()));
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(state);
        when(projectLockService.isLocked("ws-1", "project-1")).thenReturn(true);

        service.reconcileProject("ws-1", "project-1", "scheduler:tick", null);

        verify(projectLockService).unlockProject(any(), eq("project-1"));
        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        verify(guardEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(GuardEventType.UNLOCKED.name());
        assertThat(captor.getValue().getGuardReason()).isEqualTo(GuardReason.NO_ACTIVE_CAPS.name());
    }
}

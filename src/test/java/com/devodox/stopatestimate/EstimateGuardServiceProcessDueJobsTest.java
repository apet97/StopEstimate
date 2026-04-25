package com.devodox.stopatestimate;

import com.devodox.stopatestimate.api.ClockifyApiException;
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
import com.devodox.stopatestimate.service.ProjectLockService;
import com.devodox.stopatestimate.service.ProjectUsageService;
import com.devodox.stopatestimate.store.CutoffJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-01: covers {@link EstimateGuardService#processDueJobs(String)} — the hard-stop path that
 * had zero direct coverage before this change. Every due-job scenario that can split-brain the
 * DB vs. Clockify (ownership races, partial side-effect failure per BUG-11, transient load
 * errors) is exercised here so regressions in the enforcement path are caught fast.
 */
class EstimateGuardServiceProcessDueJobsTest {

    private ClockifyLifecycleService lifecycleService;
    private ProjectUsageService projectUsageService;
    private ProjectLockService projectLockService;
    private ClockifyBackendApiClient backendApiClient;
    private CutoffJobStore cutoffJobStore;
    private GuardEventRepository guardEventRepository;
    private EstimateGuardService service;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lifecycleService = Mockito.mock(ClockifyLifecycleService.class);
        projectUsageService = Mockito.mock(ProjectUsageService.class);
        projectLockService = Mockito.mock(ProjectLockService.class);
        backendApiClient = Mockito.mock(ClockifyBackendApiClient.class);
        cutoffJobStore = Mockito.mock(CutoffJobStore.class);
        guardEventRepository = Mockito.mock(GuardEventRepository.class);
        // Real GuardEventRecorder wrapping the mock repo so existing
        // verify(guardEventRepository, ...).save(...) assertions still work.
        GuardEventRecorder guardEventRecorder = new GuardEventRecorder(guardEventRepository);
        // Real CutoffPlanner: pure function of inputs, no collaborators. The BUG-11
        // asymmetric-failure tests below depend on the real assess() path producing the same
        // GuardReason as production.
        CutoffPlanner cutoffPlanner = new CutoffPlanner();
        service = new EstimateGuardService(
                lifecycleService, projectUsageService, projectLockService,
                cutoffJobStore, guardEventRecorder, backendApiClient, cutoffPlanner, fixedClock);
    }

    @Test
    void normalFireStopsTimerAndLocksAndEmitsEvents() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        primeStateExceeded();
        primeRunningEntryForJob();
        when(cutoffJobStore.deleteByJobId(job.jobId())).thenReturn(1);

        service.processDueJobs("scheduler");

        verify(backendApiClient).stopRunningTimer(any(), eq("user-1"), anyString());
        verify(projectLockService).lockProject(any(), any(), eq(GuardReason.TIME_CAP_REACHED));

        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        verify(guardEventRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(GuardEventEntity::getEventType)
                .containsExactly(GuardEventType.TIMER_STOPPED.name(), GuardEventType.LOCKED.name());
        verify(cutoffJobStore, never()).save(any(PendingCutoffJob.class));
    }

    @Test
    void targetTimerAlreadyStoppedDeletesJobAndSkipsSideEffects() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(stateExceeded());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(usageAtCap());
        // No running entry whose timeEntryId matches the job — the user stopped the timer manually
        // between the schedule and the fire.
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-other", "te-other", fixedClock.instant().minusSeconds(60), true)));

        service.processDueJobs("scheduler");

        verify(cutoffJobStore).deleteByJobId(job.jobId());
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void ownershipRaceDeleteReturnsZeroSkipsSideEffects() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        primeStateExceeded();
        primeRunningEntryForJob();
        // Another instance already claimed the job.
        when(cutoffJobStore.deleteByJobId(job.jobId())).thenReturn(0);

        service.processDueJobs("scheduler");

        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
        verify(guardEventRepository, never()).save(any(GuardEventEntity.class));
    }

    @Test
    void capsRemovedBetweenScheduleAndFireDeletesJob() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        // Caps removed: loadProjectState returns a state with no active caps.
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.empty(), RateInfo.empty(),
                new ProjectCaps(false, 0L, "MONTHLY", false, false, BigDecimal.ZERO, "MONTHLY", false, ResetWindowSchedule.none())));
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(usageAtCap());
        primeRunningEntryForJob();

        service.processDueJobs("scheduler");

        verify(cutoffJobStore).deleteByJobId(job.jobId());
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void inactiveInstallationDeletesJobWithoutTouchingClockify() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation(false, AddonStatus.INACTIVE)));

        service.processDueJobs("scheduler");

        verify(cutoffJobStore).deleteByJobId(job.jobId());
        verify(projectUsageService, never()).loadProjectState(any(), anyString());
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void stopRunningTimerFailureReinsertsJobAndSkipsLock() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        primeStateExceeded();
        primeRunningEntryForJob();
        when(cutoffJobStore.deleteByJobId(job.jobId())).thenReturn(1);
        doThrow(new ClockifyApiException("boom"))
                .when(backendApiClient).stopRunningTimer(any(), anyString(), anyString());

        service.processDueJobs("scheduler");

        // BUG-11 asymmetry A: stop failed → reinsert for retry, never lock, never record TIMER_STOPPED.
        ArgumentCaptor<PendingCutoffJob> captor = ArgumentCaptor.forClass(PendingCutoffJob.class);
        verify(cutoffJobStore).save(captor.capture());
        assertThat(captor.getValue().jobId()).isEqualTo(job.jobId());
        verify(projectLockService, never()).lockProject(any(), any(), any());
        verify(guardEventRepository, never()).save(any(GuardEventEntity.class));
    }

    @Test
    void lockProjectFailureLeavesTimerStoppedEventButNoLockEventAndNoReinsert() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        primeStateExceeded();
        primeRunningEntryForJob();
        when(cutoffJobStore.deleteByJobId(job.jobId())).thenReturn(1);
        doThrow(new ClockifyApiException("lock failed"))
                .when(projectLockService).lockProject(any(), any(), any());

        service.processDueJobs("scheduler");

        // BUG-11 asymmetry B: timer stopped OK, lock threw → TIMER_STOPPED recorded, LOCKED not,
        // no job re-insert (next reconcile tick retries the lock).
        verify(backendApiClient).stopRunningTimer(any(), anyString(), anyString());
        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        verify(guardEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(GuardEventType.TIMER_STOPPED.name());
        verify(cutoffJobStore, never()).save(any(PendingCutoffJob.class));
    }

    @Test
    void transientLoadFailureLeavesJobInPlaceForNextTick() {
        PendingCutoffJob job = job();
        primeDueJobs(job);
        primeActiveInstallation();
        when(projectUsageService.loadProjectState(any(), anyString()))
                .thenThrow(new ClockifyApiException("timeout"));

        service.processDueJobs("scheduler");

        verify(cutoffJobStore, never()).deleteByJobId(anyString());
        verify(backendApiClient, never()).stopRunningTimer(any(), anyString(), anyString());
        verify(projectLockService, never()).lockProject(any(), any(), any());
    }

    @Test
    void unexpectedExceptionOnOneJobDoesNotBlockRemainingJobs() {
        PendingCutoffJob bad = new PendingCutoffJob(
                "bad-job", "ws-1", "project-1", "user-1", "te-1",
                fixedClock.instant().minusSeconds(1), fixedClock.instant().minusSeconds(10));
        PendingCutoffJob good = new PendingCutoffJob(
                "good-job", "ws-1", "project-1", "user-2", "te-2",
                fixedClock.instant().minusSeconds(1), fixedClock.instant().minusSeconds(10));
        when(cutoffJobStore.findDueJobs(any(Instant.class))).thenReturn(List.of(bad, good));
        // First call throws an unexpected runtime — findInstallation is the first thing the job
        // handler touches, so this exercises the outer try/catch in processDueJobs.
        when(lifecycleService.findInstallation("ws-1"))
                .thenThrow(new RuntimeException("unexpected"))
                .thenReturn(Optional.of(installation(true, AddonStatus.ACTIVE)));
        primeStateExceeded();
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-2", "te-2", fixedClock.instant().minusSeconds(60), true)));
        when(cutoffJobStore.deleteByJobId(good.jobId())).thenReturn(1);

        service.processDueJobs("scheduler");

        // Second job still processed despite the first one throwing.
        verify(backendApiClient).stopRunningTimer(any(), eq("user-2"), anyString());
        verify(projectLockService).lockProject(any(), any(), eq(GuardReason.TIME_CAP_REACHED));
    }

    // ----- fixtures -----

    private PendingCutoffJob job() {
        return new PendingCutoffJob(
                "job-1", "ws-1", "project-1", "user-1", "te-1",
                fixedClock.instant().minusSeconds(1), fixedClock.instant().minusSeconds(10));
    }

    private void primeDueJobs(PendingCutoffJob job) {
        when(cutoffJobStore.findDueJobs(any(Instant.class))).thenReturn(List.of(job));
    }

    private void primeActiveInstallation() {
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installation(true, AddonStatus.ACTIVE)));
    }

    private void primeStateExceeded() {
        when(projectUsageService.loadProjectState(any(), anyString())).thenReturn(stateExceeded());
        when(projectUsageService.loadProjectUsage(any(), any(), any())).thenReturn(usageAtCap());
    }

    private void primeRunningEntryForJob() {
        when(projectUsageService.loadRunningEntries(any(), anyString())).thenReturn(List.of(
                new RunningTimeEntry("ws-1", "project-1", "user-1", "te-1", fixedClock.instant().minusSeconds(60), true)));
    }

    private InstallationRecord installation(boolean enabled, AddonStatus status) {
        return new InstallationRecord(
                "ws-1", "addon-123", "addon-user", "owner-user", "installation-token",
                "https://api.clockify.me/api", "https://reports.api.clockify.me",
                java.util.Map.of(),
                status, enabled, "ENFORCE", "MONTHLY",
                fixedClock.instant(), fixedClock.instant());
    }

    private ProjectState stateExceeded() {
        // Time cap active at 1h; usageAtCap() returns 2h → exceeded → TIME_CAP_REACHED.
        return new ProjectState(
                "ws-1", "project-1", "Project 1", true, List.of(), List.of(),
                RateInfo.of(BigDecimal.valueOf(100), "USD"), RateInfo.empty(),
                new ProjectCaps(true, 3_600_000L, "MONTHLY", false, false, BigDecimal.ZERO, "MONTHLY", false, ResetWindowSchedule.none()));
    }

    private ProjectUsage usageAtCap() {
        return new ProjectUsage(
                new ResetWindow(Instant.EPOCH, fixedClock.instant(), null),
                7_200_000L, BigDecimal.valueOf(500), BigDecimal.ZERO);
    }
}

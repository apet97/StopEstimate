package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.GuardEventType;
import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.PendingCutoffJob;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectGuardSummary;
import com.devodox.stopatestimate.model.ProjectLockSnapshot;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.ProjectUsage;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.devodox.stopatestimate.service.CutoffPlanner.Assessment;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EstimateGuardService {
    private static final Logger log = LoggerFactory.getLogger(EstimateGuardService.class);

    // Clockify's stop-timer endpoint documents yyyy-MM-dd'T'HH:mm:ssZ in its OpenAPI examples.
    // Instant.toString() emits variable precision (seconds to nanos depending on the instant).
    // Pin to millisecond precision so request bodies are stable and log output is readable.
    private static final DateTimeFormatter CLOCKIFY_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final ClockifyLifecycleService lifecycleService;
    private final ProjectUsageService projectUsageService;
    private final ProjectLockService projectLockService;
    private final CutoffJobStore cutoffJobStore;
    private final GuardEventRecorder guardEventRecorder;
    private final ClockifyBackendApiClient backendApiClient;
    private final CutoffPlanner cutoffPlanner;
    private final Clock clock;

    /**
     * Per-workspace cache of the Clockify-side project ID list. Scheduler tick + sidebar calls
     * hit this more than once per minute; the 30s TTL means we hit Clockify at most once per
     * tick while still reflecting project changes within ~30s even if no webhook arrives.
     * DB-derived IDs (lock snapshots, cutoff jobs) are NOT cached — they're merged fresh on
     * each lookup so a just-fired webhook's new cutoff job is picked up immediately.
     */
    private final Cache<String, Set<String>> clockifyProjectIdsCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(1_000)
            .build();

    public EstimateGuardService(
            ClockifyLifecycleService lifecycleService,
            ProjectUsageService projectUsageService,
            ProjectLockService projectLockService,
            CutoffJobStore cutoffJobStore,
            GuardEventRecorder guardEventRecorder,
            ClockifyBackendApiClient backendApiClient,
            CutoffPlanner cutoffPlanner,
            Clock clock) {
        this.lifecycleService = lifecycleService;
        this.projectUsageService = projectUsageService;
        this.projectLockService = projectLockService;
        this.cutoffJobStore = cutoffJobStore;
        this.guardEventRecorder = guardEventRecorder;
        this.backendApiClient = backendApiClient;
        this.cutoffPlanner = cutoffPlanner;
        this.clock = clock;
    }

    public void reconcileAll(String source) {
        // DB-08: DB-side filter via idx_installations_active; no need to re-check .active() here.
        // Per-workspace try/catch: one bad tenant (expired token, revoked scopes) must not stop
        // reconcile for the remaining workspaces in the same tick.
        for (InstallationRecord installation : lifecycleService.findActiveInstallations()) {
            try {
                reconcileKnownProjects(installation.workspaceId(), source);
            } catch (RuntimeException e) {
                log.warn("reconcileKnownProjects failed for workspace {}", installation.workspaceId(), e);
            }
        }
    }

    public void reconcileKnownProjects(String workspaceId, String source) {
        InstallationRecord installation = lifecycleService.findInstallation(workspaceId).orElse(null);
        if (installation == null) {
            return;
        }
        // PERF: fetch the workspace-scoped in-progress-timers endpoint ONCE per tick and
        // group by projectId. Previously reconcileProject called loadRunningEntries per
        // project, which hit the same workspace-wide endpoint N times and filtered in-memory.
        // Pre-fetched slice is passed into the reconcileProject overload; the `null` slice
        // path is reserved for webhook-driven single-project reconciles.
        Map<String, List<RunningTimeEntry>> runningEntriesByProject;
        try {
            runningEntriesByProject = projectUsageService.loadRunningEntriesByProject(installation);
        } catch (RuntimeException e) {
            log.warn("loadRunningEntriesByProject failed for workspace {} — falling back to per-project fetch", workspaceId, e);
            runningEntriesByProject = Map.of();
        }
        // Per-project try/catch: one broken project (deleted mid-tick, Clockify 4xx on a
        // specific id) must not starve the remaining projects.
        for (String projectId : knownProjectIds(installation)) {
            try {
                reconcileProject(workspaceId, projectId, source, null,
                        runningEntriesByProject.getOrDefault(projectId, List.of()));
            } catch (RuntimeException e) {
                log.warn("reconcileProject failed for workspace {} project {}", workspaceId, projectId, e);
            }
        }
    }

    public void reconcileProject(String workspaceId, String projectId, String source, JsonObject payload) {
        // Webhook / direct-call path: no batched prefetch available; fetch per-project.
        reconcileProject(workspaceId, projectId, source, payload, null);
    }

    public void reconcileProject(
            String workspaceId,
            String projectId,
            String source,
            JsonObject payload,
            List<RunningTimeEntry> preFetchedRunningEntries) {
        // BUG-06: no method-wide @Transactional. This method makes 3–5 Clockify HTTP calls
        // and previously held a DB connection across all of them, exhausting the pool under
        // load. Each collaborator (cutoffJobStore, projectLockService, recordEvent) commits
        // its own small transaction, and partial failure is acceptable: the next reconcile
        // tick (or due-job firing) reconverges state.
        InstallationRecord installation = lifecycleService.findInstallation(workspaceId).orElse(null);
        if (installation == null || projectId == null || projectId.isBlank()) {
            return;
        }
        if (!installation.active()) {
            cutoffJobStore.deleteByProject(workspaceId, projectId);
            if (projectLockService.isLocked(workspaceId, projectId)) {
                projectLockService.unlockProject(installation, projectId);
                recordEvent(workspaceId, projectId, GuardEventType.UNLOCKED, null, source, payload);
            }
            return;
        }

        Instant now = clock.instant();
        ProjectState projectState = projectUsageService.loadProjectState(installation, projectId);
        ProjectCaps caps = projectState.caps();
        if (caps == null || !caps.hasActiveCaps()) {
            cutoffJobStore.deleteByProject(workspaceId, projectId);
            if (projectLockService.isLocked(workspaceId, projectId)) {
                projectLockService.unlockProject(installation, projectId);
                recordEvent(workspaceId, projectId, GuardEventType.UNLOCKED, GuardReason.NO_ACTIVE_CAPS, source, payload);
            }
            return;
        }

        ProjectUsage usage = projectUsageService.loadProjectUsage(installation, projectState, now);
        List<RunningTimeEntry> runningEntries = preFetchedRunningEntries != null
                ? preFetchedRunningEntries
                : projectUsageService.loadRunningEntries(installation, projectId);
        Assessment assessment = cutoffPlanner.assess(projectState, usage, runningEntries, now, source, payload);

        if (!installation.enforcing()) {
            cutoffJobStore.deleteByProject(workspaceId, projectId);
            if (projectLockService.isLocked(workspaceId, projectId)) {
                projectLockService.unlockProject(installation, projectId);
                recordEvent(workspaceId, projectId, GuardEventType.UNLOCKED, null, source, payload);
            }
            return;
        }

        if (assessment.exceededReason() != null) {
            stopRunningEntries(installation, runningEntries, assessment.cutoffAt());
            if (!runningEntries.isEmpty()) {
                recordEvent(workspaceId, projectId, GuardEventType.TIMER_STOPPED, assessment.exceededReason(), source, payload);
            }
            projectLockService.lockProject(installation, projectState, assessment.exceededReason());
            recordEvent(workspaceId, projectId, GuardEventType.LOCKED, assessment.exceededReason(), source, payload);
            cutoffJobStore.deleteByProject(workspaceId, projectId);
            return;
        }

        if (assessment.lockNow()) {
            // If already locked, avoid the unlock→re-lock churn (open window + double API calls).
            if (!projectLockService.isLocked(workspaceId, projectId)) {
                stopRunningEntries(installation, runningEntries, now);
                if (!runningEntries.isEmpty()) {
                    recordEvent(workspaceId, projectId, GuardEventType.TIMER_STOPPED, assessment.plannedReason(), source, payload);
                }
                projectLockService.lockProject(installation, projectState, assessment.plannedReason());
                recordEvent(workspaceId, projectId, GuardEventType.LOCKED, assessment.plannedReason(), source, payload);
            }
            cutoffJobStore.deleteByProject(workspaceId, projectId);
            return;
        }

        if (projectLockService.isLocked(workspaceId, projectId)) {
            projectLockService.unlockProject(installation, projectId);
            recordEvent(workspaceId, projectId, GuardEventType.UNLOCKED, GuardReason.BELOW_CAPS, source, payload);
        }

        if (assessment.cutoffAt() == null) {
            cutoffJobStore.deleteByProject(workspaceId, projectId);
            return;
        }

        syncCutoffJobs(installation, projectId, runningEntries, assessment.cutoffAt());
        recordEvent(workspaceId, projectId, GuardEventType.CUTOFF_SCHEDULED, assessment.plannedReason(), source, payload);
    }

    public List<ProjectGuardSummary> listProjectSummaries(String workspaceId) {
        InstallationRecord installation = lifecycleService.findInstallation(workspaceId).orElse(null);
        if (installation == null) {
            return List.of();
        }

        // Bulk-load lock snapshots once per workspace; otherwise summarizeProject would
        // hit the DB once per project on top of the existing per-project Clockify calls.
        Map<String, ProjectLockSnapshot> snapshotsByProject = projectLockService.findSnapshots(installation.workspaceId())
                .stream()
                .collect(Collectors.toMap(ProjectLockSnapshot::projectId, s -> s, (a, b) -> a));

        // PERF: the in-progress-timers endpoint is workspace-scoped. Previously summarizeProject
        // fetched it once per project and filtered in-memory, so a workspace with N guarded
        // projects made N identical HTTP requests. Fetch once, group by projectId, pass the
        // per-project slice into summarizeProject. Freshness invariant from BUG-08 is preserved —
        // `now` is still captured inside summarizeProject before the assessment math runs.
        Map<String, List<RunningTimeEntry>> runningEntriesByProject =
                projectUsageService.loadRunningEntriesByProject(installation);

        List<ProjectGuardSummary> summaries = new ArrayList<>();
        for (String projectId : knownProjectIds(installation)) {
            ProjectGuardSummary summary = summarizeProject(
                    installation,
                    projectId,
                    Optional.ofNullable(snapshotsByProject.get(projectId)),
                    runningEntriesByProject.getOrDefault(projectId, List.of()));
            if (summary == null) {
                continue;
            }
            boolean keep = summary.activeCaps() || summary.locked() || summary.cutoffAt() != null;
            if (keep) {
                summaries.add(summary);
            }
        }
        summaries.sort(Comparator
                .comparing(ProjectGuardSummary::locked).reversed()
                .thenComparing(ProjectGuardSummary::status)
                .thenComparing(ProjectGuardSummary::projectName));
        return List.copyOf(summaries);
    }

    public void processDueJobs(String source) {
        // BUG-01: no method-wide @Transactional. A previous design rolled back every prior
        // delete in the batch if any job threw. Each job now commits its own state changes
        // via the individual store/repository methods, so a per-job failure does not affect
        // any other job.
        Instant now = clock.instant();
        for (PendingCutoffJob job : cutoffJobStore.findDueJobs(now)) {
            try {
                processDueJob(job, now, source);
            } catch (RuntimeException e) {
                log.warn("processDueJobs: unexpected error for job {} — continuing with remaining jobs", job.jobId(), e);
            }
        }
    }

    private void processDueJob(PendingCutoffJob job, Instant now, String source) {
        InstallationRecord installation = lifecycleService.findInstallation(job.workspaceId()).orElse(null);
        if (installation == null || !installation.enforcing()) {
            cutoffJobStore.deleteByJobId(job.jobId());
            return;
        }

        ProjectState projectState;
        ProjectUsage usage;
        List<RunningTimeEntry> runningEntries;
        try {
            projectState = projectUsageService.loadProjectState(installation, job.projectId());
            usage = projectUsageService.loadProjectUsage(installation, projectState, now);
            runningEntries = projectUsageService.loadRunningEntries(installation, job.projectId());
        } catch (RuntimeException e) {
            // Transient Clockify failure; leave the job in place for the next tick.
            log.warn("processDueJobs: failed to load state for job {} — leaving job for retry", job.jobId(), e);
            return;
        }

        boolean targetStillRunning = runningEntries.stream()
                .anyMatch(entry -> job.timeEntryId().equals(entry.timeEntryId())
                        && job.userId().equals(entry.userId()));
        if (!targetStillRunning) {
            cutoffJobStore.deleteByJobId(job.jobId());
            return;
        }

        // Re-assess so user-side changes (unchecked cap, pause, manual stop) short-circuit
        // before we push any side effects.
        Assessment assessment = cutoffPlanner.assess(projectState, usage, runningEntries, now, source + ":due-job", null);
        if (assessment.exceededReason() == null && !assessment.lockNow()) {
            cutoffJobStore.deleteByJobId(job.jobId());
            return;
        }

        GuardReason reason = assessment.exceededReason() != null
                ? assessment.exceededReason()
                : assessment.plannedReason();

        // Ownership-confirm delete before the side effect: if another instance already
        // claimed this job, deleteByJobId returns 0 and we skip.
        if (cutoffJobStore.deleteByJobId(job.jobId()) == 0) {
            return;
        }

        // BUG-11: wrap each side effect so partial failure does not split-brain the project.
        // If stopRunningTimer fails we reinsert the job so a future tick retries; if
        // lockProject fails after the timer stopped we log and let the next reconcile tick
        // catch up, rather than leaving the DB and Clockify in disagreement.
        try {
            backendApiClient.stopRunningTimer(installation, job.userId(), CLOCKIFY_TIMESTAMP.format(job.cutoffAt()));
        } catch (RuntimeException stopErr) {
            log.warn("stopRunningTimer failed for job {} — reinserting for retry on next tick", job.jobId(), stopErr);
            try {
                cutoffJobStore.save(job);
            } catch (RuntimeException reinsertErr) {
                log.warn("Failed to reinsert job {} after stopRunningTimer error; scheduler reconcile will recover", job.jobId(), reinsertErr);
            }
            return;
        }
        recordEvent(job.workspaceId(), job.projectId(), GuardEventType.TIMER_STOPPED, reason, source + ":due-job", null);

        try {
            projectLockService.lockProject(installation, projectState, reason);
            recordEvent(job.workspaceId(), job.projectId(), GuardEventType.LOCKED, reason, source + ":due-job", null);
        } catch (RuntimeException lockErr) {
            log.warn("lockProject failed for job {} after timer stopped — next reconcile tick will retry the lock", job.jobId(), lockErr);
        }
    }

    private void recordEvent(
            String workspaceId,
            String projectId,
            GuardEventType type,
            GuardReason reason,
            String source,
            JsonObject payload) {
        guardEventRecorder.record(workspaceId, projectId, type, reason, source, payload);
    }

    public void cancelWorkspaceJobs(String workspaceId) {
        cutoffJobStore.deleteByWorkspaceId(workspaceId);
    }

    private ProjectGuardSummary summarizeProject(
            InstallationRecord installation,
            String projectId,
            Optional<ProjectLockSnapshot> snapshot,
            List<RunningTimeEntry> runningEntries) {
        // BUG-08: capture `now` once. Previously clock.instant() was called twice and the
        // HTTP latency of loadRunningEntries/loadProjectUsage was being counted as elapsed
        // running time in `assess`, which pushed cutoffAt earlier than it should be.
        Instant now = clock.instant();
        ProjectState projectState = projectUsageService.loadProjectState(installation, projectId);
        ProjectCaps caps = projectState.caps();
        ProjectUsage usage = projectUsageService.loadProjectUsage(installation, projectState, now);
        Assessment assessment = cutoffPlanner.assess(projectState, usage, runningEntries, now, "summary", null);

        boolean activeCaps = caps != null && caps.hasActiveCaps();
        String status;
        if (!installation.active()) {
            status = "INACTIVE";
        } else if (!activeCaps) {
            status = snapshot.isPresent() ? "LOCKED" : "NO_ACTIVE_CAPS";
        } else if (snapshot.isPresent()) {
            status = "LOCKED";
        } else if (assessment.exceededReason() != null) {
            status = "OVER_CAP";
        } else if (assessment.cutoffAt() != null) {
            status = "CUTOFF_PENDING";
        } else {
            status = "OK";
        }

        long timeLimitMs = activeCaps ? caps.timeLimitMs() : 0L;
        boolean includeExpenses = activeCaps && caps.includeExpenses();
        BigDecimal budgetLimit = activeCaps ? caps.budgetLimit() : BigDecimal.ZERO;

        return new ProjectGuardSummary(
                installation.workspaceId(),
                projectState.projectId(),
                projectState.name(),
                activeCaps,
                snapshot.isPresent(),
                status,
                assessment.reason().name(),
                usage.trackedTimeMs(),
                timeLimitMs,
                usage.budgetUsage(includeExpenses),
                budgetLimit,
                runningEntries.size(),
                assessment.cutoffAt(),
                usage.resetWindow().nextResetAt(),
                snapshot.map(ProjectLockSnapshot::lockedAt).orElse(null));
    }

    private Set<String> knownProjectIds(InstallationRecord installation) {
        Set<String> projectIds = new LinkedHashSet<>();
        projectIds.addAll(cachedClockifyProjectIds(installation));
        // DB-derived IDs are always merged fresh so a just-created lock or cutoff-job is
        // reconciled on the very next pass instead of waiting for the 30s TTL to expire.
        projectLockService.findSnapshots(installation.workspaceId()).forEach(snapshot -> projectIds.add(snapshot.projectId()));
        cutoffJobStore.findByWorkspaceId(installation.workspaceId()).forEach(job -> projectIds.add(job.projectId()));
        return projectIds;
    }

    private Set<String> cachedClockifyProjectIds(InstallationRecord installation) {
        return clockifyProjectIdsCache.get(installation.workspaceId(), ws -> {
            Set<String> ids = new LinkedHashSet<>();
            backendApiClient.listProjects(installation)
                    .forEach(project -> ClockifyJson.string(project, "id").ifPresent(ids::add));
            return ids;
        });
    }

    private void syncCutoffJobs(
            InstallationRecord installation,
            String projectId,
            List<RunningTimeEntry> runningEntries,
            Instant cutoffAt) {
        Set<String> activeTimeEntryIds = new LinkedHashSet<>();
        for (RunningTimeEntry entry : runningEntries) {
            if (entry.timeEntryId() == null) {
                continue;
            }
            activeTimeEntryIds.add(entry.timeEntryId());
            upsertJob(installation.workspaceId(), projectId, entry.userId(), entry.timeEntryId(), cutoffAt);
        }

        cutoffJobStore.deleteStale(installation.workspaceId(), projectId, activeTimeEntryIds);
    }

    private void upsertJob(String workspaceId, String projectId, String userId, String timeEntryId, Instant cutoffAt) {
        cutoffJobStore.upsert(workspaceId, projectId, userId, timeEntryId, cutoffAt);
    }

    private void stopRunningEntries(InstallationRecord installation, List<RunningTimeEntry> runningEntries, Instant cutoffAt) {
        Set<String> users = new LinkedHashSet<>();
        for (RunningTimeEntry runningEntry : runningEntries) {
            if (runningEntry.userId() != null && users.add(runningEntry.userId())) {
                backendApiClient.stopRunningTimer(installation, runningEntry.userId(), CLOCKIFY_TIMESTAMP.format(cutoffAt));
            }
        }
    }

}

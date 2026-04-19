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
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.devodox.stopatestimate.model.entity.GuardEventEntity;
import com.devodox.stopatestimate.repository.CutoffJobRepository;
import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final CutoffJobRepository cutoffJobRepository;
    private final GuardEventRepository guardEventRepository;
    private final ClockifyBackendApiClient backendApiClient;
    private final Clock clock;

    public EstimateGuardService(
            ClockifyLifecycleService lifecycleService,
            ProjectUsageService projectUsageService,
            ProjectLockService projectLockService,
            CutoffJobStore cutoffJobStore,
            CutoffJobRepository cutoffJobRepository,
            GuardEventRepository guardEventRepository,
            ClockifyBackendApiClient backendApiClient,
            Clock clock) {
        this.lifecycleService = lifecycleService;
        this.projectUsageService = projectUsageService;
        this.projectLockService = projectLockService;
        this.cutoffJobStore = cutoffJobStore;
        this.cutoffJobRepository = cutoffJobRepository;
        this.guardEventRepository = guardEventRepository;
        this.backendApiClient = backendApiClient;
        this.clock = clock;
    }

    public void reconcileAll(String source) {
        for (InstallationRecord installation : lifecycleService.findAllInstallations()) {
            if (installation.active()) {
                reconcileKnownProjects(installation.workspaceId(), source);
            }
        }
    }

    public void reconcileKnownProjects(String workspaceId, String source) {
        InstallationRecord installation = lifecycleService.findInstallation(workspaceId).orElse(null);
        if (installation == null) {
            return;
        }
        for (String projectId : knownProjectIds(installation)) {
            reconcileProject(workspaceId, projectId, source, null);
        }
    }

    public void reconcileProject(String workspaceId, String projectId, String source, JsonObject payload) {
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
        List<RunningTimeEntry> runningEntries = projectUsageService.loadRunningEntries(installation, projectId);
        Assessment assessment = assess(projectState, usage, runningEntries, now, source, payload);

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

        List<ProjectGuardSummary> summaries = new ArrayList<>();
        for (String projectId : knownProjectIds(installation)) {
            ProjectGuardSummary summary = summarizeProject(installation, projectId);
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
        Assessment assessment = assess(projectState, usage, runningEntries, now, source + ":due-job", null);
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
        GuardEventEntity row = new GuardEventEntity();
        row.setWorkspaceId(workspaceId);
        row.setProjectId(projectId);
        row.setEventType(type.name());
        row.setGuardReason(reason == null ? null : reason.name());
        row.setSource(source);
        row.setPayloadFingerprint(fingerprint(payload));
        guardEventRepository.save(row);
    }

    private static String fingerprint(JsonObject payload) {
        if (payload == null) {
            return "scheduler";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            // 16 hex chars = 64 bits of collision resistance; plenty for audit-trail dedup.
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "scheduler";
        }
    }

    public void cancelWorkspaceJobs(String workspaceId) {
        cutoffJobStore.deleteByWorkspaceId(workspaceId);
    }

    private ProjectGuardSummary summarizeProject(InstallationRecord installation, String projectId) {
        // BUG-08: capture `now` once. Previously clock.instant() was called twice and the
        // HTTP latency of loadRunningEntries/loadProjectUsage was being counted as elapsed
        // running time in `assess`, which pushed cutoffAt earlier than it should be.
        Instant now = clock.instant();
        ProjectState projectState = projectUsageService.loadProjectState(installation, projectId);
        ProjectCaps caps = projectState.caps();
        ProjectUsage usage = projectUsageService.loadProjectUsage(installation, projectState, now);
        List<RunningTimeEntry> runningEntries = projectUsageService.loadRunningEntries(installation, projectId);
        Assessment assessment = assess(projectState, usage, runningEntries, now, "summary", null);
        Optional<ProjectLockSnapshot> snapshot = projectLockService.findSnapshot(installation.workspaceId(), projectId);

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
        backendApiClient.listProjects(installation).forEach(project -> ClockifyJson.string(project, "id").ifPresent(projectIds::add));
        projectLockService.findSnapshots(installation.workspaceId()).forEach(snapshot -> projectIds.add(snapshot.projectId()));
        cutoffJobStore.findByWorkspaceId(installation.workspaceId()).forEach(job -> projectIds.add(job.projectId()));
        return projectIds;
    }

    private Assessment assess(
            ProjectState projectState,
            ProjectUsage usage,
            List<RunningTimeEntry> runningEntries,
            Instant now,
            String source,
            JsonObject payload) {
        ProjectCaps caps = projectState.caps();
        if (caps == null || !caps.hasActiveCaps()) {
            return new Assessment(GuardReason.NO_ACTIVE_CAPS, null, false, GuardReason.NO_ACTIVE_CAPS);
        }
        GuardReason exceeded = exceededReason(caps, usage);
        if (exceeded != null) {
            return new Assessment(exceeded, immediateCutoff(source, payload, projectState, usage, runningEntries, now), true, exceeded);
        }
        CutoffPlan plan = cutoffPlan(projectState, usage, runningEntries, now);
        return new Assessment(GuardReason.BELOW_CAPS, plan.cutoffAt(), plan.lockNow(), plan.reason());
    }

    private GuardReason exceededReason(ProjectCaps caps, ProjectUsage usage) {
        if (caps.timeCapActive() && usage.trackedTimeMs() >= caps.timeLimitMs()) {
            return GuardReason.TIME_CAP_REACHED;
        }
        if (caps.budgetCapActive() && usage.budgetUsage(caps.includeExpenses()).compareTo(caps.budgetLimit()) >= 0) {
            return GuardReason.BUDGET_CAP_REACHED;
        }
        return null;
    }

    private Instant immediateCutoff(
            String source,
            JsonObject payload,
            ProjectState projectState,
            ProjectUsage usage,
            List<RunningTimeEntry> runningEntries,
            Instant now) {
        if (!source.contains("NEW_TIMER_STARTED") || payload == null || runningEntries.size() != 1) {
            return now;
        }
        RunningTimeEntry entry = runningEntries.get(0);
        if (entry.start() == null) {
            return now;
        }

        long elapsedMs = Math.max(0L, now.toEpochMilli() - entry.start().toEpochMilli());
        long trackedBeforeCurrent = Math.max(0L, usage.trackedTimeMs() - elapsedMs);
        BigDecimal budgetBeforeCurrent = usage.budgetUsage(projectState.caps().includeExpenses())
                .subtract(elapsedBillable(projectState, entry, elapsedMs))
                .max(BigDecimal.ZERO);

        if ((projectState.caps().timeCapActive() && trackedBeforeCurrent >= projectState.caps().timeLimitMs())
                || (projectState.caps().budgetCapActive()
                && budgetBeforeCurrent.compareTo(projectState.caps().budgetLimit()) >= 0)) {
            return entry.start();
        }
        return now;
    }

    private CutoffPlan cutoffPlan(ProjectState projectState, ProjectUsage usage, List<RunningTimeEntry> runningEntries, Instant now) {
        if (runningEntries.isEmpty()) {
            return new CutoffPlan(null, false, GuardReason.BELOW_CAPS);
        }

        ProjectCaps caps = projectState.caps();
        List<Instant> candidateCutoffs = new ArrayList<>();

        if (caps.timeCapActive()) {
            // Clockify Reports summary only counts completed entries, so usage.trackedTimeMs() does
            // not include elapsed time on still-running timers. Subtract that elapsed time here,
            // otherwise the cutoff keeps sliding forward by (cap - tracked) on every tick and the
            // scheduler never reaches lockNow when the due job fires.
            long elapsedRunningMs = runningEntries.stream()
                    .filter(e -> e.start() != null)
                    .mapToLong(e -> Math.max(0L, now.toEpochMilli() - e.start().toEpochMilli()))
                    .sum();
            long remainingMs = caps.timeLimitMs() - usage.trackedTimeMs() - elapsedRunningMs;
            if (remainingMs <= 0) {
                return new CutoffPlan(now, true, GuardReason.TIME_CAP_REACHED);
            }
            long concurrentTimers = runningEntries.size();
            candidateCutoffs.add(now.plusMillis(Math.max(0L, remainingMs / concurrentTimers)));
        }

        if (caps.budgetCapActive()) {
            BigDecimal remainingBudget = caps.budgetLimit().subtract(usage.budgetUsage(caps.includeExpenses()));
            // Same rationale as the time branch: subtract the billable amount already accrued on
            // still-running billable timers, so repeated reconciles converge rather than keeping
            // remaining flat. Non-billable entries do not accrue against the budget cap.
            BigDecimal elapsedBillableRunning = runningEntries.stream()
                    .filter(e -> e.start() != null)
                    .filter(RunningTimeEntry::billable)
                    .map(e -> elapsedBillable(projectState, e,
                            Math.max(0L, now.toEpochMilli() - e.start().toEpochMilli())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            remainingBudget = remainingBudget.subtract(elapsedBillableRunning);
            if (remainingBudget.compareTo(BigDecimal.ZERO) <= 0) {
                return new CutoffPlan(now, true, GuardReason.BUDGET_CAP_REACHED);
            }
            boolean anyBillable = runningEntries.stream().anyMatch(RunningTimeEntry::billable);
            if (anyBillable) {
                BigDecimal aggregateRate = aggregateBudgetRatePerMillisecond(projectState, runningEntries);
                if (aggregateRate.compareTo(BigDecimal.ZERO) <= 0) {
                    // SPEC §5 fail-closed: a billable timer is running but we can't determine its
                    // hourly rate, so budget math is ambiguous — lock immediately rather than
                    // letting the timer accrue past the cap.
                    return new CutoffPlan(now, true, GuardReason.BUDGET_CAP_REACHED);
                }
                long millis = remainingBudget.divide(aggregateRate, 0, RoundingMode.DOWN).longValue();
                candidateCutoffs.add(now.plusMillis(Math.max(0L, millis)));
            }
            // If no entries are billable, no labor accrues against the budget on this tick —
            // omit a budget candidate and let the time branch (if active) drive the cutoff.
        }

        Instant cutoffAt = candidateCutoffs.stream().min(Comparator.naturalOrder()).orElse(null);
        if (cutoffAt != null && !cutoffAt.isAfter(now)) {
            return new CutoffPlan(now, true, GuardReason.TIME_CAP_REACHED);
        }
        return new CutoffPlan(cutoffAt, false, GuardReason.BELOW_CAPS);
    }

    private BigDecimal aggregateBudgetRatePerMillisecond(ProjectState projectState, List<RunningTimeEntry> runningEntries) {
        // Caller is expected to have at least one billable entry. Non-billable entries are
        // ignored. ZERO signals "rate missing on a billable entry" — caller must treat that
        // as fail-closed per SPEC §5.
        BigDecimal total = BigDecimal.ZERO;
        for (RunningTimeEntry entry : runningEntries) {
            if (!entry.billable()) {
                continue;
            }
            Optional<RateInfo> hourlyRate = projectState.hourlyRateForUser(entry.userId());
            if (hourlyRate.isEmpty() || !hourlyRate.get().present()) {
                return BigDecimal.ZERO;
            }
            total = total.add(hourlyRate.get().amount().divide(BigDecimal.valueOf(3_600_000L), 12, RoundingMode.HALF_UP));
        }
        return total;
    }

    private BigDecimal elapsedBillable(ProjectState projectState, RunningTimeEntry entry, long elapsedMs) {
        if (!entry.billable()) {
            return BigDecimal.ZERO;
        }
        return projectState.hourlyRateForUser(entry.userId())
                .filter(RateInfo::present)
                .map(rate -> rate.amount()
                        .multiply(BigDecimal.valueOf(elapsedMs))
                        .divide(BigDecimal.valueOf(3_600_000L), 12, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
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

        if (activeTimeEntryIds.isEmpty()) {
            cutoffJobStore.deleteByProject(installation.workspaceId(), projectId);
        } else {
            cutoffJobRepository.deleteStaleByProject(installation.workspaceId(), projectId, activeTimeEntryIds);
        }
    }

    private void upsertJob(String workspaceId, String projectId, String userId, String timeEntryId, Instant cutoffAt) {
        Optional<PendingCutoffJob> existing = cutoffJobStore.findByTimeEntryId(workspaceId, timeEntryId);
        if (existing.isPresent() && existing.get().cutoffAt().equals(cutoffAt)) {
            return;
        }
        existing.ifPresent(job -> cutoffJobStore.deleteByJobId(job.jobId()));
        try {
            cutoffJobStore.save(PendingCutoffJob.create(workspaceId, projectId, userId, timeEntryId, cutoffAt));
        } catch (DataIntegrityViolationException race) {
            // A parallel webhook delivery just inserted the (workspace_id, time_entry_id) row
            // (uk_cutoff_jobs_workspace_time_entry in V1_0_5). Re-read and overwrite so our
            // cutoffAt wins — subsequent ticks will converge to the same value anyway.
            PendingCutoffJob winner = cutoffJobStore.findByTimeEntryId(workspaceId, timeEntryId).orElse(null);
            if (winner == null || winner.cutoffAt().equals(cutoffAt)) {
                return;
            }
            cutoffJobStore.deleteByJobId(winner.jobId());
            cutoffJobStore.save(PendingCutoffJob.create(workspaceId, projectId, userId, timeEntryId, cutoffAt));
        }
    }

    private void stopRunningEntries(InstallationRecord installation, List<RunningTimeEntry> runningEntries, Instant cutoffAt) {
        Set<String> users = new LinkedHashSet<>();
        for (RunningTimeEntry runningEntry : runningEntries) {
            if (runningEntry.userId() != null && users.add(runningEntry.userId())) {
                backendApiClient.stopRunningTimer(installation, runningEntry.userId(), CLOCKIFY_TIMESTAMP.format(cutoffAt));
            }
        }
    }

    private record CutoffPlan(Instant cutoffAt, boolean lockNow, GuardReason reason) {
    }

    private record Assessment(GuardReason reason, Instant cutoffAt, boolean lockNow, GuardReason plannedReason) {
        GuardReason exceededReason() {
            return reason == GuardReason.TIME_CAP_REACHED || reason == GuardReason.BUDGET_CAP_REACHED ? reason : null;
        }
    }
}

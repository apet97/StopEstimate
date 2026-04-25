package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectGuardSummary;
import com.devodox.stopatestimate.model.ProjectLockSnapshot;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.ProjectUsage;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.devodox.stopatestimate.service.CutoffPlanner.Assessment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProjectSummaryService {

    private final ClockifyLifecycleService lifecycleService;
    private final ProjectUsageService projectUsageService;
    private final ProjectLockService projectLockService;
    private final CutoffPlanner cutoffPlanner;
    private final KnownProjectIdsResolver knownProjectIdsResolver;
    private final Clock clock;

    public ProjectSummaryService(
            ClockifyLifecycleService lifecycleService,
            ProjectUsageService projectUsageService,
            ProjectLockService projectLockService,
            CutoffPlanner cutoffPlanner,
            KnownProjectIdsResolver knownProjectIdsResolver,
            Clock clock) {
        this.lifecycleService = lifecycleService;
        this.projectUsageService = projectUsageService;
        this.projectLockService = projectLockService;
        this.cutoffPlanner = cutoffPlanner;
        this.knownProjectIdsResolver = knownProjectIdsResolver;
        this.clock = clock;
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
        for (String projectId : knownProjectIdsResolver.resolve(installation)) {
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
}

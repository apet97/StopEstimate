package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.store.CutoffJobStore;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class KnownProjectIdsResolver {

    private final ClockifyBackendApiClient backendApiClient;
    private final ProjectLockService projectLockService;
    private final CutoffJobStore cutoffJobStore;

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

    public KnownProjectIdsResolver(
            ClockifyBackendApiClient backendApiClient,
            ProjectLockService projectLockService,
            CutoffJobStore cutoffJobStore) {
        this.backendApiClient = backendApiClient;
        this.projectLockService = projectLockService;
        this.cutoffJobStore = cutoffJobStore;
    }

    public Set<String> resolve(InstallationRecord installation) {
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
}

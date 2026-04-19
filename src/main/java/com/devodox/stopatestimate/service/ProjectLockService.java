package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyApiException;
import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectLockSnapshot;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.store.LockSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class ProjectLockService {
    private static final Logger log = LoggerFactory.getLogger(ProjectLockService.class);

    private final ClockifyBackendApiClient backendApiClient;
    private final AccessResolutionService accessResolutionService;
    private final LockSnapshotStore lockSnapshotStore;
    private final Clock clock;

    public ProjectLockService(
            ClockifyBackendApiClient backendApiClient,
            AccessResolutionService accessResolutionService,
            LockSnapshotStore lockSnapshotStore,
            Clock clock) {
        this.backendApiClient = backendApiClient;
        this.accessResolutionService = accessResolutionService;
        this.lockSnapshotStore = lockSnapshotStore;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isLocked(String workspaceId, String projectId) {
        return findSnapshot(workspaceId, projectId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<ProjectLockSnapshot> findSnapshots(String workspaceId) {
        return lockSnapshotStore.findByWorkspaceId(workspaceId);
    }

    @Transactional(readOnly = true)
    public Optional<ProjectLockSnapshot> findSnapshot(String workspaceId, String projectId) {
        return lockSnapshotStore.findByProject(workspaceId, projectId);
    }

    @Transactional
    public void lockProject(InstallationRecord installation, ProjectState projectState, GuardReason reason) {
        ProjectLockSnapshot existing = lockSnapshotStore.findByProject(projectState.workspaceId(), projectState.projectId())
                .orElse(null);
        ProjectLockSnapshot snapshot = existing != null
                ? existing
                : new ProjectLockSnapshot(
                        projectState.workspaceId(),
                        projectState.projectId(),
                        projectState.isPublic(),
                        projectState.directMembers(),
                        projectState.userGroupIds(),
                        reason.name(),
                        clock.instant());

        // Resolve allowed members BEFORE writing the snapshot so a read-side failure here
        // leaves no DB state behind. Once the snapshot lands we are committed to the lock
        // attempt and any partial mutation below must be recoverable via unlockProject.
        List<com.devodox.stopatestimate.model.ProjectMemberAccess> allowedMembers =
                accessResolutionService.resolveAllowedMembers(installation, projectState);

        // SEC-02: save the snapshot BEFORE any Clockify mutation. If a mutation below fails
        // partway (e.g. visibility succeeds but memberships throw), a future reconcile must
        // still be able to find originalPublic/originalMembers to restore. Previously the
        // snapshot was written last, so a mid-lock crash permanently split-brained the
        // project: Clockify saw it as private, our DB saw it as unlocked, unlockProject
        // short-circuited, and nothing ever restored it.
        lockSnapshotStore.save(snapshot);

        try {
            backendApiClient.updateProjectVisibility(installation, projectState.projectId(), false);
            try {
                backendApiClient.updateProjectMemberships(installation, projectState.projectId(), allowedMembers, List.of());
            } catch (ClockifyApiException e) {
                log.warn("Project membership group clear failed for {}. Falling back to direct members only.", projectState.projectId(), e);
                backendApiClient.updateProjectMemberships(installation, projectState.projectId(), allowedMembers, null);
            }
        } catch (RuntimeException lockErr) {
            // Compensate a partial lock. Before this, if updateProjectMemberships (including its
            // direct-users fallback) threw after updateProjectVisibility had already flipped the
            // project private, we exited with the project "half-locked": private in Clockify,
            // original members still assigned. Scheduler reconvergence did NOT heal this class
            // of state — reconcileProject skips lockProject when a snapshot already exists
            // (see EstimateGuardService line ~154), so a project that stayed over cap could
            // linger half-locked indefinitely, healed only when it transitioned to unlock.
            //
            // Reverse the visibility flip (idempotent HTTP call — safe even if the forward
            // flip never reached the server) and drop the snapshot. Spring's @Transactional
            // rollback handles the snapshot save too when we rethrow, but the explicit
            // deleteByProject makes the compensation intent visible at the call site and
            // keeps it correct if the transactional annotation is ever revisited.
            //
            // This does NOT regress BUG-01/BUG-06: the compensation adds no new @Transactional
            // boundary, and the two extra calls are the same kind already used by the unlock
            // path (updateProjectVisibility + deleteByProject), so pool/connection behavior is
            // unchanged.
            try {
                backendApiClient.updateProjectVisibility(installation, projectState.projectId(), projectState.isPublic());
                lockSnapshotStore.deleteByProject(projectState.workspaceId(), projectState.projectId());
            } catch (RuntimeException rollbackErr) {
                // Compensation itself failed. Bubble the original exception with the rollback
                // error attached so operators see both. The forward transaction will still
                // be rolled back by Spring on rethrow, so the DB snapshot does not survive.
                // The project may remain half-locked in Clockify until a subsequent tick
                // retries lockProject from a clean DB state.
                log.warn("lockProject compensation failed for {} — project may be temporarily half-locked until retry",
                        projectState.projectId(), rollbackErr);
                lockErr.addSuppressed(rollbackErr);
            }
            throw lockErr;
        }
    }

    @Transactional
    public void unlockProject(InstallationRecord installation, String projectId) {
        ProjectLockSnapshot snapshot = lockSnapshotStore.findByProject(installation.workspaceId(), projectId).orElse(null);
        if (snapshot == null) {
            return;
        }
        unlockFromSnapshot(installation, snapshot);
    }

    @Transactional
    public void unlockWorkspaceProjects(InstallationRecord installation) {
        // DB-09: iterate the pre-loaded snapshots directly instead of round-tripping through
        // unlockProject(projectId), which would re-fetch each snapshot we already have.
        for (ProjectLockSnapshot snapshot : findSnapshots(installation.workspaceId())) {
            unlockFromSnapshot(installation, snapshot);
        }
    }

    private void unlockFromSnapshot(InstallationRecord installation, ProjectLockSnapshot snapshot) {
        String projectId = snapshot.projectId();
        try {
            backendApiClient.updateProjectMemberships(
                    installation,
                    projectId,
                    snapshot.originalMembers(),
                    snapshot.originalUserGroupIds());
        } catch (ClockifyApiException e) {
            log.warn("Project user group restore failed for {}. Restoring direct users only.", projectId, e);
            backendApiClient.updateProjectMemberships(
                    installation,
                    projectId,
                    snapshot.originalMembers(),
                    null);
        }
        backendApiClient.updateProjectVisibility(installation, projectId, snapshot.originalPublic());
        lockSnapshotStore.deleteByProject(snapshot.workspaceId(), projectId);
    }
}

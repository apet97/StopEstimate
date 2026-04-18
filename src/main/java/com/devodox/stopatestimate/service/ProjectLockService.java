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

        backendApiClient.updateProjectVisibility(installation, projectState.projectId(), false);
        List<com.devodox.stopatestimate.model.ProjectMemberAccess> allowedMembers =
                accessResolutionService.resolveAllowedMembers(installation, projectState);
        try {
            backendApiClient.updateProjectMemberships(installation, projectState.projectId(), allowedMembers, List.of());
        } catch (ClockifyApiException e) {
            log.warn("Project membership group clear failed for {}. Falling back to direct members only.", projectState.projectId(), e);
            backendApiClient.updateProjectMemberships(installation, projectState.projectId(), allowedMembers, null);
        }

        lockSnapshotStore.save(snapshot);
    }

    @Transactional
    public void unlockProject(InstallationRecord installation, String projectId) {
        ProjectLockSnapshot snapshot = lockSnapshotStore.findByProject(installation.workspaceId(), projectId).orElse(null);
        if (snapshot == null) {
            return;
        }

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

    @Transactional
    public void unlockWorkspaceProjects(InstallationRecord installation) {
        for (ProjectLockSnapshot snapshot : findSnapshots(installation.workspaceId())) {
            unlockProject(installation, snapshot.projectId());
        }
    }
}

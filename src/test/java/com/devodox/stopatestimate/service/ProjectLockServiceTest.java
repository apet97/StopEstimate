package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyApiException;
import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.ResetWindowSchedule;
import com.devodox.stopatestimate.store.LockSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-B1: compensation for partial failure inside {@link ProjectLockService#lockProject}.
 * <p>
 * The guard previously exited with a half-locked project (private in Clockify, original members
 * still assigned) when updateProjectMemberships threw after updateProjectVisibility had already
 * flipped visibility. Reconvergence did not heal this class of state because the scheduler's
 * reconcile path skips lockProject when a snapshot exists, so a project that stayed over cap
 * lingered half-locked indefinitely. These tests lock in the compensation contract: on any
 * post-visibility failure, lockProject must leave Clockify in its pre-lock visibility state and
 * drop the snapshot before rethrowing.
 */
class ProjectLockServiceTest {

    private ClockifyBackendApiClient backendApiClient;
    private AccessResolutionService accessResolutionService;
    private LockSnapshotStore lockSnapshotStore;
    private ProjectLockService service;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        backendApiClient = Mockito.mock(ClockifyBackendApiClient.class);
        accessResolutionService = Mockito.mock(AccessResolutionService.class);
        lockSnapshotStore = Mockito.mock(LockSnapshotStore.class);
        service = new ProjectLockService(backendApiClient, accessResolutionService, lockSnapshotStore, fixedClock);

        when(accessResolutionService.resolveAllowedMembers(any(), any())).thenReturn(List.of());
        when(lockSnapshotStore.findByProject(anyString(), anyString())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void happyPath_flipsVisibilityOnce_setsMemberships_savesSnapshot_noRollback() {
        service.lockProject(installation(), publicProject(), GuardReason.TIME_CAP_REACHED);

        InOrder order = inOrder(lockSnapshotStore, backendApiClient);
        order.verify(lockSnapshotStore).save(any());
        order.verify(backendApiClient).updateProjectVisibility(any(), eq("project-1"), eq(false));
        order.verify(backendApiClient).updateProjectMemberships(any(), eq("project-1"), any(), eq(List.of()));
        // No compensation: visibility is never re-flipped, snapshot is never deleted.
        verify(backendApiClient, times(1)).updateProjectVisibility(any(), anyString(), Mockito.anyBoolean());
        verify(lockSnapshotStore, never()).deleteByProject(anyString(), anyString());
    }

    @Test
    void membershipsFails_bothPrimaryAndFallback_rollsBackVisibilityAndDeletesSnapshot() {
        // Primary call (groups cleared) fails with ClockifyApiException, then the fallback
        // (userGroupIds=null) also fails. Both branches escape the inner catch and trip the
        // outer compensation path — simulates the worst case where neither memberships API
        // shape works and the project has been flipped private without members stripped.
        doThrow(new ClockifyApiException("clear-groups failed"))
                .when(backendApiClient)
                .updateProjectMemberships(any(), eq("project-1"), any(), eq(List.of()));
        doThrow(new ClockifyApiException("fallback failed"))
                .when(backendApiClient)
                .updateProjectMemberships(any(), eq("project-1"), any(), eq(null));

        assertThatThrownBy(() -> service.lockProject(installation(), publicProject(), GuardReason.TIME_CAP_REACHED))
                .isInstanceOf(ClockifyApiException.class)
                .hasMessage("fallback failed");

        // Compensation: visibility was flipped false (forward), then restored to original true.
        InOrder order = inOrder(backendApiClient, lockSnapshotStore);
        order.verify(backendApiClient).updateProjectVisibility(any(), eq("project-1"), eq(false));
        order.verify(backendApiClient).updateProjectVisibility(any(), eq("project-1"), eq(true));
        order.verify(lockSnapshotStore).deleteByProject(eq("ws-1"), eq("project-1"));
    }

    @Test
    void originallyPrivateProject_rollsBackToPrivate_notPublic() {
        // A project that was already private before the lock attempt must be rolled back to
        // private, not flipped to public. Guards against hardcoding `true` in the compensation.
        doThrow(new ClockifyApiException("boom"))
                .when(backendApiClient)
                .updateProjectMemberships(any(), anyString(), any(), any());

        assertThatThrownBy(() -> service.lockProject(installation(), privateProject(), GuardReason.BUDGET_CAP_REACHED))
                .isInstanceOf(ClockifyApiException.class);

        // Forward flipped false; rollback flipped back to false (the original). Two calls total,
        // both with the same boolean — the second call is the compensation using
        // projectState.isPublic()=false.
        verify(backendApiClient, times(2)).updateProjectVisibility(any(), eq("project-1"), eq(false));
        verify(backendApiClient, never()).updateProjectVisibility(any(), anyString(), eq(true));
        verify(lockSnapshotStore).deleteByProject(eq("ws-1"), eq("project-1"));
    }

    @Test
    void forwardVisibilityFails_stillAttemptsRollbackAndSnapshotDelete() {
        // If the forward visibility flip itself throws, it may have partially taken effect on
        // Clockify (flaky response after a successful write) or may not have landed at all.
        // Either way, updateProjectVisibility is idempotent, so calling it with the original
        // value is safe. The snapshot must still be deleted so reconcile retries from clean
        // state on the next tick.
        doThrow(new ClockifyApiException("visibility flip failed"))
                .when(backendApiClient)
                .updateProjectVisibility(any(), eq("project-1"), eq(false));

        assertThatThrownBy(() -> service.lockProject(installation(), publicProject(), GuardReason.TIME_CAP_REACHED))
                .isInstanceOf(ClockifyApiException.class);

        verify(backendApiClient).updateProjectVisibility(any(), eq("project-1"), eq(true));
        verify(lockSnapshotStore).deleteByProject(eq("ws-1"), eq("project-1"));
        // Memberships were never called because the forward visibility threw first.
        verify(backendApiClient, never()).updateProjectMemberships(any(), anyString(), any(), any());
    }

    @Test
    void rollbackVisibilityAlsoFails_suppressesRollbackError_stillThrowsOriginal() {
        // Compensation itself failing (extreme case — Clockify outage) must not mask the
        // original lock failure. Caller (processDueJob) must see the ClockifyApiException so
        // its outer catch can log and leave recovery to the next reconcile tick.
        ClockifyApiException original = new ClockifyApiException("memberships failed");
        doThrow(original)
                .when(backendApiClient)
                .updateProjectMemberships(any(), anyString(), any(), any());
        ClockifyApiException rollback = new ClockifyApiException("rollback failed");
        doThrow(rollback)
                .when(backendApiClient)
                .updateProjectVisibility(any(), eq("project-1"), eq(true));

        assertThatThrownBy(() -> service.lockProject(installation(), publicProject(), GuardReason.TIME_CAP_REACHED))
                .isSameAs(original)
                .hasSuppressedException(rollback);
    }

    private InstallationRecord installation() {
        return new InstallationRecord(
                "ws-1",
                "addon-123",
                "addon-user",
                "owner-user",
                "installation-token",
                "https://api.clockify.me/api",
                "https://reports.api.clockify.me",
                Map.of(),
                AddonStatus.ACTIVE,
                true,
                "ENFORCE",
                "MONTHLY",
                fixedClock.instant(),
                fixedClock.instant());
    }

    private ProjectState publicProject() {
        return projectState(true);
    }

    private ProjectState privateProject() {
        return projectState(false);
    }

    private ProjectState projectState(boolean isPublic) {
        return new ProjectState(
                "ws-1",
                "project-1",
                "Project 1",
                isPublic,
                List.of(),
                List.of(),
                RateInfo.of(BigDecimal.valueOf(1000), "USD"),
                RateInfo.empty(),
                new ProjectCaps(true, 3_600_000L, "MONTHLY", false, false, BigDecimal.ZERO, "MONTHLY", false, ResetWindowSchedule.none()));
    }
}

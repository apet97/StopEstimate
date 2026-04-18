package com.devodox.stopatestimate.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "project_lock_snapshots")
public class ProjectLockSnapshotEntity {

    @EmbeddedId
    private Key id;

    @Column(name = "original_is_public", nullable = false)
    private boolean originalIsPublic;

    @Column(name = "original_memberships_json", columnDefinition = "TEXT")
    private String originalMembershipsJson;

    @Column(name = "original_user_groups_json", columnDefinition = "TEXT")
    private String originalUserGroupsJson;

    @Column(name = "lock_reason", length = 64, nullable = false)
    private String lockReason;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (lockedAt == null) {
            lockedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public Key getId() { return id; }
    public void setId(Key id) { this.id = id; }

    public boolean isOriginalIsPublic() { return originalIsPublic; }
    public void setOriginalIsPublic(boolean v) { this.originalIsPublic = v; }

    public String getOriginalMembershipsJson() { return originalMembershipsJson; }
    public void setOriginalMembershipsJson(String v) { this.originalMembershipsJson = v; }

    public String getOriginalUserGroupsJson() { return originalUserGroupsJson; }
    public void setOriginalUserGroupsJson(String v) { this.originalUserGroupsJson = v; }

    public String getLockReason() { return lockReason; }
    public void setLockReason(String v) { this.lockReason = v; }

    public Instant getLockedAt() { return lockedAt; }
    public void setLockedAt(Instant v) { this.lockedAt = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "workspace_id", length = 64, nullable = false)
        private String workspaceId;

        @Column(name = "project_id", length = 64, nullable = false)
        private String projectId;

        public Key() {}

        public Key(String workspaceId, String projectId) {
            this.workspaceId = workspaceId;
            this.projectId = projectId;
        }

        public String getWorkspaceId() { return workspaceId; }
        public void setWorkspaceId(String v) { this.workspaceId = v; }

        public String getProjectId() { return projectId; }
        public void setProjectId(String v) { this.projectId = v; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(workspaceId, key.workspaceId) && Objects.equals(projectId, key.projectId);
        }

        @Override
        public int hashCode() { return Objects.hash(workspaceId, projectId); }
    }
}

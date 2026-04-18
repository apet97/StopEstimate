package com.devodox.stopatestimate.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "cutoff_jobs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cutoff_jobs_workspace_time_entry", columnNames = {"workspace_id", "time_entry_id"})
})
public class CutoffJobEntity {

    @Id
    @Column(name = "job_id", length = 64, nullable = false)
    private String jobId;

    @Column(name = "workspace_id", length = 64, nullable = false)
    private String workspaceId;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "time_entry_id", length = 64, nullable = false)
    private String timeEntryId;

    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getJobId() { return jobId; }
    public void setJobId(String v) { this.jobId = v; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String v) { this.workspaceId = v; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { this.projectId = v; }

    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }

    public String getTimeEntryId() { return timeEntryId; }
    public void setTimeEntryId(String v) { this.timeEntryId = v; }

    public Instant getCutoffAt() { return cutoffAt; }
    public void setCutoffAt(Instant v) { this.cutoffAt = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
}

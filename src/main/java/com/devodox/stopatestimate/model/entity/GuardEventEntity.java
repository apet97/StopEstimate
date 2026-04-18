package com.devodox.stopatestimate.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "guard_events")
public class GuardEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", length = 64, nullable = false)
    private String workspaceId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "guard_reason", length = 64)
    private String guardReason;

    @Column(name = "source", length = 128)
    private String source;

    @Column(name = "payload_fingerprint", length = 128)
    private String payloadFingerprint;

    @Column(name = "outcome", length = 64)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String v) { this.workspaceId = v; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { this.projectId = v; }

    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }

    public String getGuardReason() { return guardReason; }
    public void setGuardReason(String v) { this.guardReason = v; }

    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }

    public String getPayloadFingerprint() { return payloadFingerprint; }
    public void setPayloadFingerprint(String v) { this.payloadFingerprint = v; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}

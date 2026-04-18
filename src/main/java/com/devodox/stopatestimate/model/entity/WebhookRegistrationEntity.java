package com.devodox.stopatestimate.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "webhook_registrations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_registrations_workspace_path",
                columnNames = {"workspace_id", "route_path"}))
public class WebhookRegistrationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", length = 64, nullable = false)
    private String workspaceId;

    @Column(name = "route_path", length = 256, nullable = false)
    private String routePath;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "webhook_token_enc", nullable = false, columnDefinition = "TEXT")
    private String webhookTokenEnc;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String v) { this.workspaceId = v; }

    public String getRoutePath() { return routePath; }
    public void setRoutePath(String v) { this.routePath = v; }

    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }

    public String getWebhookTokenEnc() { return webhookTokenEnc; }
    public void setWebhookTokenEnc(String v) { this.webhookTokenEnc = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}

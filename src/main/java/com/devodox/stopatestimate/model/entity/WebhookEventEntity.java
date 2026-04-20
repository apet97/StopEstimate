package com.devodox.stopatestimate.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Dedupes Clockify webhook deliveries. Clockify retries the same event on transient failures;
 * matching on (event_id, signature_hash) short-circuits before any side effect runs.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEventEntity implements Persistable<WebhookEventEntity.Key> {

    @EmbeddedId
    private Key id;

    @Column(name = "workspace_id", length = 64, nullable = false)
    private String workspaceId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Transient
    private boolean newEntity = true;

    @PrePersist
    public void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    @PostPersist
    @PostLoad
    public void markNotNew() {
        newEntity = false;
    }

    public WebhookEventEntity() {}

    public WebhookEventEntity(String eventId, String signatureHash, String workspaceId) {
        this.id = new Key(eventId, signatureHash);
        this.workspaceId = workspaceId;
    }

    @Override
    public Key getId() { return id; }
    public void setId(Key id) { this.id = id; }

    @Override
    public boolean isNew() { return newEntity; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String v) { this.workspaceId = v; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "event_id", length = 128, nullable = false)
        private String eventId;

        @Column(name = "signature_hash", length = 128, nullable = false)
        private String signatureHash;

        public Key() {}

        public Key(String eventId, String signatureHash) {
            this.eventId = eventId;
            this.signatureHash = signatureHash;
        }

        public String getEventId() { return eventId; }
        public void setEventId(String v) { this.eventId = v; }

        public String getSignatureHash() { return signatureHash; }
        public void setSignatureHash(String v) { this.signatureHash = v; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(eventId, k.eventId) && Objects.equals(signatureHash, k.signatureHash);
        }

        @Override
        public int hashCode() { return Objects.hash(eventId, signatureHash); }
    }
}

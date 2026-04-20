package com.devodox.stopatestimate.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "installations")
public class InstallationEntity {

    @Id
    @Column(name = "workspace_id", length = 64, nullable = false)
    private String workspaceId;

    @Column(name = "addon_id", length = 64, nullable = false)
    private String addonId;

    @Column(name = "addon_user_id", length = 64)
    private String addonUserId;

    @Column(name = "owner_user_id", length = 64)
    private String ownerUserId;

    @Column(name = "installation_token_enc", nullable = false, columnDefinition = "TEXT")
    private String installationTokenEnc;

    @Column(name = "backend_url", nullable = false, length = 512)
    private String backendUrl;

    @Column(name = "reports_url", nullable = false, length = 512)
    private String reportsUrl;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "default_reset_cadence", length = 16, nullable = false)
    private String defaultResetCadence;

    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (installedAt == null) {
            installedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String v) { this.workspaceId = v; }

    public String getAddonId() { return addonId; }
    public void setAddonId(String v) { this.addonId = v; }

    public String getAddonUserId() { return addonUserId; }
    public void setAddonUserId(String v) { this.addonUserId = v; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String v) { this.ownerUserId = v; }

    public String getInstallationTokenEnc() { return installationTokenEnc; }
    public void setInstallationTokenEnc(String v) { this.installationTokenEnc = v; }

    public String getBackendUrl() { return backendUrl; }
    public void setBackendUrl(String v) { this.backendUrl = v; }

    public String getReportsUrl() { return reportsUrl; }
    public void setReportsUrl(String v) { this.reportsUrl = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getDefaultResetCadence() { return defaultResetCadence; }
    public void setDefaultResetCadence(String v) { this.defaultResetCadence = v; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }

    public Instant getInstalledAt() { return installedAt; }
    public void setInstalledAt(Instant v) { this.installedAt = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
}

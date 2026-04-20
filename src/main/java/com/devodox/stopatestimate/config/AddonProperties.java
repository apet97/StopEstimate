package com.devodox.stopatestimate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "addon")
public class AddonProperties {

    private String key = "stop-at-estimate";
    private String name = "Stop @ Estimate";
    private String description = "Stops timers and locks projects when time or budget estimates are reached.";
    // No default — operators must set ADDON_BASE_URL. Startup validation in SecurityConfig
    // rejects blank and well-known placeholder values so a misconfigured deploy can't boot.
    private String baseUrl;
    private String sidebarLabel = "Stop @ Estimate";
    private String defaultResetCadence = "NONE";
    private String encryptionKeyHex;
    private String encryptionSaltHex;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSidebarLabel() {
        return sidebarLabel;
    }

    public void setSidebarLabel(String sidebarLabel) {
        this.sidebarLabel = sidebarLabel;
    }

    public String getDefaultResetCadence() {
        return defaultResetCadence;
    }

    public void setDefaultResetCadence(String defaultResetCadence) {
        this.defaultResetCadence = defaultResetCadence;
    }

    public String getEncryptionKeyHex() {
        return encryptionKeyHex;
    }

    public void setEncryptionKeyHex(String encryptionKeyHex) {
        this.encryptionKeyHex = encryptionKeyHex;
    }

    public String getEncryptionSaltHex() {
        return encryptionSaltHex;
    }

    public void setEncryptionSaltHex(String encryptionSaltHex) {
        this.encryptionSaltHex = encryptionSaltHex;
    }
}

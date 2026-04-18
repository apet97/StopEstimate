package com.devodox.stopatestimate.store;

import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.WebhookCredential;
import com.devodox.stopatestimate.model.entity.InstallationEntity;
import com.devodox.stopatestimate.model.entity.WebhookRegistrationEntity;
import com.devodox.stopatestimate.repository.InstallationRepository;
import com.devodox.stopatestimate.repository.WebhookRegistrationRepository;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Installation persistence backed by PostgreSQL via JPA. Installation tokens and webhook tokens are
 * encrypted at rest through {@link TextEncryptor}.
 */
@Component
public class InstallationStore {

    private final InstallationRepository installationRepository;
    private final WebhookRegistrationRepository webhookRepository;
    private final TextEncryptor textEncryptor;

    public InstallationStore(
            InstallationRepository installationRepository,
            WebhookRegistrationRepository webhookRepository,
            TextEncryptor textEncryptor) {
        this.installationRepository = installationRepository;
        this.webhookRepository = webhookRepository;
        this.textEncryptor = textEncryptor;
    }

    @Transactional
    public void save(InstallationRecord installation) {
        InstallationEntity entity = installationRepository.findById(installation.workspaceId())
                .orElseGet(InstallationEntity::new);
        entity.setWorkspaceId(installation.workspaceId());
        entity.setAddonId(installation.addonId());
        entity.setAddonUserId(installation.addonUserId());
        entity.setOwnerUserId(installation.ownerUserId());
        entity.setInstallationTokenEnc(encryptOrNull(installation.installationToken()));
        entity.setBackendUrl(installation.backendUrl());
        entity.setReportsUrl(installation.reportsUrl());
        entity.setStatus(installation.status() == null ? AddonStatus.ACTIVE.name() : installation.status().name());
        entity.setEnabled(installation.enabled());
        entity.setDefaultResetCadence(installation.defaultResetCadenceValue());
        entity.setInstalledAt(installation.installedAt());
        entity.setUpdatedAt(installation.updatedAt());
        installationRepository.save(entity);

        webhookRepository.deleteAllByWorkspaceId(installation.workspaceId());
        List<WebhookRegistrationEntity> rows = new ArrayList<>();
        Map<String, WebhookCredential> tokens = installation.webhookAuthTokens() == null
                ? Map.of()
                : installation.webhookAuthTokens();
        for (Map.Entry<String, WebhookCredential> entry : tokens.entrySet()) {
            WebhookCredential cred = entry.getValue();
            if (entry.getKey() == null || cred == null || cred.authToken() == null) {
                continue;
            }
            WebhookRegistrationEntity row = new WebhookRegistrationEntity();
            row.setWorkspaceId(installation.workspaceId());
            row.setRoutePath(entry.getKey());
            row.setEventType(cred.eventType());
            row.setWebhookTokenEnc(textEncryptor.encrypt(cred.authToken()));
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            webhookRepository.saveAll(rows);
        }
    }

    @Transactional(readOnly = true)
    public Optional<InstallationRecord> findByWorkspaceId(String workspaceId) {
        return installationRepository.findById(workspaceId).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public List<InstallationRecord> findAllRecords() {
        List<InstallationRecord> result = new ArrayList<>();
        for (InstallationEntity entity : installationRepository.findAll()) {
            result.add(toRecord(entity));
        }
        return result;
    }

    @Transactional
    public void deleteByWorkspaceId(String workspaceId) {
        webhookRepository.deleteAllByWorkspaceId(workspaceId);
        installationRepository.deleteById(workspaceId);
    }

    @Transactional
    public void deleteAllRecords() {
        webhookRepository.deleteAll();
        installationRepository.deleteAll();
    }

    private InstallationRecord toRecord(InstallationEntity entity) {
        Map<String, WebhookCredential> tokens = new LinkedHashMap<>();
        for (WebhookRegistrationEntity wh : webhookRepository.findAllByWorkspaceId(entity.getWorkspaceId())) {
            tokens.put(wh.getRoutePath(), new WebhookCredential(
                    wh.getEventType(),
                    textEncryptor.decrypt(wh.getWebhookTokenEnc())));
        }
        AddonStatus status;
        try {
            status = AddonStatus.valueOf(entity.getStatus());
        } catch (Exception e) {
            status = AddonStatus.ACTIVE;
        }
        Instant installedAt = entity.getInstalledAt() == null ? Instant.now() : entity.getInstalledAt();
        Instant updatedAt = entity.getUpdatedAt() == null ? installedAt : entity.getUpdatedAt();
        String tokenPlain = entity.getInstallationTokenEnc() == null
                ? null
                : textEncryptor.decrypt(entity.getInstallationTokenEnc());
        return new InstallationRecord(
                entity.getWorkspaceId(),
                entity.getAddonId(),
                entity.getAddonUserId(),
                entity.getOwnerUserId(),
                tokenPlain,
                entity.getBackendUrl(),
                entity.getReportsUrl(),
                tokens,
                status,
                entity.isEnabled(),
                "ENFORCE",
                entity.getDefaultResetCadence() == null ? "NONE" : entity.getDefaultResetCadence(),
                installedAt,
                updatedAt);
    }

    private String encryptOrNull(String plain) {
        return plain == null || plain.isBlank() ? null : textEncryptor.encrypt(plain);
    }
}

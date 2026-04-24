package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.GuardEventType;
import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.entity.GuardEventEntity;
import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Façade over {@link GuardEventRepository} that owns the audit-trail concerns
 * {@link EstimateGuardService} previously had inline. Single point of:
 * <ul>
 *     <li>row construction from {@code (workspaceId, projectId, type, reason, source, payload)}</li>
 *     <li>payload fingerprinting (SHA-256 prefix, 16 hex chars; 64 bits is plenty for
 *     audit-trail dedup)</li>
 *     <li>nullable-{@link GuardReason} normalisation to its serialised name.</li>
 * </ul>
 *
 * Pulled out per plan item C1 to shrink {@code EstimateGuardService}'s surface and make the
 * audit-trail behaviour testable in isolation.
 */
@Service
public class GuardEventRecorder {

    private static final String SCHEDULER_FINGERPRINT = "scheduler";

    private final GuardEventRepository guardEventRepository;

    public GuardEventRecorder(GuardEventRepository guardEventRepository) {
        this.guardEventRepository = guardEventRepository;
    }

    public void record(
            String workspaceId,
            String projectId,
            GuardEventType type,
            GuardReason reason,
            String source,
            JsonObject payload) {
        GuardEventEntity row = new GuardEventEntity();
        row.setWorkspaceId(workspaceId);
        row.setProjectId(projectId);
        row.setEventType(type.name());
        row.setGuardReason(reason == null ? null : reason.name());
        row.setSource(source);
        row.setPayloadFingerprint(fingerprint(payload));
        guardEventRepository.save(row);
    }

    static String fingerprint(JsonObject payload) {
        if (payload == null) {
            return SCHEDULER_FINGERPRINT;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            // 16 hex chars = 64 bits of collision resistance; plenty for audit-trail dedup.
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return SCHEDULER_FINGERPRINT;
        }
    }
}

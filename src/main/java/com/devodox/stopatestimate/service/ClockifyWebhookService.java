package com.devodox.stopatestimate.service;

import com.cake.clockify.addonsdk.clockify.ClockifySignatureParser;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.WebhookCredential;
import com.devodox.stopatestimate.model.entity.WebhookEventEntity;
import com.devodox.stopatestimate.repository.WebhookEventRepository;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Service
public class ClockifyWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ClockifyWebhookService.class);

    private final TokenVerificationService tokenVerificationService;
    private final ClockifyLifecycleService lifecycleService;
    private final ClockifyCutoffService cutoffService;
    private final WebhookEventRepository webhookEventRepository;
    private final Gson gson = new Gson();

    public ClockifyWebhookService(
            TokenVerificationService tokenVerificationService,
            ClockifyLifecycleService lifecycleService,
            ClockifyCutoffService cutoffService,
            WebhookEventRepository webhookEventRepository) {
        this.tokenVerificationService = tokenVerificationService;
        this.lifecycleService = lifecycleService;
        this.cutoffService = cutoffService;
        this.webhookEventRepository = webhookEventRepository;
    }

    public void handleWebhook(
            String expectedEventType,
            String routePath,
            String rawBody,
            String signature,
            String eventTypeHeader) {
        if (signature == null || signature.isBlank()) {
            throw new ClockifyRequestAuthException("Missing Clockify-Signature header");
        }
        if (eventTypeHeader == null || eventTypeHeader.isBlank()) {
            throw new ClockifyRequestAuthException("Missing clockify-webhook-event-type header");
        }
        if (!expectedEventType.equals(eventTypeHeader)) {
            throw new IllegalArgumentException("Unexpected webhook type for route");
        }

        Map<String, Object> claims;
        try {
            claims = tokenVerificationService.verifyAndParseClaims(signature);
        } catch (InvalidAddonTokenException e) {
            throw new ClockifyRequestAuthException("Invalid webhook signature", e);
        }
        String workspaceId = claimString(claims, ClockifySignatureParser.CLAIM_WORKSPACE_ID)
                .orElseThrow(() -> new ClockifyRequestAuthException("Webhook signature missing workspaceId"));
        String addonId = claimString(claims, ClockifySignatureParser.CLAIM_ADDON_ID)
                .orElseThrow(() -> new ClockifyRequestAuthException("Webhook signature missing addonId"));

        InstallationRecord installation = lifecycleService.findInstallation(workspaceId).orElse(null);
        if (installation == null || !installation.active()) {
            return;
        }
        if (!installation.addonId().equals(addonId)) {
            throw new ClockifyAccessForbiddenException("Webhook addonId mismatch");
        }
        verifyStoredWebhookToken(installation, routePath, signature);

        JsonObject payload = parsePayload(rawBody);
        ClockifyJson.findFirstString(payload, "workspaceId")
                .filter(payloadWorkspaceId -> !workspaceId.equals(payloadWorkspaceId))
                .ifPresent(value -> {
                    throw new ClockifyRequestAuthException("Webhook payload workspace mismatch");
                });

        if (isDuplicate(expectedEventType, payload, rawBody, signature, workspaceId)) {
            return;
        }

        String projectId = extractProjectId(expectedEventType, payload).orElse(null);
        if (projectId != null) {
            cutoffService.reconcileProject(workspaceId, projectId, "webhook:" + expectedEventType, payload);
        } else {
            cutoffService.reconcileKnownProjects(workspaceId, "webhook:" + expectedEventType);
        }
    }

    /**
     * Clockify retries deliveries on transient failures. A row in {@code webhook_events} keyed
     * by (event-id, signature hash) short-circuits the second+ attempts before any side effect.
     * Falls back to a body hash when the payload doesn't carry a stable event identifier.
     */
    @Transactional
    protected boolean isDuplicate(
            String eventType,
            JsonObject payload,
            String rawBody,
            String signature,
            String workspaceId) {
        String signatureHash = sha256Hex(signature);
        String eventId = extractEventId(eventType, payload).orElse("body:" + sha256Hex(rawBody == null ? "" : rawBody));
        // DB-07: go straight to insert. The existsBy pre-check is a read-then-write race — two
        // concurrent deliveries both pass the existence check, only one commits, the other causes
        // a spurious transaction rollback. The (event_id, signature_hash) PK makes insert-or-fail
        // race-proof: on duplicate we catch and return true.
        try {
            webhookEventRepository.save(new WebhookEventEntity(eventId, signatureHash, workspaceId));
            return false;
        } catch (DataIntegrityViolationException race) {
            log.debug("Webhook dedup hit event={} sigHash={}", eventId, prefix(signatureHash));
            return true;
        }
    }

    private Optional<String> extractEventId(String eventType, JsonObject payload) {
        Optional<String> explicit = ClockifyJson.findFirstString(payload, "eventId");
        if (explicit.isPresent()) {
            return explicit.map(id -> eventType + ":" + id);
        }
        Optional<String> entryId = ClockifyJson.findFirstString(payload, "id", "timeEntryId");
        return entryId.map(id -> eventType + ":" + id);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String prefix(String value) {
        return value == null || value.length() < 12 ? value : value.substring(0, 12);
    }

    private void verifyStoredWebhookToken(InstallationRecord installation, String routePath, String signature) {
        String normalizedPath = ClockifyJson.normalizeWebhookPath(routePath);
        WebhookCredential stored = installation.webhookAuthTokens().get(normalizedPath);
        String expected = stored == null ? null : stored.authToken();
        if (expected == null || !constantTimeEquals(expected, signature)) {
            // Require per-route token equality: a valid token for one registered route
            // must not authenticate a different route. Constant-time compare prevents
            // length/prefix-based timing leaks on the stored token.
            throw new ClockifyAccessForbiddenException("Webhook auth token mismatch");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    private JsonObject parsePayload(String rawBody) {
        JsonElement element = gson.fromJson(rawBody, JsonElement.class);
        if (element == null || !element.isJsonObject()) {
            return new JsonObject();
        }
        return element.getAsJsonObject();
    }

    private Optional<String> extractProjectId(String eventType, JsonObject payload) {
        Optional<String> projectId = ClockifyJson.findFirstString(payload, "projectId");
        if (projectId.isPresent()) {
            return projectId;
        }
        if ("PROJECT_UPDATED".equals(eventType)) {
            return ClockifyJson.findFirstString(payload, "id");
        }
        return Optional.empty();
    }

    private Optional<String> claimString(Map<String, Object> claims, String claimName) {
        Object value = claims.get(claimName);
        return value instanceof String string && !string.isBlank() ? Optional.of(string) : Optional.empty();
    }
}

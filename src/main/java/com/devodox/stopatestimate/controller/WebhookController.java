package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.api.ClockifyAccessForbiddenException;
import com.devodox.stopatestimate.api.ClockifyApiException;
import com.devodox.stopatestimate.api.ClockifyRequestAuthException;
import com.devodox.stopatestimate.service.ClockifyWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for every webhook route. Five of the ten routes here are declared in the manifest
 * under schema 1.3 (timer-started/stopped and time-entry created/updated/deleted). The remaining
 * five — {@code /webhook/project-updated} and the four {@code /webhook/expense-*} routes — are
 * <em>not</em> declarable under schema 1.3, so they will never receive traffic from Clockify
 * today. They are retained intentionally as hibernated plumbing: a future schema upgrade (or a
 * manual runtime registration via {@code POST /v1/workspaces/{id}/webhooks}) can enable them
 * without re-introducing the controller wiring. See SPEC.md §1 for the authoritative rationale.
 */
@RestController
@RequestMapping(path = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ClockifyWebhookService webhookService;

    public WebhookController(ClockifyWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/new-timer-started")
    public ResponseEntity<Void> newTimerStarted(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("NEW_TIMER_STARTED", "/webhook/new-timer-started", body, signature, eventType);
    }

    @PostMapping("/timer-stopped")
    public ResponseEntity<Void> timerStopped(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("TIMER_STOPPED", "/webhook/timer-stopped", body, signature, eventType);
    }

    @PostMapping("/new-time-entry")
    public ResponseEntity<Void> newTimeEntry(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("NEW_TIME_ENTRY", "/webhook/new-time-entry", body, signature, eventType);
    }

    @PostMapping("/time-entry-updated")
    public ResponseEntity<Void> timeEntryUpdated(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("TIME_ENTRY_UPDATED", "/webhook/time-entry-updated", body, signature, eventType);
    }

    @PostMapping("/time-entry-deleted")
    public ResponseEntity<Void> timeEntryDeleted(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("TIME_ENTRY_DELETED", "/webhook/time-entry-deleted", body, signature, eventType);
    }

    @PostMapping("/project-updated")
    public ResponseEntity<Void> projectUpdated(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("PROJECT_UPDATED", "/webhook/project-updated", body, signature, eventType);
    }

    @PostMapping("/expense-created")
    public ResponseEntity<Void> expenseCreated(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("EXPENSE_CREATED", "/webhook/expense-created", body, signature, eventType);
    }

    @PostMapping("/expense-updated")
    public ResponseEntity<Void> expenseUpdated(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("EXPENSE_UPDATED", "/webhook/expense-updated", body, signature, eventType);
    }

    @PostMapping("/expense-deleted")
    public ResponseEntity<Void> expenseDeleted(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("EXPENSE_DELETED", "/webhook/expense-deleted", body, signature, eventType);
    }

    @PostMapping("/expense-restored")
    public ResponseEntity<Void> expenseRestored(
            @RequestBody String body,
            @RequestHeader(value = "Clockify-Signature", required = true) String signature,
            @RequestHeader(value = "clockify-webhook-event-type", required = false) String eventType) {
        return handleWebhook("EXPENSE_RESTORED", "/webhook/expense-restored", body, signature, eventType);
    }

    private ResponseEntity<Void> handleWebhook(
            String expectedEventType,
            String routePath,
            String body,
            String signature,
            String eventType) {
        try {
            webhookService.handleWebhook(expectedEventType, routePath, body, signature, eventType);
        } catch (ClockifyRequestAuthException | ClockifyAccessForbiddenException e) {
            // After the A3 reparent, ClockifyApiException catches auth/forbidden too. Re-throw
            // those so GlobalExceptionHandler maps a bad signature / forged request to 401/403
            // — silently 200-acking an unauthenticated webhook would mask both real bugs and
            // attacks. Only the generic-ClockifyApiException case below is absorbed for the
            // scheduler to reconcile.
            throw e;
        } catch (ClockifyApiException e) {
            // Clockify retries forever on 5xx; scheduler reconcile will catch up, so 200-ack.
            log.warn("Clockify API call failed during {} handling — scheduler will reconcile", expectedEventType, e);
        }
        return ResponseEntity.ok().build();
    }
}

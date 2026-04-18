package com.devodox.stopatestimate.web;

import com.devodox.stopatestimate.api.ClockifyApiException;
import com.devodox.stopatestimate.service.ClockifyAccessForbiddenException;
import com.devodox.stopatestimate.service.ClockifyRequestAuthException;
import com.devodox.stopatestimate.service.InvalidAddonTokenException;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised sanitised-envelope mapping for every route. Controllers no longer wrap calls in
 * try/catch — any of these exceptions bubble up and land here. Responses never include stack
 * traces or Spring's default error schema (see {@code server.error.include-*} in application.yml).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidAddonTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidToken(InvalidAddonTokenException e) {
        return respond(HttpStatus.UNAUTHORIZED, "invalid_addon_token", e.getMessage());
    }

    @ExceptionHandler(ClockifyRequestAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(ClockifyRequestAuthException e) {
        return respond(HttpStatus.UNAUTHORIZED, "invalid_request_token", e.getMessage());
    }

    @ExceptionHandler(ClockifyAccessForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ClockifyAccessForbiddenException e) {
        return respond(HttpStatus.FORBIDDEN, "webhook_token_mismatch", e.getMessage());
    }

    @ExceptionHandler(ClockifyApiException.class)
    public ResponseEntity<Map<String, Object>> handleClockifyApi(ClockifyApiException e) {
        log.warn("Clockify API call failed: {}", e.getMessage(), e);
        return respond(HttpStatus.BAD_GATEWAY, "clockify_api_error", "Clockify request failed");
    }

    @ExceptionHandler({JsonSyntaxException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadJson(Exception e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_payload", "Malformed JSON payload");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        // Typically an idempotent install race; return 200 so the caller (Clockify) stops retrying.
        log.debug("DataIntegrityViolation suppressed (idempotent): {}", e.getMessage());
        return ResponseEntity.ok(Map.of("ok", true, "note", "already_applied"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal error");
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        if (message != null && !message.isBlank()) {
            body.put("message", message);
        }
        return ResponseEntity.status(status).body(body);
    }
}

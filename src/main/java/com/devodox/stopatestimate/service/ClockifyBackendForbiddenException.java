package com.devodox.stopatestimate.service;

/**
 * 403 from the Clockify backend API (insufficient addon permissions / scope mismatch / admin
 * downgraded the installation). Distinct from {@link ClockifyAccessForbiddenException} which
 * represents a webhook-token verification failure. Extends the base class so existing callers
 * that catch the parent still see it, but {@code GlobalExceptionHandler} maps it to a
 * different error code so operators can tell the two failure modes apart.
 */
public class ClockifyBackendForbiddenException extends ClockifyAccessForbiddenException {
    public ClockifyBackendForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.devodox.stopatestimate.api;

/**
 * Authorisation failure related to a stored Clockify credential. Today only the webhook-token
 * mismatch path raises this directly; the backend-403 case raises the
 * {@link ClockifyBackendForbiddenException} subclass so {@code GlobalExceptionHandler} can map
 * the two to distinct error codes.
 *
 * <p>Extends {@link ClockifyApiException} so callers that catch the parent see all Clockify-side
 * failures. Callers that have a fallback path keyed on transient backend errors (5xx) must catch
 * this subtype FIRST and re-throw, so the fallback only runs for {@code ClockifyApiException}
 * cases that are not authorisation-related — see {@code ProjectLockService.lockProject} and
 * {@code WebhookController.handleWebhook}.
 */
public class ClockifyAccessForbiddenException extends ClockifyApiException {
    public ClockifyAccessForbiddenException(String message) {
        super(message);
    }

    public ClockifyAccessForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}

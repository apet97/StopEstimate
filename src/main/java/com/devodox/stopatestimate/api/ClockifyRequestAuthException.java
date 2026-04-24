package com.devodox.stopatestimate.api;

/**
 * 401 from the Clockify backend or an invalid Clockify-issued token (signature verification
 * failure, missing claim, expired iat). Extends {@link ClockifyApiException} so callers that
 * catch the parent see all Clockify-side failures, but {@code GlobalExceptionHandler} maps it
 * to a distinct {@code invalid_request_token} envelope so operators can distinguish auth drift
 * from generic upstream errors.
 *
 * <p>Callers that have a fallback path keyed on transient backend errors (5xx) must catch
 * this subtype FIRST and re-throw, so the fallback only runs for
 * {@code ClockifyApiException} cases that are not auth-related — see
 * {@code ProjectLockService.lockProject} and {@code WebhookController.handleWebhook}.
 */
public class ClockifyRequestAuthException extends ClockifyApiException {
    public ClockifyRequestAuthException(String message) {
        super(message);
    }

    public ClockifyRequestAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}

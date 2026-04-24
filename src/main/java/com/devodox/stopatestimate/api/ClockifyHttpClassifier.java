package com.devodox.stopatestimate.api;

import org.springframework.web.client.RestClientResponseException;

/**
 * Maps a Clockify HTTP error response to the right {@link ClockifyApiException} subtype so
 * callers can keep consistent {@code catch} + {@code GlobalExceptionHandler} semantics.
 *
 * <p>Both {@link ClockifyBackendApiClient} and {@link ClockifyReportsApiClient} route 401, 403,
 * 429, and other status codes to the same exception types — this utility is the single source
 * of truth for that mapping. {@code sourceLabel} is interpolated into the message so operators
 * can tell which surface failed without digging into the cause chain.
 *
 * <p>The contract locked by {@code ClockifyBackendApiClientTest} and
 * {@code ClockifyReportsApiClientTest} is:
 * <ul>
 *     <li>401 → {@link ClockifyRequestAuthException}</li>
 *     <li>403 → {@link ClockifyBackendForbiddenException}</li>
 *     <li>429 (after the caller's one bounded Retry-After retry) → {@link ClockifyApiException}</li>
 *     <li>any other status → {@link ClockifyApiException}</li>
 * </ul>
 */
final class ClockifyHttpClassifier {

    private ClockifyHttpClassifier() {
    }

    static RuntimeException classify(RestClientResponseException e, String sourceLabel) {
        int code = e.getStatusCode().value();
        if (code == 429) {
            // Caller attempted one bounded Retry-After-honoring retry before reaching here. A
            // second 429 means the burst is sustained; defer to the scheduler's next tick.
            return new ClockifyApiException(
                    sourceLabel + " rate limited (429) after one retry; deferring to scheduler", e);
        }
        if (code == 401) {
            return new ClockifyRequestAuthException(sourceLabel + " rejected the request token", e);
        }
        if (code == 403) {
            // RES-08: distinguish a backend permission failure (addon lost scope / admin revoked
            // access) from a webhook-token mismatch so GlobalExceptionHandler can emit a distinct
            // error code.
            return new ClockifyBackendForbiddenException(sourceLabel + " forbade the request", e);
        }
        return new ClockifyApiException(
                sourceLabel + " call failed with status " + e.getStatusCode(), e);
    }
}

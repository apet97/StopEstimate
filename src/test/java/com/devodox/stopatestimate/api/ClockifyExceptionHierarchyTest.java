package com.devodox.stopatestimate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3: locks the Clockify exception hierarchy so a future refactor can't silently re-split the
 * tree. The invariant: every Clockify-side failure type is reachable via {@code catch
 * (ClockifyApiException)}, which is what lets controllers / services have a single fallback
 * block plus a narrower re-throw for auth/forbidden.
 *
 * <p>Callers that rely on this invariant (ProjectLockService.lockProject,
 * ProjectLockService.unlockFromSnapshot, WebhookController.handleWebhook) also narrow their
 * catches to re-throw auth/forbidden before the generic fallback runs. If this test fails, those
 * catch sites need a corresponding audit.
 */
class ClockifyExceptionHierarchyTest {

    @Test
    void requestAuthExtendsClockifyApiException() {
        ClockifyRequestAuthException e = new ClockifyRequestAuthException("x");
        assertThat(e).isInstanceOf(ClockifyApiException.class);
    }

    @Test
    void accessForbiddenExtendsClockifyApiException() {
        ClockifyAccessForbiddenException e = new ClockifyAccessForbiddenException("x");
        assertThat(e).isInstanceOf(ClockifyApiException.class);
    }

    @Test
    void backendForbiddenExtendsAccessForbiddenAndClockifyApi() {
        ClockifyBackendForbiddenException e =
                new ClockifyBackendForbiddenException("x", new RuntimeException());
        assertThat(e).isInstanceOf(ClockifyAccessForbiddenException.class);
        assertThat(e).isInstanceOf(ClockifyApiException.class);
    }

    @Test
    void clockifyApiExceptionIsARuntimeException() {
        assertThat(new ClockifyApiException("x")).isInstanceOf(RuntimeException.class);
    }
}

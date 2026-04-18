package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyApiException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for the install-time backoff. Uses tiny delays (1ms) so the suite stays fast. The
 * behavior under real-world 2s/5s/10s delays is identical — only the sleep value changes.
 */
class InstallReconcileRetrierTest {

    @Test
    void retriesOnFailureThenSucceeds() {
        ClockifyCutoffService cutoffService = Mockito.mock(ClockifyCutoffService.class);
        // Fail twice (simulating Clockify 401 during token activation), then succeed on 3rd try.
        doThrow(new ClockifyApiException("401"))
                .doThrow(new ClockifyApiException("401"))
                .doNothing()
                .when(cutoffService).reconcileKnownProjects(eq("ws-1"), anyString());

        new InstallReconcileRetrier(cutoffService, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-1", "lifecycle:installed");

        verify(cutoffService, times(3)).reconcileKnownProjects(eq("ws-1"), anyString());
    }

    @Test
    void stopsAfterMaxAttemptsWithoutSuccess() {
        ClockifyCutoffService cutoffService = Mockito.mock(ClockifyCutoffService.class);
        doThrow(new ClockifyApiException("persistent failure"))
                .when(cutoffService).reconcileKnownProjects(eq("ws-2"), anyString());

        new InstallReconcileRetrier(cutoffService, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-2", "lifecycle:installed");

        // All 3 attempts consumed; scheduler takes over beyond this — no more calls.
        verify(cutoffService, times(3)).reconcileKnownProjects(eq("ws-2"), anyString());
    }

    @Test
    void succeedsOnFirstAttemptSingleCall() {
        ClockifyCutoffService cutoffService = Mockito.mock(ClockifyCutoffService.class);
        doNothing().when(cutoffService).reconcileKnownProjects(eq("ws-3"), anyString());

        new InstallReconcileRetrier(cutoffService, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-3", "lifecycle:installed");

        verify(cutoffService, times(1)).reconcileKnownProjects(eq("ws-3"), anyString());
        verify(cutoffService, never()).reconcileKnownProjects(eq("ws-3"), eq("lifecycle:installed:retry-2"));
    }
}

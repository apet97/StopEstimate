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
 * Unit test for the install-time backoff listener. Uses tiny delays (1ms) so the suite stays
 * fast. The behavior under real-world 2s/5s/10s delays is identical — only the sleep value
 * changes.
 */
class InstallReconcileRetrierTest {

    // --- reconcileWithBackoff direct-invocation tests ---

    @Test
    void retriesOnFailureThenSucceeds() {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        // Fail twice (simulating Clockify 401 during token activation), then succeed on 3rd try.
        doThrow(new ClockifyApiException("401"))
                .doThrow(new ClockifyApiException("401"))
                .doNothing()
                .when(guard).reconcileKnownProjects(eq("ws-1"), anyString());

        new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-1", "lifecycle:installed");

        verify(guard, times(3)).reconcileKnownProjects(eq("ws-1"), anyString());
    }

    @Test
    void stopsAfterMaxAttemptsWithoutSuccess() {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        doThrow(new ClockifyApiException("persistent failure"))
                .when(guard).reconcileKnownProjects(eq("ws-2"), anyString());

        new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-2", "lifecycle:installed");

        // All 3 attempts consumed; scheduler takes over beyond this — no more calls.
        verify(guard, times(3)).reconcileKnownProjects(eq("ws-2"), anyString());
    }

    // Validates the "401 abort" fix: the post-install token-activation race surfaces as
    // ClockifyRequestAuthException and must consume the full backoff window. The prior
    // behavior aborted on the first 401, leaving the sidebar blank for 60s until the next
    // scheduler tick.
    @Test
    void persistentAuthFailureConsumesFullBackoffWindow() {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        doThrow(new ClockifyRequestAuthException("persistent 401"))
                .when(guard).reconcileKnownProjects(eq("ws-auth-persist"), anyString());

        new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-auth-persist", "lifecycle:installed");

        verify(guard, times(3)).reconcileKnownProjects(eq("ws-auth-persist"), anyString());
    }

    @Test
    void transientAuthFailureThenSuccess() {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        doThrow(new ClockifyRequestAuthException("transient 401"))
                .doNothing()
                .when(guard).reconcileKnownProjects(eq("ws-auth-transient"), anyString());

        new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L})
                .reconcileWithBackoff("ws-auth-transient", "lifecycle:installed");

        // 2 attempts total: retry 1 (fails auth), retry 2 (succeeds).
        verify(guard, times(2)).reconcileKnownProjects(eq("ws-auth-transient"), anyString());
    }

    // TEST-13: Thread.interrupt() during backoff must set the interrupt flag and return without
    // ever invoking reconcileKnownProjects. Using Long.MAX_VALUE as the delay guarantees the sleep
    // is still pending when the interrupt arrives.
    @Test
    void interruptDuringBackoffReturnsWithoutReconcileCall() throws InterruptedException {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        InstallReconcileRetrier retrier = new InstallReconcileRetrier(
                guard, new long[]{Long.MAX_VALUE});

        final boolean[] interruptFlagSeen = {false};
        Thread worker = new Thread(() -> {
            retrier.reconcileWithBackoff("ws-interrupt", "lifecycle:installed");
            // Inside the worker, after reconcileWithBackoff returns: the code path restores the
            // interrupt flag via Thread.currentThread().interrupt() before returning.
            interruptFlagSeen[0] = Thread.currentThread().isInterrupted();
        });
        worker.start();
        // Tiny sleep to guarantee the worker has entered Thread.sleep before we interrupt.
        Thread.sleep(50);
        worker.interrupt();
        worker.join(2_000);

        org.junit.jupiter.api.Assertions.assertFalse(worker.isAlive(),
                "Worker must exit reconcileWithBackoff after interrupt — no retry loop progression");
        verify(guard, never()).reconcileKnownProjects(anyString(), anyString());
        org.junit.jupiter.api.Assertions.assertTrue(interruptFlagSeen[0],
                "reconcileWithBackoff must preserve the interrupt flag (Thread.currentThread().interrupt())");
    }

    // --- onLifecycleReconcileRequested contract tests ---

    @Test
    void listenerInstallSuccessOnFirstBackoffAttemptMakesOneCall() {
        // useBackoffOnFailure=true routes through reconcileWithBackoff. The first attempt (at the
        // configured delay) succeeds → no further retries, no redundant Clockify calls.
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        doNothing().when(guard).reconcileKnownProjects(eq("ws-3"), anyString());
        InstallReconcileRetrier retrier = new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L});

        retrier.onLifecycleReconcileRequested(
                new LifecycleReconcileRequestedEvent("ws-3", "lifecycle:installed", true));

        verify(guard, times(1)).reconcileKnownProjects(eq("ws-3"), anyString());
        verify(guard).reconcileKnownProjects(eq("ws-3"), eq("lifecycle:installed:retry-1"));
        verify(guard, never()).reconcileKnownProjects(eq("ws-3"), eq("lifecycle:installed:retry-2"));
        verify(guard, never()).reconcileKnownProjects(eq("ws-3"), eq("lifecycle:installed:retry-3"));
    }

    @Test
    void listenerInstallFailurePersistsAcrossAllBackoffAttempts() {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        // Fail retry-1, fail retry-2, succeed on retry-3.
        doThrow(new ClockifyApiException("retry-1 failed"))
                .when(guard).reconcileKnownProjects(eq("ws-4"), eq("lifecycle:installed:retry-1"));
        doThrow(new ClockifyApiException("retry-2 failed"))
                .when(guard).reconcileKnownProjects(eq("ws-4"), eq("lifecycle:installed:retry-2"));
        doNothing().when(guard).reconcileKnownProjects(eq("ws-4"), eq("lifecycle:installed:retry-3"));

        InstallReconcileRetrier retrier = new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L});

        retrier.onLifecycleReconcileRequested(
                new LifecycleReconcileRequestedEvent("ws-4", "lifecycle:installed", true));

        verify(guard).reconcileKnownProjects(eq("ws-4"), eq("lifecycle:installed:retry-1"));
        verify(guard).reconcileKnownProjects(eq("ws-4"), eq("lifecycle:installed:retry-2"));
        verify(guard).reconcileKnownProjects(eq("ws-4"), eq("lifecycle:installed:retry-3"));
    }

    @Test
    void listenerStatusChangedFailureReturnsWithoutRetry() {
        EstimateGuardService guard = Mockito.mock(EstimateGuardService.class);
        doThrow(new ClockifyApiException("status-changed reconcile failed"))
                .when(guard).reconcileKnownProjects(eq("ws-5"), eq("lifecycle:status-changed"));

        InstallReconcileRetrier retrier = new InstallReconcileRetrier(guard, new long[]{1L, 1L, 1L});

        retrier.onLifecycleReconcileRequested(
                new LifecycleReconcileRequestedEvent("ws-5", "lifecycle:status-changed", false));

        // Exactly one call (the sync attempt). No retry loop because useBackoffOnFailure=false.
        verify(guard, times(1)).reconcileKnownProjects(eq("ws-5"), anyString());
    }
}

package com.devodox.stopatestimate.service;

/**
 * Fired by {@link ClockifyLifecycleService} after an install / status-change / settings-update
 * commits, requesting that the reconcile loop run for the affected workspace. Decouples the
 * lifecycle writer from the reconcile caller so the two no longer form a Spring bean cycle.
 *
 * <p>{@code useBackoffOnFailure} is {@code true} only for fresh installs, where Clockify's API
 * gateway may not yet have activated the installation token. For status/settings updates the
 * token is already known-good; we try reconcile once and defer to the scheduler if it fails.
 */
public record LifecycleReconcileRequestedEvent(
        String workspaceId,
        String source,
        boolean useBackoffOnFailure) {
}

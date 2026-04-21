package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyRequestAuthException;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.store.InstallationStore;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;

/**
 * Listens for {@link LifecycleReconcileRequestedEvent} published after a lifecycle transaction
 * commits, runs one reconcile attempt, and — when the event asks for it — retries on short
 * delays if the first attempt throws.
 *
 * <p>The retry window exists because Clockify's API gateway may not have activated a freshly
 * minted installation token by the time the INSTALLED lifecycle callback completes. Without
 * the retries, the next successful reconcile is only guaranteed after the 60s scheduler tick,
 * which is a long time to stare at a blank sidebar.
 */
@Component
public class InstallReconcileRetrier {

    private static final Logger log = LoggerFactory.getLogger(InstallReconcileRetrier.class);
    // 2s, 5s, 10s — total worst-case ~17s, comfortably inside the 60s scheduler interval so the
    // retries cannot race the scheduler's first tick.
    private static final long[] DEFAULT_BACKOFF_MS = {2_000L, 5_000L, 10_000L};

    private final EstimateGuardService estimateGuardService;
    private final InstallationStore installationStore;
    private final ClockifyBackendApiClient backendApiClient;
    private final Clock clock;
    private final long[] backoffMs;

    @Autowired
    public InstallReconcileRetrier(
            EstimateGuardService estimateGuardService,
            InstallationStore installationStore,
            ClockifyBackendApiClient backendApiClient,
            Clock clock) {
        this(estimateGuardService, installationStore, backendApiClient, clock, DEFAULT_BACKOFF_MS);
    }

    // Package-private for tests that need short delays so the suite stays fast.
    InstallReconcileRetrier(
            EstimateGuardService estimateGuardService,
            InstallationStore installationStore,
            ClockifyBackendApiClient backendApiClient,
            Clock clock,
            long[] backoffMs) {
        this.estimateGuardService = estimateGuardService;
        this.installationStore = installationStore;
        this.backendApiClient = backendApiClient;
        this.clock = clock;
        this.backoffMs = backoffMs;
    }

    /**
     * Fires after the lifecycle @Transactional method commits. For install-style events it runs
     * the full backoff schedule (break on first success — the common case finishes after the
     * 2s attempt). For status/settings updates, runs a single attempt and defers failures to
     * the scheduler so the HTTP handler returns 200 quickly.
     */
    @Async("lifecycleReconcileExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleReconcileRequested(LifecycleReconcileRequestedEvent event) {
        String workspaceId = event.workspaceId();
        String source = event.source();
        if (event.useBackoffOnFailure()) {
            reconcileWithBackoff(workspaceId, source);
            return;
        }
        try {
            estimateGuardService.reconcileKnownProjects(workspaceId, source);
            populateTimezoneIfMissing(workspaceId);
            log.debug("Post-lifecycle reconcile succeeded for {} ({})", workspaceId, source);
        } catch (RuntimeException e) {
            log.warn("Post-lifecycle reconcile failed for {} ({}); deferring to scheduler tick",
                    workspaceId, source, e);
        }
    }

    /**
     * Retries {@code reconcileKnownProjects} with the configured backoff schedule. Visible for
     * direct invocation from tests so the retry loop can be exercised without publishing an
     * event.
     *
     * <p>Auth failures ({@link ClockifyRequestAuthException}) consume the full backoff window
     * because the post-install token-activation race is the whole reason this retrier exists.
     * A persistent 401 surfaces on the last attempt's WARN log; the 60s scheduler tick is the
     * final backstop for the "token truly dead" case.
     */
    @Async("lifecycleReconcileExecutor")
    public void reconcileWithBackoff(String workspaceId, String source) {
        for (int i = 0; i < backoffMs.length; i++) {
            try {
                Thread.sleep(backoffMs[i]);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                estimateGuardService.reconcileKnownProjects(workspaceId, source + ":retry-" + (i + 1));
                populateTimezoneIfMissing(workspaceId);
                log.debug("Backoff reconcile succeeded on attempt {} for {}", i + 1, source);
                return;
            } catch (RuntimeException e) {
                if (i == backoffMs.length - 1) {
                    log.warn("Backoff reconcile attempt {} for {} failed; handing off to scheduler",
                            i + 1, source, e);
                    return;
                }
                log.debug("Backoff reconcile attempt {} for {} failed, retrying", i + 1, source, e);
            }
        }
    }

    /**
     * Best-effort workspace-timezone fetch + persist. No-op if timezone is already set or the
     * installation is gone. Fails quietly with a WARN — callers retry on next reconcile.
     *
     * <p>Public so {@code CutoffJobScheduler.tick} can retry per-workspace on every 60s tick
     * when the post-install lifecycle retry window (2s/5s/10s) exhausts before Clockify's API
     * gateway activates the installation token. Without scheduler-driven retry a persistent
     * activation race would leave {@code timezone} null forever (report filters silently
     * fall back to UTC, misaligning resets on non-UTC workspaces).
     */
    public void populateTimezoneIfMissing(String workspaceId) {
        InstallationRecord installation = installationStore.findByWorkspaceId(workspaceId).orElse(null);
        if (installation == null || installation.timezone() != null) {
            return;
        }
        try {
            String timezone = extractInstallerTimezone(installation);
            if (timezone == null) {
                return;
            }
            installationStore.save(installation.withTimezone(timezone).withUpdatedAt(clock.instant()));
            log.info("Populated timezone={} for workspace={}", timezone, workspaceId);
        } catch (RuntimeException e) {
            log.warn("Timezone fetch failed for {}; leaving null (will retry on next reconcile)", workspaceId, e);
        }
    }

    /**
     * Clockify's {@code GET /v1/workspaces/{id}} response does not expose an IANA timezone
     * (observed Apr 2026 — the workspace entity has no {@code timeZone} field and
     * {@code workspaceSettings.lockTimeZone} is null for every workspace we checked). The
     * most reliable proxy is the authenticated principal's {@code settings.timeZone} —
     * with the installation token, that's the addon user whose profile mirrors the
     * installer's workspace-local clock. Falls back to the workspace payload shape in case
     * Clockify re-exposes a workspace-level timezone field in the future.
     */
    private String extractInstallerTimezone(InstallationRecord installation) {
        JsonObject user = backendApiClient.getCurrentUser(installation);
        if (user != null) {
            JsonObject settings = ClockifyJson.object(user, "settings");
            String userTz = ClockifyJson.string(settings, "timeZone").orElse(null);
            if (userTz != null) {
                return userTz;
            }
        }
        JsonObject workspace = backendApiClient.getWorkspace(installation);
        if (workspace == null) {
            return null;
        }
        String top = ClockifyJson.string(workspace, "timeZone").orElse(null);
        if (top != null) {
            return top;
        }
        JsonObject ws = ClockifyJson.object(workspace, "workspaceSettings");
        if (ws == null) {
            return null;
        }
        String nested = ClockifyJson.string(ws, "timeZone").orElse(null);
        if (nested != null) {
            return nested;
        }
        return ClockifyJson.string(ws, "lockTimeZone").orElse(null);
    }
}

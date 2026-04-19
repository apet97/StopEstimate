package com.devodox.stopatestimate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private final long[] backoffMs;

    @Autowired
    public InstallReconcileRetrier(EstimateGuardService estimateGuardService) {
        this(estimateGuardService, DEFAULT_BACKOFF_MS);
    }

    // Package-private for tests that need short delays so the suite stays fast.
    InstallReconcileRetrier(EstimateGuardService estimateGuardService, long[] backoffMs) {
        this.estimateGuardService = estimateGuardService;
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
}

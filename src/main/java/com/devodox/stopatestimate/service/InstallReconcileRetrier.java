package com.devodox.stopatestimate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Retries the first post-install reconcile a few times on short delays. Clockify's API gateway may
 * not have activated a freshly-minted installation token by the time we receive the INSTALLED
 * lifecycle callback, so the first outbound call often 401s. Without this the next successful
 * reconcile is only guaranteed after the 60s scheduler tick, which is a long time to stare at a
 * blank sidebar. The method is {@code @Async} so the HTTP handler returns 200 immediately.
 */
@Component
public class InstallReconcileRetrier {

    private static final Logger log = LoggerFactory.getLogger(InstallReconcileRetrier.class);
    // 2s, 5s, 10s — total worst-case ~17s, comfortably inside the 60s scheduler interval so the
    // retries cannot race the scheduler's first tick.
    private static final long[] DEFAULT_BACKOFF_MS = {2_000L, 5_000L, 10_000L};

    private final ClockifyCutoffService cutoffService;
    private final long[] backoffMs;

    @Autowired
    public InstallReconcileRetrier(@Lazy ClockifyCutoffService cutoffService) {
        // @Lazy breaks the circular dependency:
        // ClockifyLifecycleService → InstallReconcileRetrier → ClockifyCutoffService
        //                            → EstimateGuardService → ClockifyLifecycleService.
        this(cutoffService, DEFAULT_BACKOFF_MS);
    }

    // Package-private for tests that need short delays so the suite stays fast.
    InstallReconcileRetrier(ClockifyCutoffService cutoffService, long[] backoffMs) {
        this.cutoffService = cutoffService;
        this.backoffMs = backoffMs;
    }

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
                cutoffService.reconcileKnownProjects(workspaceId, source + ":retry-" + (i + 1));
                log.debug("Backoff reconcile succeeded on attempt {} for {}", i + 1, source);
                return;
            } catch (ClockifyRequestAuthException authError) {
                // RES-04: auth failures are not transient. The post-install token activation race
                // surfaces as 401 on the *first* attempt and clears on retry, but a persistent 401
                // means the token is dead and retrying just burns 17s before the scheduler tick
                // can surface the error upstream.
                log.warn("Auth failure on reconcile attempt {} for {}; handing off to scheduler",
                        i + 1, source, authError);
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

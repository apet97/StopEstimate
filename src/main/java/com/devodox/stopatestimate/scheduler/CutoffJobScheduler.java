package com.devodox.stopatestimate.scheduler;

import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.devodox.stopatestimate.repository.WebhookEventRepository;
import com.devodox.stopatestimate.service.EstimateGuardService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Component
public class CutoffJobScheduler {
    private static final Logger log = LoggerFactory.getLogger(CutoffJobScheduler.class);
    private static final Duration WEBHOOK_EVENT_RETENTION = Duration.ofHours(24);

    private final EstimateGuardService estimateGuardService;
    private final WebhookEventRepository webhookEventRepository;
    private final GuardEventRepository guardEventRepository;
    private final Clock clock;
    private final Duration guardEventsRetention;

    public CutoffJobScheduler(
            EstimateGuardService estimateGuardService,
            WebhookEventRepository webhookEventRepository,
            GuardEventRepository guardEventRepository,
            Clock clock,
            @Value("${clockify.guard-events.retention:P30D}") Duration guardEventsRetention) {
        this.estimateGuardService = estimateGuardService;
        this.webhookEventRepository = webhookEventRepository;
        this.guardEventRepository = guardEventRepository;
        this.clock = clock;
        this.guardEventsRetention = guardEventsRetention;
    }

    @Scheduled(fixedDelayString = "${clockify.cutoff.interval-ms:60000}")
    @SchedulerLock(name = "CutoffJobScheduler.tick", lockAtMostFor = "PT5M", lockAtLeastFor = "PT15S")
    public void tick() {
        // Split failure domains: a thrown processDueJobs must not skip reconcileAll, and
        // vice versa. Workspace-level isolation lives inside EstimateGuardService.reconcileAll.
        try {
            estimateGuardService.processDueJobs("scheduler");
        } catch (RuntimeException e) {
            log.warn("processDueJobs failed", e);
        }
        try {
            estimateGuardService.reconcileAll("scheduler");
        } catch (RuntimeException e) {
            log.warn("reconcileAll failed", e);
        }
    }

    // DB-10: wrap the delete + log in a single transaction so the lock/txn boundaries align.
    @Transactional
    @Scheduled(cron = "${clockify.cutoff.webhook-cleanup-cron:0 0 * * * *}")
    @SchedulerLock(name = "CutoffJobScheduler.cleanupWebhookEvents", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void cleanupWebhookEvents() {
        try {
            int removed = webhookEventRepository.deleteAllOlderThan(clock.instant().minus(WEBHOOK_EVENT_RETENTION));
            if (removed > 0) {
                log.info("Purged {} webhook_events older than {}", removed, WEBHOOK_EVENT_RETENTION);
            }
        } catch (Exception e) {
            log.warn("webhook_events cleanup failed", e);
        }
    }

    // DB-06: guard_events grow unbounded without a retention policy. Cron runs after the
    // webhook cleanup to keep both purges on a predictable nightly schedule.
    @Transactional
    @Scheduled(cron = "${clockify.guard-events.purge-cron:0 15 3 * * *}")
    @SchedulerLock(name = "CutoffJobScheduler.purgeGuardEvents", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeGuardEvents() {
        try {
            int removed = guardEventRepository.deleteAllOlderThan(clock.instant().minus(guardEventsRetention));
            if (removed > 0) {
                log.info("Purged {} guard_events older than {}", removed, guardEventsRetention);
            }
        } catch (Exception e) {
            log.warn("guard_events purge failed", e);
        }
    }
}

package com.devodox.stopatestimate.scheduler;

import com.devodox.stopatestimate.repository.WebhookEventRepository;
import com.devodox.stopatestimate.service.ClockifyCutoffService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class CutoffJobScheduler {
    private static final Logger log = LoggerFactory.getLogger(CutoffJobScheduler.class);
    private static final Duration WEBHOOK_EVENT_RETENTION = Duration.ofHours(24);

    private final ClockifyCutoffService cutoffService;
    private final WebhookEventRepository webhookEventRepository;
    private final Clock clock;

    public CutoffJobScheduler(
            ClockifyCutoffService cutoffService,
            WebhookEventRepository webhookEventRepository,
            Clock clock) {
        this.cutoffService = cutoffService;
        this.webhookEventRepository = webhookEventRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${clockify.cutoff.interval-ms:60000}")
    @SchedulerLock(name = "CutoffJobScheduler.tick", lockAtMostFor = "PT5M", lockAtLeastFor = "PT15S")
    public void tick() {
        try {
            cutoffService.processDueJobs("scheduler");
            cutoffService.reconcileAll("scheduler");
        } catch (Exception e) {
            log.warn("Scheduled cutoff tick failed", e);
        }
    }

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
}

package com.devodox.stopatestimate.scheduler;

import com.devodox.stopatestimate.service.ClockifyCutoffService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CutoffJobScheduler {
    private static final Logger log = LoggerFactory.getLogger(CutoffJobScheduler.class);

    private final ClockifyCutoffService cutoffService;

    public CutoffJobScheduler(ClockifyCutoffService cutoffService) {
        this.cutoffService = cutoffService;
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
}

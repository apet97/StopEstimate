package com.devodox.stopatestimate.scheduler;

import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.devodox.stopatestimate.repository.WebhookEventRepository;
import com.devodox.stopatestimate.service.EstimateGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CutoffJobSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-04-19T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);

    private EstimateGuardService cutoffService;
    private WebhookEventRepository webhookEventRepository;
    private GuardEventRepository guardEventRepository;
    private CutoffJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        cutoffService = mock(EstimateGuardService.class);
        webhookEventRepository = mock(WebhookEventRepository.class);
        guardEventRepository = mock(GuardEventRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        scheduler = new CutoffJobScheduler(
                cutoffService, webhookEventRepository, guardEventRepository, clock, RETENTION);
    }

    @Test
    void purgeGuardEventsPassesNowMinusRetentionAsCutoff() {
        when(guardEventRepository.deleteAllOlderThan(any(Instant.class))).thenReturn(7);

        scheduler.purgeGuardEvents();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(guardEventRepository).deleteAllOlderThan(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isEqualTo(NOW.minus(RETENTION));
    }

    @Test
    void purgeGuardEventsSwallowsExceptionsSoFutureTicksStillRun() {
        when(guardEventRepository.deleteAllOlderThan(any(Instant.class)))
                .thenThrow(new RuntimeException("transient db blip"));

        // Must not propagate — otherwise ShedLock marks the tick failed and we'd need manual intervention.
        scheduler.purgeGuardEvents();

        verify(guardEventRepository).deleteAllOlderThan(any(Instant.class));
    }
}

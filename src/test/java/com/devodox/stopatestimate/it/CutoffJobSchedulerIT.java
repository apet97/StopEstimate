package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.scheduler.CutoffJobScheduler;
import com.devodox.stopatestimate.service.EstimateGuardService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CutoffJobSchedulerIT extends AbstractPostgresIT {

    private static final String LOCK_NAME = "CutoffJobScheduler.tick";

    @Autowired
    private CutoffJobScheduler scheduler;

    @MockBean
    private EstimateGuardService estimateGuardService;

    @MockBean(name = "taskScheduler")
    private ThreadPoolTaskScheduler ignoredTaskScheduler;

    @BeforeEach
    void resetGuardService() {
        reset(estimateGuardService);
        doNothing().when(estimateGuardService).processDueJobs(ArgumentMatchers.anyString());
        doNothing().when(estimateGuardService).reconcileAll(ArgumentMatchers.anyString());
    }

    @Test
    void twoParallelTicks_onlyOneExecutes() throws Exception {
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        doAnswer(invocation -> blockFirstTick(firstStarted, releaseFirst, invocation))
                .when(estimateGuardService)
                .processDueJobs("scheduler");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(scheduler::tick);
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(scheduler::tick);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(estimateGuardService, times(1)).processDueJobs("scheduler"));

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        verify(estimateGuardService, times(1)).processDueJobs("scheduler");
        verify(estimateGuardService, times(1)).reconcileAll("scheduler");
        assertThat(countRows("select count(*) from shedlock where name = ?", LOCK_NAME)).isEqualTo(1);
    }

    @Test
    void lockReleasedAfterTick_subsequentCallSucceeds() {
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();

        scheduler.tick();
        verify(estimateGuardService, times(1)).processDueJobs("scheduler");
        verify(estimateGuardService, times(1)).reconcileAll("scheduler");
        assertThat(countRows("select count(*) from shedlock where name = ?", LOCK_NAME)).isEqualTo(1);

        sleepPastLockAtLeastFor();
        scheduler.tick();

        verify(estimateGuardService, times(2)).processDueJobs("scheduler");
        verify(estimateGuardService, times(2)).reconcileAll("scheduler");
        assertThat(countRows("select count(*) from shedlock where name = ?", LOCK_NAME)).isEqualTo(1);
    }

    private static Void blockFirstTick(
            CountDownLatch firstStarted,
            CountDownLatch releaseFirst,
            InvocationOnMock invocation) throws InterruptedException {
        firstStarted.countDown();
        assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
        return null;
    }

    private static void sleepPastLockAtLeastFor() {
        try {
            Thread.sleep(Duration.ofSeconds(16).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

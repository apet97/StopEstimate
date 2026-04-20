package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.model.entity.CutoffJobEntity;
import com.devodox.stopatestimate.repository.CutoffJobRepository;
import com.devodox.stopatestimate.scheduler.CutoffJobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CutoffJobRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private CutoffJobRepository cutoffJobRepository;

    @MockBean
    private CutoffJobScheduler ignoredScheduler;

    @Test
    void upsertByWorkspaceAndTimeEntry_insertThenUpdate_onConflictIncrementsVersion() {
        String workspaceId = "ws-upsert";
        Instant firstCutoff = Instant.parse("2026-04-20T10:15:00Z");
        Instant secondCutoff = Instant.parse("2026-04-20T10:45:00Z");

        installationStore.save(installation(workspaceId));

        cutoffJobRepository.upsertByWorkspaceAndTimeEntry(
                "job-1", workspaceId, "project-1", "user-1", "time-entry-1", firstCutoff);
        cutoffJobRepository.upsertByWorkspaceAndTimeEntry(
                "job-2", workspaceId, "project-2", "user-2", "time-entry-1", secondCutoff);

        CutoffJobEntity row = cutoffJobRepository.findFirstByWorkspaceIdAndTimeEntryId(workspaceId, "time-entry-1")
                .orElseThrow();

        assertThat(row.getJobId()).isEqualTo("job-1");
        assertThat(row.getProjectId()).isEqualTo("project-2");
        assertThat(row.getUserId()).isEqualTo("user-2");
        assertThat(row.getCutoffAt()).isEqualTo(secondCutoff);
        assertThat(row.getVersion()).isEqualTo(1L);
    }

    @Test
    void uniqueConstraintExistsPostMigration() {
        int count = countRows(
                "select count(*) from pg_constraint where conname = ?",
                "uk_cutoff_jobs_workspace_time_entry");

        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentUpsertsSerializeSafely() throws Exception {
        String workspaceId = "ws-concurrent-upsert";
        Instant cutoffAt = Instant.parse("2026-04-20T11:00:00Z");
        int callsPerThread = 50;

        installationStore.save(installation(workspaceId));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> tasks = List.of(
                    upsertBurst(workspaceId, "worker-a", cutoffAt, callsPerThread, ready, start),
                    upsertBurst(workspaceId, "worker-b", cutoffAt, callsPerThread, ready, start));

            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(task));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        CutoffJobEntity row = cutoffJobRepository.findFirstByWorkspaceIdAndTimeEntryId(workspaceId, "time-entry-1")
                .orElseThrow();

        assertThat(countRows(
                "select count(*) from cutoff_jobs where workspace_id = ? and time_entry_id = ?",
                workspaceId,
                "time-entry-1")).isEqualTo(1);
        assertThat(row.getVersion()).isEqualTo((2L * callsPerThread) - 1L);
        assertThat(row.getCutoffAt()).isEqualTo(cutoffAt);
    }

    private Callable<Void> upsertBurst(
            String workspaceId,
            String worker,
            Instant cutoffAt,
            int calls,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < calls; i++) {
                cutoffJobRepository.upsertByWorkspaceAndTimeEntry(
                        worker + "-" + UUID.randomUUID(),
                        workspaceId,
                        "project-1",
                        "user-1",
                        "time-entry-1",
                        cutoffAt);
            }
            return null;
        };
    }
}

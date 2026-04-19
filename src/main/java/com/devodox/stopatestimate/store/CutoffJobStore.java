package com.devodox.stopatestimate.store;

import com.devodox.stopatestimate.model.PendingCutoffJob;
import com.devodox.stopatestimate.model.entity.CutoffJobEntity;
import com.devodox.stopatestimate.repository.CutoffJobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class CutoffJobStore {

    private static final int DUE_JOB_PAGE_SIZE = 200;

    private final CutoffJobRepository repository;

    public CutoffJobStore(CutoffJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void save(PendingCutoffJob job) {
        CutoffJobEntity entity = repository.findById(job.jobId()).orElseGet(CutoffJobEntity::new);
        entity.setJobId(job.jobId());
        entity.setWorkspaceId(job.workspaceId());
        entity.setProjectId(job.projectId());
        entity.setUserId(job.userId());
        entity.setTimeEntryId(job.timeEntryId());
        entity.setCutoffAt(job.cutoffAt());
        entity.setCreatedAt(job.createdAt());
        repository.save(entity);
    }

    @Transactional
    public PendingCutoffJob upsert(String workspaceId, String projectId, String userId, String timeEntryId, Instant cutoffAt) {
        // Atomic (workspace_id, time_entry_id) upsert backed by uk_cutoff_jobs_workspace_time_entry.
        // A candidate jobId is used only for net-new rows; existing rows keep their original PK.
        String candidateJobId = UUID.randomUUID().toString();
        repository.upsertByWorkspaceAndTimeEntry(candidateJobId, workspaceId, projectId, userId, timeEntryId, cutoffAt);
        return repository.findFirstByWorkspaceIdAndTimeEntryId(workspaceId, timeEntryId)
                .map(this::toRecord)
                .orElseThrow(() -> new IllegalStateException(
                        "cutoff_jobs row vanished after upsert for " + workspaceId + "/" + timeEntryId));
    }

    @Transactional(readOnly = true)
    public Optional<PendingCutoffJob> findByJobId(String jobId) {
        return repository.findById(jobId).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public List<PendingCutoffJob> findDueJobs(Instant now) {
        return repository.findAllByCutoffAtLessThanEqual(now, PageRequest.of(0, DUE_JOB_PAGE_SIZE))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PendingCutoffJob> findAllJobs() {
        List<PendingCutoffJob> result = new ArrayList<>();
        for (CutoffJobEntity entity : repository.findAll()) {
            result.add(toRecord(entity));
        }
        return result;
    }

    @Transactional
    public int deleteByJobId(String jobId) {
        return repository.deleteByJobId(jobId);
    }

    @Transactional(readOnly = true)
    public List<PendingCutoffJob> findByWorkspaceId(String workspaceId) {
        return repository.findAllByWorkspaceId(workspaceId).stream().map(this::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public List<PendingCutoffJob> findByProject(String workspaceId, String projectId) {
        return repository.findAllByWorkspaceIdAndProjectId(workspaceId, projectId).stream()
                .map(this::toRecord)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PendingCutoffJob> findByTimeEntryId(String workspaceId, String timeEntryId) {
        return repository.findFirstByWorkspaceIdAndTimeEntryId(workspaceId, timeEntryId).map(this::toRecord);
    }

    @Transactional
    public void deleteByProject(String workspaceId, String projectId) {
        repository.deleteAllByWorkspaceIdAndProjectId(workspaceId, projectId);
    }

    @Transactional
    public int deleteStale(String workspaceId, String projectId, java.util.Collection<String> keepTimeEntryIds) {
        if (keepTimeEntryIds == null || keepTimeEntryIds.isEmpty()) {
            repository.deleteAllByWorkspaceIdAndProjectId(workspaceId, projectId);
            return 0;
        }
        return repository.deleteStaleByProject(workspaceId, projectId, keepTimeEntryIds);
    }

    @Transactional
    public void deleteByWorkspaceId(String workspaceId) {
        repository.deleteAllByWorkspaceId(workspaceId);
    }

    @Transactional
    public void deleteAllJobs() {
        repository.deleteAll();
    }

    private PendingCutoffJob toRecord(CutoffJobEntity entity) {
        return new PendingCutoffJob(
                entity.getJobId(),
                entity.getWorkspaceId(),
                entity.getProjectId(),
                entity.getUserId(),
                entity.getTimeEntryId(),
                entity.getCutoffAt(),
                entity.getCreatedAt());
    }
}

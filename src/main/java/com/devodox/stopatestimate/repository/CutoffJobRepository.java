package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.CutoffJobEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CutoffJobRepository extends JpaRepository<CutoffJobEntity, String> {

    List<CutoffJobEntity> findAllByWorkspaceId(String workspaceId);

    List<CutoffJobEntity> findAllByWorkspaceIdAndProjectId(String workspaceId, String projectId);

    Optional<CutoffJobEntity> findFirstByWorkspaceIdAndTimeEntryId(String workspaceId, String timeEntryId);

    List<CutoffJobEntity> findAllByCutoffAtLessThanEqualOrderByCutoffAtAsc(Instant cutoffAt, Pageable pageable);

    default List<CutoffJobEntity> findAllByCutoffAtLessThanEqual(Instant cutoffAt, Pageable pageable) {
        return findAllByCutoffAtLessThanEqualOrderByCutoffAtAsc(cutoffAt, pageable);
    }

    @Modifying
    @Transactional
    @Query("delete from CutoffJobEntity j where j.jobId = :jobId")
    int deleteByJobId(@Param("jobId") String jobId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO cutoff_jobs (job_id, workspace_id, project_id, user_id, time_entry_id, cutoff_at, created_at, version)
            VALUES (:jobId, :workspaceId, :projectId, :userId, :timeEntryId, :cutoffAt, NOW(), 0)
            ON CONFLICT ON CONSTRAINT uk_cutoff_jobs_workspace_time_entry
            DO UPDATE SET project_id = EXCLUDED.project_id,
                          user_id    = EXCLUDED.user_id,
                          cutoff_at  = EXCLUDED.cutoff_at,
                          version    = cutoff_jobs.version + 1
            """, nativeQuery = true)
    int upsertByWorkspaceAndTimeEntry(@Param("jobId") String jobId,
                                      @Param("workspaceId") String workspaceId,
                                      @Param("projectId") String projectId,
                                      @Param("userId") String userId,
                                      @Param("timeEntryId") String timeEntryId,
                                      @Param("cutoffAt") Instant cutoffAt);

    @Modifying
    @Transactional
    @Query("delete from CutoffJobEntity j where j.workspaceId = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") String workspaceId);

    @Modifying
    @Transactional
    @Query("delete from CutoffJobEntity j where j.workspaceId = :workspaceId and j.projectId = :projectId")
    void deleteAllByWorkspaceIdAndProjectId(@Param("workspaceId") String workspaceId,
                                             @Param("projectId") String projectId);

    @Modifying
    @Transactional
    @Query("delete from CutoffJobEntity j where j.workspaceId = :workspaceId and j.projectId = :projectId and j.timeEntryId not in :keepTimeEntryIds")
    int deleteStaleByProject(@Param("workspaceId") String workspaceId,
                             @Param("projectId") String projectId,
                             @Param("keepTimeEntryIds") Collection<String> keepTimeEntryIds);
}

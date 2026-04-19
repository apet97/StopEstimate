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

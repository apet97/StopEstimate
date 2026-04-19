package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.GuardEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface GuardEventRepository extends JpaRepository<GuardEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("delete from GuardEventEntity e where e.createdAt < :cutoff")
    int deleteAllOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Workspace-scoped audit feed, newest first. {@code projectId} is optional — when null, returns
     * events for any project in the workspace.
     */
    @Query("select new com.devodox.stopatestimate.repository.GuardEventRepository$GuardEventView("
            + "e.createdAt, e.eventType, e.guardReason, e.source, e.projectId, e.payloadFingerprint) "
            + "from GuardEventEntity e "
            + "where e.workspaceId = :workspaceId "
            + "and (:projectId is null or e.projectId = :projectId) "
            + "order by e.createdAt desc, e.id desc")
    List<GuardEventView> findRecent(
            @Param("workspaceId") String workspaceId,
            @Param("projectId") String projectId,
            Pageable pageable);

    /** Read-only projection safe to serialize to sidebar callers. */
    record GuardEventView(
            Instant createdAt,
            String eventType,
            String guardReason,
            String source,
            String projectId,
            String payloadFingerprint) {
    }
}

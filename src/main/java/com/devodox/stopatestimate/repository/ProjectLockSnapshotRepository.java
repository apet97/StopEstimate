package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.ProjectLockSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ProjectLockSnapshotRepository extends JpaRepository<ProjectLockSnapshotEntity, ProjectLockSnapshotEntity.Key> {

    List<ProjectLockSnapshotEntity> findAllByIdWorkspaceId(String workspaceId);

    @Modifying
    @Transactional
    @Query("delete from ProjectLockSnapshotEntity s where s.id.workspaceId = :workspaceId")
    void deleteAllByIdWorkspaceId(@Param("workspaceId") String workspaceId);
}

package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.ProjectLockSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectLockSnapshotRepository extends JpaRepository<ProjectLockSnapshotEntity, ProjectLockSnapshotEntity.Key> {

    List<ProjectLockSnapshotEntity> findAllByIdWorkspaceId(String workspaceId);

    void deleteAllByIdWorkspaceId(String workspaceId);
}

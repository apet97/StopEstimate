package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.InstallationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InstallationRepository extends JpaRepository<InstallationEntity, String> {

    // DB-08: lets the DB use idx_installations_active (partial on status='ACTIVE' AND enabled=true)
    // so the scheduler's reconcile loop doesn't full-scan installations and filter in memory.
    @Query("select i from InstallationEntity i where i.status = 'ACTIVE' and i.enabled = true")
    List<InstallationEntity> findAllActive();
}

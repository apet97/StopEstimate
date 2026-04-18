package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.InstallationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstallationRepository extends JpaRepository<InstallationEntity, String> {
}

package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.WebhookRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookRegistrationRepository extends JpaRepository<WebhookRegistrationEntity, Long> {

    List<WebhookRegistrationEntity> findAllByWorkspaceId(String workspaceId);

    Optional<WebhookRegistrationEntity> findByWorkspaceIdAndRoutePath(String workspaceId, String routePath);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WebhookRegistrationEntity w where w.workspaceId = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") String workspaceId);
}

package com.devodox.stopatestimate.repository;

import com.devodox.stopatestimate.model.entity.WebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, WebhookEventEntity.Key> {

    boolean existsByIdEventIdAndIdSignatureHash(String eventId, String signatureHash);

    @Modifying
    @Transactional
    @Query("delete from WebhookEventEntity w where w.receivedAt < :cutoff")
    int deleteAllOlderThan(@Param("cutoff") Instant cutoff);
}

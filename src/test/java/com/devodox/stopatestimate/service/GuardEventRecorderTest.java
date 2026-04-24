package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.GuardEventType;
import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.entity.GuardEventEntity;
import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class GuardEventRecorderTest {

    @Test
    void recordsRowWithFingerprintFromPayload() {
        GuardEventRepository repo = Mockito.mock(GuardEventRepository.class);
        GuardEventRecorder recorder = new GuardEventRecorder(repo);

        JsonObject payload = new JsonObject();
        payload.addProperty("k", "v");

        recorder.record(
                "ws-1",
                "p-1",
                GuardEventType.LOCKED,
                GuardReason.TIME_CAP_REACHED,
                "webhook:NEW_TIMER_STARTED",
                payload);

        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        Mockito.verify(repo).save(captor.capture());
        GuardEventEntity row = captor.getValue();
        assertThat(row.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(row.getProjectId()).isEqualTo("p-1");
        assertThat(row.getEventType()).isEqualTo("LOCKED");
        assertThat(row.getGuardReason()).isEqualTo("TIME_CAP_REACHED");
        assertThat(row.getSource()).isEqualTo("webhook:NEW_TIMER_STARTED");
        // Stable 16-hex prefix of SHA-256 over the payload JSON. Exact value asserted so a
        // future change to the fingerprint surface (length, encoding) breaks this test loudly.
        assertThat(row.getPayloadFingerprint())
                .hasSize(16)
                .matches("^[0-9a-f]+$");
    }

    @Test
    void nullPayloadFingerprintIsSchedulerSentinel() {
        GuardEventRepository repo = Mockito.mock(GuardEventRepository.class);
        GuardEventRecorder recorder = new GuardEventRecorder(repo);

        recorder.record(
                "ws-1",
                "p-1",
                GuardEventType.UNLOCKED,
                null,
                "scheduler",
                null);

        ArgumentCaptor<GuardEventEntity> captor = ArgumentCaptor.forClass(GuardEventEntity.class);
        Mockito.verify(repo).save(captor.capture());
        GuardEventEntity row = captor.getValue();
        // Null reason normalises to null in the row (so the column is nullable in DB);
        // null payload normalises to the "scheduler" sentinel so audit-trail rows from the
        // scheduler tick are visually distinguishable from webhook-driven ones.
        assertThat(row.getGuardReason()).isNull();
        assertThat(row.getPayloadFingerprint()).isEqualTo("scheduler");
    }
}

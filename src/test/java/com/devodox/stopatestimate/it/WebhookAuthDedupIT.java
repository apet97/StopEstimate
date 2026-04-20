package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.TestJwtFactory;
import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import com.devodox.stopatestimate.scheduler.CutoffJobScheduler;
import com.devodox.stopatestimate.service.EstimateGuardService;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebhookAuthDedupIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private EstimateGuardService estimateGuardService;

    @MockBean
    private ClockifyBackendApiClient backendApiClient;

    @MockBean
    private ClockifyReportsApiClient reportsApiClient;

    @MockBean
    private CutoffJobScheduler ignoredScheduler;

    @BeforeEach
    void resetSpy() {
        Mockito.reset(estimateGuardService, backendApiClient, reportsApiClient);
        doNothing().when(estimateGuardService)
                .reconcileProject(anyString(), anyString(), anyString(), ArgumentMatchers.<JsonObject>any());
    }

    @Test
    void sameEventIdDeliveredTwice_reconcileCalledExactlyOnce() throws Exception {
        String workspaceId = "ws-webhook-dedup-same";
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        installationStore.save(installationWithWebhook(
                workspaceId,
                "/new-time-entry",
                "NEW_TIME_ENTRY",
                webhookToken));

        String body = """
                {
                  "workspaceId": "%s",
                  "projectId": "project-1",
                  "id": "time-entry-1"
                }
                """.formatted(workspaceId);

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content(body))
                .andExpect(status().isOk());

        verify(estimateGuardService, times(1))
                .reconcileProject(anyString(), anyString(), anyString(), ArgumentMatchers.<JsonObject>any());
        assertThat(countRows("select count(*) from webhook_events where workspace_id = ?", workspaceId)).isEqualTo(1);
    }

    @Test
    void differentEventIds_bothReconcile() throws Exception {
        String workspaceId = "ws-webhook-dedup-different";
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        installationStore.save(installationWithWebhook(
                workspaceId,
                "/new-time-entry",
                "NEW_TIME_ENTRY",
                webhookToken));

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content("""
                                {
                                  "workspaceId": "%s",
                                  "projectId": "project-1",
                                  "id": "time-entry-1"
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content("""
                                {
                                  "workspaceId": "%s",
                                  "projectId": "project-1",
                                  "id": "time-entry-2"
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        verify(estimateGuardService, times(2))
                .reconcileProject(anyString(), anyString(), anyString(), ArgumentMatchers.<JsonObject>any());
        assertThat(countRows("select count(*) from webhook_events where workspace_id = ?", workspaceId)).isEqualTo(2);
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        String workspaceId = "ws-webhook-invalid";
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        installationStore.save(installationWithWebhook(
                workspaceId,
                "/new-time-entry",
                "NEW_TIME_ENTRY",
                webhookToken));

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", TestJwtFactory.signClaimsWithForeignKey(
                                TestJwtFactory.baseValidClaims(workspaceId)))
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content("""
                                {
                                  "workspaceId": "%s",
                                  "projectId": "project-1",
                                  "id": "time-entry-1"
                                }
                                """.formatted(workspaceId)))
                .andExpect(status().isUnauthorized());

        assertThat(countRows("select count(*) from webhook_events where workspace_id = ?", workspaceId)).isEqualTo(0);
    }

    @Test
    void bodyHashFallbackWhenEventIdMissing() throws Exception {
        String workspaceId = "ws-webhook-bodyhash";
        String webhookToken = TestJwtFactory.webhookToken(workspaceId);

        installationStore.save(installationWithWebhook(
                workspaceId,
                "/new-time-entry",
                "NEW_TIME_ENTRY",
                webhookToken));

        String body = """
                {
                  "workspaceId": "%s",
                  "projectId": "project-1"
                }
                """.formatted(workspaceId);

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/webhook/new-time-entry")
                        .contentType(APPLICATION_JSON)
                        .header("Clockify-Signature", webhookToken)
                        .header("clockify-webhook-event-type", "NEW_TIME_ENTRY")
                        .content(body))
                .andExpect(status().isOk());

        verify(estimateGuardService, times(1))
                .reconcileProject(anyString(), anyString(), anyString(), ArgumentMatchers.<JsonObject>any());

        String eventId = jdbcTemplate.queryForObject("select event_id from webhook_events where workspace_id = ?",
                String.class,
                workspaceId);
        assertThat(eventId).startsWith("body:");
    }

}

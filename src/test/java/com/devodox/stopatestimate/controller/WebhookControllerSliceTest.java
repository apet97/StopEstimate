package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.api.ClockifyAccessForbiddenException;
import com.devodox.stopatestimate.api.ClockifyApiException;
import com.devodox.stopatestimate.api.ClockifyRequestAuthException;
import com.devodox.stopatestimate.service.ClockifyWebhookService;
import com.devodox.stopatestimate.service.VerifiedAddonContextService;
import com.devodox.stopatestimate.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class WebhookControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClockifyWebhookService webhookService;

    // Required because @WebMvcTest auto-registers the AddonTokenVerificationInterceptor
    // (a HandlerInterceptor bean) whose constructor needs this service. Webhook routes do not
    // pass through the interceptor (it's scoped to /api/**), so the mock is never actually called.
    @MockBean
    private VerifiedAddonContextService verifiedAddonContextService;

    @Test
    void validSignatureAndEventTypeYields200() throws Exception {
        doNothing().when(webhookService).handleWebhook(
                ArgumentMatchers.eq("NEW_TIMER_STARTED"),
                ArgumentMatchers.eq("/webhook/new-timer-started"),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());

        mockMvc.perform(post("/webhook/new-timer-started")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Clockify-Signature", "jwt.looking.string")
                        .header("clockify-webhook-event-type", "NEW_TIMER_STARTED")
                        .content("{\"id\":\"te-1\"}"))
                .andExpect(status().isOk());

        verify(webhookService).handleWebhook(
                "NEW_TIMER_STARTED",
                "/webhook/new-timer-started",
                "{\"id\":\"te-1\"}",
                "jwt.looking.string",
                "NEW_TIMER_STARTED");
    }

    @Test
    void missingClockifySignatureHeaderYields401() throws Exception {
        mockMvc.perform(post("/webhook/new-timer-started")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("clockify-webhook-event-type", "NEW_TIMER_STARTED")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_auth_header"));
    }

    @Test
    void invalidSignatureYields401ViaAuthException() throws Exception {
        doThrow(new ClockifyRequestAuthException("Invalid webhook signature"))
                .when(webhookService).handleWebhook(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());

        mockMvc.perform(post("/webhook/new-timer-started")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Clockify-Signature", "bad")
                        .header("clockify-webhook-event-type", "NEW_TIMER_STARTED")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_request_token"));
    }

    @Test
    void eventTypeMismatchYields400() throws Exception {
        doThrow(new IllegalArgumentException("Unexpected webhook type for route"))
                .when(webhookService).handleWebhook(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());

        mockMvc.perform(post("/webhook/new-timer-started")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Clockify-Signature", "jwt")
                        .header("clockify-webhook-event-type", "TIMER_STOPPED")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void webhookTokenMismatchYields403() throws Exception {
        doThrow(new ClockifyAccessForbiddenException("Webhook auth token mismatch"))
                .when(webhookService).handleWebhook(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());

        mockMvc.perform(post("/webhook/new-timer-started")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Clockify-Signature", "jwt")
                        .header("clockify-webhook-event-type", "NEW_TIMER_STARTED")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("webhook_token_mismatch"));
    }

    @Test
    void clockifyApiFailureStillYields200SoClockifyRetriesAreAbsorbed() throws Exception {
        // Controller catches ClockifyApiException and returns 200; the scheduler reconciles later.
        // This is the documented contract that NEXT_SESSION.md H1 flagged — if it ever regresses,
        // Clockify would retry forever on 5xx from us.
        doThrow(new ClockifyApiException("backend 500"))
                .when(webhookService).handleWebhook(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());

        mockMvc.perform(post("/webhook/new-timer-started")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Clockify-Signature", "jwt")
                        .header("clockify-webhook-event-type", "NEW_TIMER_STARTED")
                        .content("{}"))
                .andExpect(status().isOk());
    }
}

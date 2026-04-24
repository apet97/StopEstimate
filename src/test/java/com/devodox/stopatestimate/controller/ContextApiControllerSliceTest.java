package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.VerifiedAddonContext;
import com.devodox.stopatestimate.service.ClockifyLifecycleService;
import com.devodox.stopatestimate.service.InvalidAddonTokenException;
import com.devodox.stopatestimate.service.VerifiedAddonContextService;
import com.devodox.stopatestimate.web.AddonTokenVerificationInterceptor;
import com.devodox.stopatestimate.web.AddonWebMvcConfig;
import com.devodox.stopatestimate.web.GlobalExceptionHandler;
import com.devodox.stopatestimate.web.VerifiedAddonContextArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ContextApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        GlobalExceptionHandler.class,
        AddonWebMvcConfig.class,
        AddonTokenVerificationInterceptor.class,
        VerifiedAddonContextArgumentResolver.class
})
class ContextApiControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClockifyLifecycleService lifecycleService;

    @MockBean
    private VerifiedAddonContextService verifiedAddonContextService;

    private static VerifiedAddonContext ctx() {
        return new VerifiedAddonContext(
                "ws-1",
                "addon-1",
                "user-1",
                "https://api.clockify.me/api",
                "https://reports.api.clockify.me",
                "en",
                "LIGHT",
                Map.of());
    }

    private static InstallationRecord installed() {
        Instant now = Instant.parse("2026-04-20T10:00:00Z");
        return new InstallationRecord(
                "ws-1",
                "addon-1",
                "user-1",
                "owner-1",
                "installation-token",
                "https://api.clockify.me/api",
                "https://reports.api.clockify.me",
                Map.of(),
                AddonStatus.ACTIVE,
                true,
                "ENFORCE",
                "WEEKLY",
                now,
                now);
    }

    @Test
    void contextEndpointRequiresXAddonToken() throws Exception {
        mockMvc.perform(get("/api/context"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_addon_token"));
    }

    @Test
    void contextEndpointExposesInstallationStateButOmitsBackendUrl() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.of(installed()));

        mockMvc.perform(get("/api/context").header("X-Addon-Token", "jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value("ws-1"))
                .andExpect(jsonPath("$.installed").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultResetCadence").value("WEEKLY"))
                // RES-06: backendUrl / reportsUrl must never leak to the iframe.
                .andExpect(jsonPath("$.backendUrl").doesNotExist())
                .andExpect(jsonPath("$.reportsUrl").doesNotExist())
                .andExpect(jsonPath("$.installationToken").doesNotExist());
    }

    @Test
    void contextEndpointReportsNotInstalledWhenNoRecord() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());
        when(lifecycleService.findInstallation("ws-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/context").header("X-Addon-Token", "jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(false))
                .andExpect(jsonPath("$.status").value("NOT_INSTALLED"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void expiredTokenYields401() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString()))
                .thenThrow(new InvalidAddonTokenException("Clockify token has expired"));

        mockMvc.perform(get("/api/context").header("X-Addon-Token", "expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_addon_token"));
    }
}

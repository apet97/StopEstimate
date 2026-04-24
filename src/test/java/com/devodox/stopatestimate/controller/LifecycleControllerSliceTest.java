package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.service.ClockifyLifecycleService;
import com.devodox.stopatestimate.service.InvalidAddonTokenException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LifecycleController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class LifecycleControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClockifyLifecycleService lifecycleService;

    // Required because @WebMvcTest auto-registers the AddonTokenVerificationInterceptor
    // (a HandlerInterceptor bean) whose constructor needs this service. Lifecycle routes do not
    // pass through the interceptor (it's scoped to /api/**), so the mock is never actually called.
    @MockBean
    private VerifiedAddonContextService verifiedAddonContextService;

    @Test
    void installedHappyPathYields200() throws Exception {
        doNothing().when(lifecycleService).handleInstalled(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", "jwt")
                        .content("{\"addonId\":\"a-1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void missingLifecycleTokenHeaderYields401() throws Exception {
        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_auth_header"));
    }

    @Test
    void invalidTokenYields401() throws Exception {
        doThrow(new InvalidAddonTokenException("Clockify token has expired"))
                .when(lifecycleService).handleInstalled(
                        ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        mockMvc.perform(post("/lifecycle/installed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", "expired")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_addon_token"));
    }

    @Test
    void deletedRouteReachesService() throws Exception {
        doNothing().when(lifecycleService).handleDeleted(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        mockMvc.perform(post("/lifecycle/deleted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", "jwt")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void statusChangedRouteReachesService() throws Exception {
        doNothing().when(lifecycleService).handleStatusChanged(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        mockMvc.perform(post("/lifecycle/status-changed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", "jwt")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void settingsUpdatedRouteReachesService() throws Exception {
        doNothing().when(lifecycleService).handleSettingsUpdated(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());

        mockMvc.perform(post("/lifecycle/settings-updated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Lifecycle-Token", "jwt")
                        .content("{}"))
                .andExpect(status().isOk());
    }
}

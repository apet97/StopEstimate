package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.model.VerifiedAddonContext;
import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.devodox.stopatestimate.service.EstimateGuardService;
import com.devodox.stopatestimate.service.InvalidAddonTokenException;
import com.devodox.stopatestimate.service.ProjectSummaryService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GuardApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        GlobalExceptionHandler.class,
        AddonWebMvcConfig.class,
        AddonTokenVerificationInterceptor.class,
        VerifiedAddonContextArgumentResolver.class
})
class GuardApiControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EstimateGuardService estimateGuardService;

    @MockBean
    private ProjectSummaryService projectSummaryService;

    @MockBean
    private GuardEventRepository guardEventRepository;

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
                "DARK",
                Map.of());
    }

    @Test
    void projectsEndpointRequiresXAddonToken() throws Exception {
        mockMvc.perform(get("/api/guard/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_addon_token"));
    }

    @Test
    void projectsEndpointWithValidTokenYields200() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());
        when(projectSummaryService.listProjectSummaries("ws-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/guard/projects").header("X-Addon-Token", "jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value("ws-1"))
                .andExpect(jsonPath("$.projects").isArray());
    }

    @Test
    void expiredTokenYields401() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString()))
                .thenThrow(new InvalidAddonTokenException("Clockify token has expired"));

        mockMvc.perform(get("/api/guard/projects").header("X-Addon-Token", "expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_addon_token"));
    }

    @Test
    void reconcileEndpointDrivesReconcileAndReturnsProjects() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());
        when(projectSummaryService.listProjectSummaries("ws-1")).thenReturn(List.of());

        mockMvc.perform(post("/api/guard/reconcile").header("X-Addon-Token", "jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciled").value(true));

        verify(estimateGuardService).reconcileKnownProjects("ws-1", "api:manual");
    }

    @Test
    void eventsEndpointRejectsLimitAboveMaxWith400() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());

        mockMvc.perform(get("/api/guard/events")
                        .header("X-Addon-Token", "jwt")
                        .param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void eventsEndpointRejectsLimitBelowMinWith400() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());

        mockMvc.perform(get("/api/guard/events")
                        .header("X-Addon-Token", "jwt")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void eventsEndpointAcceptsLimitAtMaxBoundary() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());
        when(guardEventRepository.findRecent(anyString(), any(), any(PageRequest.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/guard/events")
                        .header("X-Addon-Token", "jwt")
                        .param("limit", "200"))
                .andExpect(status().isOk());

        verify(guardEventRepository).findRecent("ws-1", null, PageRequest.of(0, 200));
    }

    @Test
    void eventsEndpointPassesProjectIdFilterThrough() throws Exception {
        when(verifiedAddonContextService.verifyRequired(anyString())).thenReturn(ctx());
        when(guardEventRepository.findRecent(anyString(), anyString(), any(PageRequest.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/guard/events")
                        .header("X-Addon-Token", "jwt")
                        .param("projectId", "proj-42"))
                .andExpect(status().isOk());

        verify(guardEventRepository).findRecent("ws-1", "proj-42", PageRequest.of(0, 50));
    }
}

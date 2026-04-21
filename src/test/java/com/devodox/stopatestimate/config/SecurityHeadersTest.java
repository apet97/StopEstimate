package com.devodox.stopatestimate.config;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClockifyBackendApiClient backendApiClient;

    @MockBean
    private ClockifyReportsApiClient reportsApiClient;

    @Test
    void manifestShipsCspWithClockifyFrameAncestors() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("frame-ancestors https://*.clockify.me")));
    }

    @Test
    void manifestShipsStrictTransportSecurityForOneYearIncludingSubdomains() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Strict-Transport-Security",
                        containsString("max-age=31536000")))
                .andExpect(header().string(
                        "Strict-Transport-Security",
                        containsString("includeSubDomains")));
    }

    @Test
    void manifestShipsXContentTypeOptionsNosniff() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void manifestOmitsXFrameOptions() throws Exception {
        // CSP frame-ancestors is authoritative. Leaving XFO in place would block Firefox,
        // which AND-combines the two headers and rejects Clockify's origin under SAMEORIGIN.
        mockMvc.perform(get("/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Frame-Options"));
    }

    @Test
    void cspRestrictsScriptAndStyleToSelf() throws Exception {
        mockMvc.perform(get("/manifest"))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("script-src 'self'")))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("style-src 'self'")));
    }

    @Test
    void sidebarShipsCspWithClockifyFrameAncestors() throws Exception {
        mockMvc.perform(get("/sidebar"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("frame-ancestors https://*.clockify.me")));
    }

    @Test
    void actuatorHealthIsPubliclyReachable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorInfoIsNotPubliclyReachable() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/info")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404);
    }

    @Test
    void actuatorEnvIsNotPubliclyReachable() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/env")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404);
    }
}

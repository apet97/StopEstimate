package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D3: migrated from H2 to Testcontainers Postgres via {@link AbstractPostgresIT}. The header
 * assertions are DB-agnostic, but the test loads a full Spring context — so it inherits the IT
 * profile + Testcontainers wiring rather than maintaining a separate H2 path.
 */
@AutoConfigureMockMvc
class SecurityHeadersIT extends AbstractPostgresIT {

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
    void actuatorLivenessAndReadinessProbesArePubliclyReachable() throws Exception {
        // B2: K8s-style probes. 200 is returned once the application context is up.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorPrometheusIsPubliclyReachable() throws Exception {
        // B1: Prometheus scrape endpoint must be reachable without auth so operators can wire
        // a scrape job without building a sidecar. Any sensitivity comes from the metric
        // values themselves; the endpoint surface is intentional.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorInfoIsPubliclyReachable() throws Exception {
        // B1: /actuator/info is a low-risk read of build metadata (or empty when nothing is
        // configured). Kept public alongside prometheus for ops-friendliness.
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorEnvIsNotPubliclyReachable() throws Exception {
        // Non-exposed endpoints must stay blocked by the /actuator/** denyAll rule even if a
        // future yml change accidentally exposes them.
        MvcResult result = mockMvc.perform(get("/actuator/env")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404);
    }

    @Test
    void actuatorBeansIsNotPubliclyReachable() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/beans")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(401, 403, 404);
    }
}

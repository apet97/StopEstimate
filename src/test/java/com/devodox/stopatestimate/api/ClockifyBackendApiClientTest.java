package com.devodox.stopatestimate.api;

import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClockifyBackendApiClientTest {

    private static final String WORKSPACE_URL = "https://api.clockify.me/api/v1/workspaces/ws-1";

    @Test
    void getWorkspaceReturnsWorkspaceJson() {
        Fixture f = fixture();

        f.server.expect(requestTo(WORKSPACE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Addon-Token", "installation-token"))
                .andRespond(withSuccess("""
                        {"id":"ws-1","name":"Workspace","timeZone":"Europe/Belgrade"}
                        """, MediaType.APPLICATION_JSON));

        JsonObject workspace = f.client.getWorkspace(fakeInstallation());

        assertThat(workspace.get("timeZone").getAsString()).isEqualTo("Europe/Belgrade");
        f.server.verify();
    }

    // A1: classify() contract tests. Locks the exception-mapping contract that PR #22 split from
    // the ClockifyApiException parent. Any change to the hierarchy (see NEXT_SESSION.md H1 / A3)
    // must keep these green.

    @Test
    void classifyMaps401ToRequestAuthException() {
        Fixture f = fixture();

        f.server.expect(requestTo(WORKSPACE_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> f.client.getWorkspace(fakeInstallation()))
                .isInstanceOf(ClockifyRequestAuthException.class)
                .hasMessageContaining("Clockify backend")
                .hasMessageContaining("rejected");
        f.server.verify();
    }

    @Test
    void classifyMaps403ToBackendForbiddenException() {
        Fixture f = fixture();

        f.server.expect(requestTo(WORKSPACE_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> f.client.getWorkspace(fakeInstallation()))
                .isInstanceOf(ClockifyBackendForbiddenException.class)
                .hasMessageContaining("forbade");
        f.server.verify();
    }

    @Test
    void classifyMaps429AfterOneRetryToClockifyApiException() {
        Fixture f = fixture();

        // Retry-After: 0 so the single bounded retry does not slow the test.
        f.server.expect(requestTo(WORKSPACE_URL))
                .andRespond(withRawStatus(429).headers(retryAfterZero()));
        f.server.expect(requestTo(WORKSPACE_URL))
                .andRespond(withRawStatus(429).headers(retryAfterZero()));

        assertThatThrownBy(() -> f.client.getWorkspace(fakeInstallation()))
                .isInstanceOf(ClockifyApiException.class)
                .hasMessageContaining("rate limited")
                .hasMessageContaining("429");
        f.server.verify();
    }

    @Test
    void classifyMapsOtherStatusToClockifyApiException() {
        Fixture f = fixture();

        f.server.expect(requestTo(WORKSPACE_URL))
                .andRespond(MockRestResponseCreators.withServerError());

        assertThatThrownBy(() -> f.client.getWorkspace(fakeInstallation()))
                .isInstanceOf(ClockifyApiException.class)
                .hasMessageContaining("500");
        f.server.verify();
    }

    private static HttpHeaders retryAfterZero() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "0");
        return headers;
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClockifyBackendApiClient client = new ClockifyBackendApiClient(builder.build());
        return new Fixture(server, client);
    }

    private record Fixture(MockRestServiceServer server, ClockifyBackendApiClient client) {}

    private static InstallationRecord fakeInstallation() {
        Instant now = Instant.parse("2026-04-20T10:00:00Z");
        return new InstallationRecord(
                "ws-1",
                "addon-123",
                "addon-user",
                "owner-user",
                "installation-token",
                "https://api.clockify.me/api",
                "https://reports.api.clockify.me",
                Map.of(),
                AddonStatus.ACTIVE,
                true,
                "ENFORCE",
                "MONTHLY",
                now,
                now);
    }
}

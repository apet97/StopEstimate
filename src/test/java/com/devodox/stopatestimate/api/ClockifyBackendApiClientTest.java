package com.devodox.stopatestimate.api;

import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClockifyBackendApiClientTest {

    @Test
    void getWorkspaceReturnsWorkspaceJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClockifyBackendApiClient client = new ClockifyBackendApiClient(builder.build());

        server.expect(requestTo("https://api.clockify.me/api/v1/workspaces/ws-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Addon-Token", "installation-token"))
                .andRespond(withSuccess("""
                        {"id":"ws-1","name":"Workspace","timeZone":"Europe/Belgrade"}
                        """, MediaType.APPLICATION_JSON));

        JsonObject workspace = client.getWorkspace(fakeInstallation());

        assertThat(workspace.get("timeZone").getAsString()).isEqualTo("Europe/Belgrade");
        server.verify();
    }

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

package com.devodox.stopatestimate.api;

import com.devodox.stopatestimate.model.InstallationRecord;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class ClockifyReportsApiClient {
    private static final Logger log = LoggerFactory.getLogger(ClockifyReportsApiClient.class);

    private final RestClient restClient;
    private final Gson gson = new Gson();

    public ClockifyReportsApiClient(@Qualifier("clockifyReportsRestClient") RestClient restClient) {
        // Single RestClient per bean. RES-03: 45s read timeout tuned for the summary/detailed
        // reports endpoints which return single large payloads on busy workspaces.
        this.restClient = restClient;
    }

    public JsonObject generateSummaryReport(InstallationRecord installation, JsonObject filter) {
        return postJson(
                installation.reportsUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/reports/summary",
                installation.installationToken(),
                filter);
    }

    public JsonObject generateExpenseReport(InstallationRecord installation, JsonObject filter) {
        return postJson(
                installation.reportsUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/reports/expenses/detailed",
                installation.installationToken(),
                filter);
    }

    private JsonObject postJson(String reportsUrl, String path, String addonToken, JsonObject body) {
        String url = ClockifyApiUrls.join(ClockifyApiUrls.trimTrailingSlash(reportsUrl), path);
        String requestBodyStr = gson.toJson(body == null ? new JsonObject() : body);
        if (log.isTraceEnabled()) {
            log.trace("reports POST path={} bytes={}", path, requestBodyStr.length());
        }
        try {
            String responseBody = restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Addon-Token", addonToken)
                    .body(requestBodyStr)
                    .retrieve()
                    .body(String.class);

            if (log.isTraceEnabled()) {
                log.trace("reports response path={} bytes={}", path, responseBody == null ? 0 : responseBody.length());
            }

            if (responseBody == null || responseBody.isBlank()) {
                return new JsonObject();
            }

            JsonElement element = JsonParser.parseString(responseBody);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RestClientResponseException e) {
            // Never log the response body — reports contain workspace financial and time data.
            int code = e.getStatusCode().value();
            log.warn("Clockify reports call failed path={} status={}", path, code);
            // RES-02: differentiate status codes so callers can react correctly instead of retrying
            // permanent failures or aborting transient ones. 429 is transient (wait for next tick);
            // 401/403 are auth/permission failures that will not recover without user action.
            if (code == 429) {
                throw new ClockifyApiException("Reports rate limited (429); will retry on next tick", e);
            }
            if (code == 401) {
                throw new com.devodox.stopatestimate.service.ClockifyRequestAuthException(
                        "Reports token rejected", e);
            }
            if (code == 403) {
                throw new com.devodox.stopatestimate.service.ClockifyBackendForbiddenException(
                        "Reports forbade the request", e);
            }
            throw new ClockifyApiException("Reports call failed with " + code, e);
        } catch (RuntimeException e) {
            throw new ClockifyApiException("Clockify reports call failed", e);
        }
    }
}

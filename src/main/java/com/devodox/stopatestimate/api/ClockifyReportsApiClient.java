package com.devodox.stopatestimate.api;

import com.devodox.stopatestimate.model.InstallationRecord;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class ClockifyReportsApiClient {
    private static final Logger log = LoggerFactory.getLogger(ClockifyReportsApiClient.class);

    private final RestClient.Builder restClientBuilder;
    private final Gson gson = new Gson();

    public ClockifyReportsApiClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
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
            String responseBody = restClientBuilder.build()
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
            log.warn("Clockify reports call failed path={} status={}", path, e.getStatusCode().value());
            throw new ClockifyApiException("Clockify reports call failed with status " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw new ClockifyApiException("Clockify reports call failed", e);
        }
    }
}

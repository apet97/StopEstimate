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
            return doPostAndParse(url, addonToken, requestBodyStr, path);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                long sleepMs = ClockifyBackendApiClient.retryAfterMillis(e);
                log.info("Clockify reports returned 429 for {}; retrying once after {}ms", path, sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw classifyReports(e, path);
                }
                try {
                    return doPostAndParse(url, addonToken, requestBodyStr, path);
                } catch (RestClientResponseException retryEx) {
                    throw classifyReports(retryEx, path);
                } catch (RuntimeException retryEx) {
                    throw new ClockifyApiException("Clockify reports call failed", retryEx);
                }
            }
            throw classifyReports(e, path);
        } catch (RuntimeException e) {
            throw new ClockifyApiException("Clockify reports call failed", e);
        }
    }

    private JsonObject doPostAndParse(String url, String addonToken, String requestBodyStr, String path) {
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
    }

    private static RuntimeException classifyReports(RestClientResponseException e, String path) {
        // Never log the response body — reports contain workspace financial and time data.
        int code = e.getStatusCode().value();
        log.warn("Clockify reports call failed path={} status={}", path, code);
        // RES-02: differentiate status codes. One bounded Retry-After retry was already attempted
        // for 429 above; a second 429 defers to the scheduler. 401/403 are non-retryable.
        if (code == 429) {
            return new ClockifyApiException("Reports rate limited (429) after one retry; deferring to scheduler", e);
        }
        if (code == 401) {
            return new com.devodox.stopatestimate.service.ClockifyRequestAuthException(
                    "Reports token rejected", e);
        }
        if (code == 403) {
            return new com.devodox.stopatestimate.service.ClockifyBackendForbiddenException(
                    "Reports forbade the request", e);
        }
        return new ClockifyApiException("Reports call failed with " + code, e);
    }
}

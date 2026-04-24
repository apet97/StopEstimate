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
        //
        // B3: call sites pass a path TEMPLATE ("/v1/workspaces/{ws}/reports/summary") plus
        // path-vars so Micrometer records the template rather than the resolved URL.
        this.restClient = restClient;
    }

    public JsonObject generateSummaryReport(InstallationRecord installation, JsonObject filter) {
        return postJson(
                installation.reportsUrl(),
                "/v1/workspaces/{ws}/reports/summary",
                installation.installationToken(),
                filter,
                installation.workspaceId());
    }

    public JsonObject generateExpenseReport(InstallationRecord installation, JsonObject filter) {
        return postJson(
                installation.reportsUrl(),
                "/v1/workspaces/{ws}/reports/expenses/detailed",
                installation.installationToken(),
                filter,
                installation.workspaceId());
    }

    private JsonObject postJson(
            String reportsUrl, String pathTemplate, String addonToken, JsonObject body, Object... pathVars) {
        String fullTemplate = ClockifyApiUrls.join(ClockifyApiUrls.trimTrailingSlash(reportsUrl), pathTemplate);
        String requestBodyStr = gson.toJson(body == null ? new JsonObject() : body);
        if (log.isTraceEnabled()) {
            log.trace("reports POST path={} bytes={}", pathTemplate, requestBodyStr.length());
        }
        try {
            return doPostAndParse(fullTemplate, pathVars, addonToken, requestBodyStr, pathTemplate);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                long sleepMs = ClockifyBackendApiClient.retryAfterMillis(e);
                log.info("Clockify reports returned 429 for {}; retrying once after {}ms", pathTemplate, sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw classifyReports(e, pathTemplate);
                }
                try {
                    return doPostAndParse(fullTemplate, pathVars, addonToken, requestBodyStr, pathTemplate);
                } catch (RestClientResponseException retryEx) {
                    throw classifyReports(retryEx, pathTemplate);
                } catch (RuntimeException retryEx) {
                    throw new ClockifyApiException("Clockify reports call failed", retryEx);
                }
            }
            throw classifyReports(e, pathTemplate);
        } catch (RuntimeException e) {
            throw new ClockifyApiException("Clockify reports call failed", e);
        }
    }

    private JsonObject doPostAndParse(
            String fullTemplate, Object[] pathVars, String addonToken, String requestBodyStr, String pathTemplate) {
        String responseBody = restClient
                .post()
                .uri(fullTemplate, pathVars)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Addon-Token", addonToken)
                .body(requestBodyStr)
                .retrieve()
                .body(String.class);

        if (log.isTraceEnabled()) {
            log.trace("reports response path={} bytes={}", pathTemplate, responseBody == null ? 0 : responseBody.length());
        }

        if (responseBody == null || responseBody.isBlank()) {
            return new JsonObject();
        }

        JsonElement element = JsonParser.parseString(responseBody);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static RuntimeException classifyReports(RestClientResponseException e, String path) {
        // Never log the response body — reports contain workspace financial and time data.
        log.warn("Clockify reports call failed path={} status={}", path, e.getStatusCode().value());
        return ClockifyHttpClassifier.classify(e, "Clockify reports");
    }
}

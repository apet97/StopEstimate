package com.devodox.stopatestimate.api;

import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectMemberAccess;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClockifyBackendApiClient {

    /** Hard cap on paginated loops so a stuck pagination never OOMs the JVM. */
    private static final int MAX_PAGES = 1000;

    private final RestClient.Builder restClientBuilder;
    private final Gson gson = new Gson();
    private RestClient restClient;

    public ClockifyBackendApiClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @PostConstruct
    void init() {
        // Build a single RestClient per service instance. The underlying JDK HttpClient pools
        // connections across requests; rebuilding per call (as this code did previously) recreated
        // sockets on every cron tick and leaked ephemeral ports under load.
        this.restClient = restClientBuilder.build();
    }

    public JsonObject getProject(InstallationRecord installation, String projectId) {
        return getObject(
                installation.backendUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/projects/" + projectId,
                installation.installationToken());
    }

    public List<JsonObject> listInProgressTimeEntries(InstallationRecord installation) {
        return listPagedArray(
                installation.backendUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/time-entries/status/in-progress",
                installation.installationToken(),
                1000);
    }

    public void stopRunningTimer(InstallationRecord installation, String userId, String endIsoTimestamp) {
        JsonObject body = new JsonObject();
        body.addProperty("end", endIsoTimestamp);
        patchJson(
                installation.backendUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/user/" + userId + "/time-entries",
                installation.installationToken(),
                body);
    }

    public void updateProjectVisibility(InstallationRecord installation, String projectId, boolean isPublic) {
        // Clockify exposes only PUT on /v1/workspaces/{ws}/projects/{id}, and its request schema
        // (UpdateProjectRequest) mirrors a full-resource update: unspecified fields may be
        // overwritten with defaults. Fetch the current project and echo preserved fields back
        // alongside the flipped isPublic.
        JsonObject current = getProject(installation, projectId);
        JsonObject body = preservableProjectFields(current);
        body.addProperty("isPublic", isPublic);
        putJson(
                installation.backendUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/projects/" + projectId,
                installation.installationToken(),
                body);
    }

    private static JsonObject preservableProjectFields(JsonObject source) {
        JsonObject body = new JsonObject();
        if (source == null) {
            return body;
        }
        copyScalar(source, body, "name");
        copyScalar(source, body, "note");
        copyScalar(source, body, "color");
        copyScalar(source, body, "archived");
        copyScalar(source, body, "billable");
        copyScalar(source, body, "clientId");
        copyRate(source, body, "hourlyRate");
        copyRate(source, body, "costRate");
        return body;
    }

    private static void copyScalar(JsonObject src, JsonObject dst, String key) {
        JsonElement value = src.get(key);
        if (value == null || value.isJsonNull()) {
            return;
        }
        dst.add(key, value);
    }

    private static void copyRate(JsonObject src, JsonObject dst, String key) {
        JsonElement raw = src.get(key);
        if (raw == null || !raw.isJsonObject()) {
            return;
        }
        JsonObject rate = raw.getAsJsonObject();
        JsonElement amount = rate.get("amount");
        if (amount == null || amount.isJsonNull()) {
            return;
        }
        JsonObject out = new JsonObject();
        out.add("amount", amount);
        // Preserve currency — dropping it reverts per-user rates to workspace default currency on unlock.
        JsonElement currency = rate.get("currency");
        if (currency != null && !currency.isJsonNull()) {
            out.add("currency", currency);
        }
        dst.add(key, out);
    }

    public void updateProjectMemberships(
            InstallationRecord installation,
            String projectId,
            List<ProjectMemberAccess> memberships,
            List<String> userGroupIds) {
        JsonObject body = new JsonObject();
        JsonArray membersArray = new JsonArray();
        for (ProjectMemberAccess access : memberships) {
            JsonObject member = new JsonObject();
            member.addProperty("userId", access.userId());
            if (access.hourlyRate() != null && access.hourlyRate().present()) {
                member.add("hourlyRate", rateToJson(access.hourlyRate()));
            }
            if (access.costRate() != null && access.costRate().present()) {
                member.add("costRate", rateToJson(access.costRate()));
            }
            membersArray.add(member);
        }
        body.add("memberships", membersArray);

        if (userGroupIds != null) {
            JsonObject userGroups = new JsonObject();
            userGroups.addProperty("contains", "CONTAINS");
            userGroups.addProperty("status", "ALL");
            JsonArray ids = new JsonArray();
            for (String userGroupId : userGroupIds) {
                ids.add(userGroupId);
            }
            userGroups.add("ids", ids);
            body.add("userGroups", userGroups);
        }

        patchJson(
                installation.backendUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/projects/" + projectId + "/memberships",
                installation.installationToken(),
                body);
    }

    private static JsonObject rateToJson(com.devodox.stopatestimate.model.RateInfo rate) {
        JsonObject json = new JsonObject();
        json.addProperty("amount", rate.amount());
        if (rate.currency() != null && !rate.currency().isBlank()) {
            json.addProperty("currency", rate.currency());
        }
        return json;
    }

    public List<JsonObject> filterUsers(
            InstallationRecord installation,
            List<String> roles,
            String projectId,
            String memberships) {
        List<JsonObject> results = new ArrayList<>();
        int page = 1;
        int pageSize = 200;
        while (true) {
            JsonObject body = new JsonObject();
            body.addProperty("includeRoles", true);
            body.addProperty("status", "ACTIVE");
            body.addProperty("page", page);
            body.addProperty("pageSize", pageSize);
            body.addProperty("memberships", memberships == null ? "NONE" : memberships);
            if (projectId != null && !projectId.isBlank()) {
                body.addProperty("projectId", projectId);
            }
            if (roles != null && !roles.isEmpty()) {
                JsonArray rolesArray = new JsonArray();
                for (String role : roles) {
                    rolesArray.add(role);
                }
                body.add("roles", rolesArray);
            }

            String response = exchangeJson(
                    "POST",
                    installation.backendUrl(),
                    "/v1/workspaces/" + installation.workspaceId() + "/users/info",
                    installation.installationToken(),
                    gson.toJson(body));
            List<JsonObject> pageItems = parseArray(response);
            results.addAll(pageItems);
            if (pageItems.size() < pageSize) {
                return results;
            }
            page++;
            if (page > MAX_PAGES) {
                throw new ClockifyApiException("Clockify filterUsers pagination exceeded " + MAX_PAGES + " pages");
            }
        }
    }

    public List<JsonObject> listProjects(InstallationRecord installation) {
        return listPagedArray(
                installation.backendUrl(),
                "/v1/workspaces/" + installation.workspaceId() + "/projects",
                installation.installationToken(),
                100);
    }

    private List<JsonObject> listPagedArray(String baseUrl, String path, String addonToken, int pageSize) {
        List<JsonObject> results = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = UriComponentsBuilder
                    .fromUriString(ClockifyApiUrls.join(baseUrl, path))
                    .queryParam("page", page)
                    .queryParam("page-size", pageSize)
                    .build()
                    .toUriString();
            List<JsonObject> pageItems = parseArray(exchange("GET", url, addonToken, null));
            results.addAll(pageItems);
            if (pageItems.size() < pageSize) {
                return results;
            }
            page++;
            if (page > MAX_PAGES) {
                throw new ClockifyApiException("Clockify " + path + " pagination exceeded " + MAX_PAGES + " pages");
            }
        }
    }

    private JsonObject getObject(String baseUrl, String path, String addonToken) {
        String response = exchange("GET", ClockifyApiUrls.join(baseUrl, path), addonToken, null);
        if (response == null || response.isBlank()) {
            return new JsonObject();
        }
        JsonElement element = JsonParser.parseString(response);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private void putJson(String baseUrl, String path, String addonToken, JsonObject body) {
        exchangeJson("PUT", baseUrl, path, addonToken, gson.toJson(body));
    }

    private void patchJson(String baseUrl, String path, String addonToken, JsonObject body) {
        exchangeJson("PATCH", baseUrl, path, addonToken, gson.toJson(body));
    }

    private String exchangeJson(String method, String baseUrl, String path, String addonToken, String body) {
        return exchange(method, ClockifyApiUrls.join(baseUrl, path), addonToken, body);
    }

    private String exchange(String method, String url, String addonToken, String body) {
        return exchangeOnce(method, url, addonToken, body, true);
    }

    private String exchangeOnce(String method, String url, String addonToken, String body, boolean allowRetry) {
        try {
            return switch (method) {
                case "GET" -> restClient.get()
                        .uri(url)
                        .header("X-Addon-Token", addonToken)
                        .retrieve()
                        .body(String.class);
                case "PUT" -> restClient.put()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Token", addonToken)
                        .body(body == null ? "{}" : body)
                        .retrieve()
                        .body(String.class);
                case "PATCH" -> restClient.patch()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Token", addonToken)
                        .body(body == null ? "{}" : body)
                        .retrieve()
                        .body(String.class);
                case "POST" -> restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Addon-Token", addonToken)
                        .body(body == null ? "{}" : body)
                        .retrieve()
                        .body(String.class);
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            };
        } catch (RestClientResponseException e) {
            if (allowRetry && e.getStatusCode().value() == 429) {
                // Single Retry-After-honoring retry. Cap the sleep so a hostile Retry-After
                // can't park the thread for minutes; fail through to classify() if we still
                // get rate-limited on the retry.
                sleepQuietly(parseRetryAfterMillis(e));
                return exchangeOnce(method, url, addonToken, body, false);
            }
            throw classify(e);
        } catch (RuntimeException e) {
            throw new ClockifyApiException("Clockify backend call failed", e);
        }
    }

    private static long parseRetryAfterMillis(RestClientResponseException e) {
        org.springframework.http.HttpHeaders headers = e.getResponseHeaders();
        String retryAfter = headers == null ? null : headers.getFirst("Retry-After");
        long fallbackMs = 1_000L;
        long capMs = 10_000L;
        if (retryAfter == null || retryAfter.isBlank()) {
            return fallbackMs;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return Math.min(capMs, Math.max(0L, seconds) * 1_000L);
        } catch (NumberFormatException ignored) {
            return fallbackMs;
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException classify(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        int code = status.value();
        if (code == 401) {
            return new com.devodox.stopatestimate.service.ClockifyRequestAuthException(
                    "Clockify backend rejected the installation token", e);
        }
        if (code == 403) {
            // RES-08: distinguish a backend permission failure (addon lost scope / admin
            // revoked access) from a webhook-token mismatch so the exception handler can
            // emit a different error code.
            return new com.devodox.stopatestimate.service.ClockifyBackendForbiddenException(
                    "Clockify backend forbade the request", e);
        }
        return new ClockifyApiException("Clockify backend call failed with status " + status, e);
    }

    private List<JsonObject> parseArray(String body) {
        List<JsonObject> result = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return result;
        }

        JsonElement element = JsonParser.parseString(body);
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    result.add(item.getAsJsonObject());
                }
            }
        }
        return result;
    }
}

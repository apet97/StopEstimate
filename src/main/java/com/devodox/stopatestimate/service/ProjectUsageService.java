package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectMemberAccess;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.ProjectUsage;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.ResetWindow;
import com.devodox.stopatestimate.model.ResetWindowSchedule;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectUsageService {
    private static final DateTimeFormatter REPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final ClockifyBackendApiClient backendApiClient;
    private final ClockifyReportsApiClient reportsApiClient;
    private final ClockifyResetWindowPlanner resetWindowPlanner;

    public ProjectUsageService(
            ClockifyBackendApiClient backendApiClient,
            ClockifyReportsApiClient reportsApiClient,
            ClockifyResetWindowPlanner resetWindowPlanner) {
        this.backendApiClient = backendApiClient;
        this.reportsApiClient = reportsApiClient;
        this.resetWindowPlanner = resetWindowPlanner;
    }

    public ProjectState loadProjectState(InstallationRecord installation, String projectId) {
        JsonObject project = backendApiClient.getProject(installation, projectId);
        ProjectCaps caps = parseCaps(
                project, installation.defaultResetCadenceValue(), resolveZone(installation.timezone()));
        return new ProjectState(
                installation.workspaceId(),
                projectId,
                ClockifyJson.string(project, "name").orElse(projectId),
                ClockifyJson.bool(project, "public").orElse(false),
                parseMembers(ClockifyJson.array(project, "memberships")),
                parseUserGroupIds(project),
                parseRate(ClockifyJson.object(project, "hourlyRate")),
                parseRate(ClockifyJson.object(project, "costRate")),
                caps);
    }

    public ProjectUsage loadProjectUsage(InstallationRecord installation, ProjectState projectState, Instant now) {
        ResetWindow window = resetWindowPlanner.currentWindow(projectState.caps(), now);
        ZoneId zone = resolveZone(installation.timezone());
        JsonObject summary = reportsApiClient.generateSummaryReport(
                installation,
                summaryFilter(projectState.projectId(), window, now, zone));
        BigDecimal expenses = projectState.caps().budgetCapActive() && projectState.caps().includeExpenses()
                ? extractExpenseTotal(reportsApiClient.generateExpenseReport(
                        installation,
                        expenseFilter(projectState.projectId(), window, now, zone)))
                : BigDecimal.ZERO;
        return new ProjectUsage(
                window,
                extractSummaryTotalTime(summary),
                extractSummaryBillable(summary),
                expenses);
    }

    public List<RunningTimeEntry> loadRunningEntries(InstallationRecord installation, String projectId) {
        List<RunningTimeEntry> entries = new ArrayList<>();
        for (JsonObject entry : backendApiClient.listInProgressTimeEntries(installation)) {
            String currentProjectId = ClockifyJson.findFirstString(entry, "projectId").orElse(null);
            if (!projectId.equals(currentProjectId)) {
                continue;
            }
            entries.add(parseRunningEntry(installation.workspaceId(), currentProjectId, entry));
        }
        return entries;
    }

    /**
     * Workspace-scoped fan-in: one HTTP call to
     * {@code /v1/workspaces/{ws}/time-entries/status/in-progress} returns every running entry in
     * the workspace. Callers that need running entries for many projects in one pass (sidebar
     * summary) should use this instead of looping {@link #loadRunningEntries} per project —
     * otherwise the same workspace-wide payload is fetched N times and filtered in-memory.
     * <p>
     * Freshness is identical to {@link #loadRunningEntries}; this is purely a batching refactor.
     */
    public Map<String, List<RunningTimeEntry>> loadRunningEntriesByProject(InstallationRecord installation) {
        Map<String, List<RunningTimeEntry>> byProject = new LinkedHashMap<>();
        for (JsonObject entry : backendApiClient.listInProgressTimeEntries(installation)) {
            String projectId = ClockifyJson.findFirstString(entry, "projectId").orElse(null);
            if (projectId == null || projectId.isBlank()) {
                continue;
            }
            byProject
                    .computeIfAbsent(projectId, k -> new ArrayList<>())
                    .add(parseRunningEntry(installation.workspaceId(), projectId, entry));
        }
        return byProject;
    }

    private RunningTimeEntry parseRunningEntry(String workspaceId, String projectId, JsonObject entry) {
        Instant start = null;
        JsonObject timeInterval = ClockifyJson.object(entry, "timeInterval");
        if (timeInterval != null) {
            start = ClockifyJson.instant(timeInterval.get("start"));
        }
        if (start == null) {
            start = ClockifyJson.instant(entry.get("start"));
        }
        return new RunningTimeEntry(
                workspaceId,
                projectId,
                ClockifyJson.findFirstString(entry, "userId").orElse(null),
                ClockifyJson.findFirstString(entry, "id", "timeEntryId").orElse(null),
                start,
                ClockifyJson.bool(entry, "billable").orElse(false));
    }

    private ProjectCaps parseCaps(JsonObject project, String defaultResetCadence, ZoneId zone) {
        JsonObject timeEstimate = ClockifyJson.object(project, "timeEstimate");
        JsonObject budgetEstimate = ClockifyJson.object(project, "budgetEstimate");
        JsonObject estimateReset = ClockifyJson.object(project, "estimateReset");

        boolean timeCapActive = timeEstimate != null && ClockifyJson.bool(timeEstimate, "active").orElse(false);
        boolean budgetCapActive = budgetEstimate != null && ClockifyJson.bool(budgetEstimate, "active").orElse(false);
        ResetWindowSchedule schedule = resetWindowPlanner.deriveSchedule(
                defaultResetCadence,
                estimateReset,
                zone,
                timeEstimate == null ? null : ClockifyJson.string(timeEstimate, "resetOption").orElse(null),
                budgetEstimate == null ? null : ClockifyJson.string(budgetEstimate, "resetOption").orElse(null));

        return new ProjectCaps(
                timeCapActive,
                timeEstimate == null ? 0L : ClockifyJson.durationMillis(timeEstimate.get("estimate")),
                timeEstimate == null ? null : ClockifyJson.string(timeEstimate, "resetOption").orElse(null),
                timeEstimate != null && ClockifyJson.bool(timeEstimate, "includeNonBillable").orElse(false),
                budgetCapActive,
                budgetEstimate == null ? BigDecimal.ZERO : ClockifyJson.decimal(budgetEstimate.get("estimate")),
                budgetEstimate == null ? null : ClockifyJson.string(budgetEstimate, "resetOption").orElse(null),
                budgetEstimate != null && ClockifyJson.bool(budgetEstimate, "includeExpenses").orElse(false),
                schedule);
    }

    private List<ProjectMemberAccess> parseMembers(JsonArray memberships) {
        List<ProjectMemberAccess> members = new ArrayList<>();
        if (memberships == null) {
            return members;
        }
        for (JsonElement membership : memberships) {
            if (!membership.isJsonObject()) {
                continue;
            }
            JsonObject object = membership.getAsJsonObject();
            String userId = ClockifyJson.string(object, "userId").orElse(null);
            if (userId == null) {
                continue;
            }
            members.add(new ProjectMemberAccess(
                    userId,
                    parseRate(ClockifyJson.object(object, "hourlyRate")),
                    parseRate(ClockifyJson.object(object, "costRate")),
                    ClockifyJson.string(object, "membershipType").orElse("PROJECT"),
                    ClockifyJson.string(object, "membershipStatus").orElse("ACTIVE")));
        }
        return members;
    }

    private List<String> parseUserGroupIds(JsonObject project) {
        List<String> userGroupIds = new ArrayList<>();
        JsonArray groups = ClockifyJson.array(project, "userGroups");
        if (groups != null) {
            for (JsonElement group : groups) {
                if (group.isJsonPrimitive()) {
                    userGroupIds.add(group.getAsString());
                }
            }
            return userGroupIds;
        }
        JsonObject groupObject = ClockifyJson.object(project, "userGroups");
        if (groupObject != null) {
            JsonArray ids = ClockifyJson.array(groupObject, "ids");
            if (ids != null) {
                for (JsonElement id : ids) {
                    if (id.isJsonPrimitive()) {
                        userGroupIds.add(id.getAsString());
                    }
                }
            }
        }
        return userGroupIds;
    }

    private RateInfo parseRate(JsonObject object) {
        if (object == null) {
            return RateInfo.empty();
        }
        boolean hasAmount = object.has("amount");
        return new RateInfo(
                hasAmount ? ClockifyJson.decimal(object.get("amount")) : BigDecimal.ZERO,
                ClockifyJson.string(object, "currency").orElse(null),
                hasAmount);
    }

    // Package-private so ProjectUsageServiceTest can assert the emitted filter JSON does not
    // request Cost Analysis amounts (which would 400 on workspaces without that feature).
    JsonObject summaryFilter(String projectId, ResetWindow window, Instant now, ZoneId zone) {
        JsonObject filter = baseReportFilter(window, now, zone);
        JsonObject projects = new JsonObject();
        projects.addProperty("contains", "CONTAINS_ONLY");
        projects.addProperty("status", "ALL");
        JsonArray ids = new JsonArray();
        ids.add(projectId);
        projects.add("ids", ids);
        filter.add("projects", projects);

        // Requesting any amounts subset forces the workspace to have Cost Analysis enabled;
        // without it Clockify returns HTTP 400 code 501. We only need totalTime here — the
        // billable amount is derived from totals[].amounts[] only when the response includes
        // it, and budget usage falls back to expenses when not (see extractSummaryBillable).
        // Project budgetEstimate tracks billable amount, so we read EARNED/BILLED entries.
        JsonObject summaryFilter = new JsonObject();
        JsonArray groups = new JsonArray();
        groups.add("PROJECT");
        summaryFilter.add("groups", groups);
        filter.add("summaryFilter", summaryFilter);

        return filter;
    }

    private JsonObject expenseFilter(String projectId, ResetWindow window, Instant now, ZoneId zone) {
        JsonObject filter = baseReportFilter(window, now, zone);
        JsonObject projects = new JsonObject();
        projects.addProperty("contains", "CONTAINS_ONLY");
        projects.addProperty("status", "ALL");
        JsonArray ids = new JsonArray();
        ids.add(projectId);
        projects.add("ids", ids);
        filter.add("projects", projects);
        return filter;
    }

    private JsonObject baseReportFilter(ResetWindow window, Instant now, ZoneId zone) {
        ZoneId resolvedZone = zone == null ? ZoneOffset.UTC : zone;
        JsonObject filter = new JsonObject();
        filter.addProperty("dateRangeType", "ABSOLUTE");
        filter.addProperty("dateRangeStart", REPORT_TIMESTAMP.format(window.startInclusive()));
        Instant end = window.endExclusive() == null ? now : window.endExclusive();
        filter.addProperty("dateRangeEnd", REPORT_TIMESTAMP.format(end));
        filter.addProperty("exportType", "JSON");
        filter.addProperty("timeZone", ZoneOffset.UTC.equals(resolvedZone) ? "UTC" : resolvedZone.getId());
        return filter;
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException ignored) {
            return ZoneOffset.UTC;
        }
    }

    // Package-private so ProjectUsageServiceTest can assert the parsing contract directly without
    // standing up the full Reports client pipeline.
    long extractSummaryTotalTime(JsonObject summary) {
        // Clockify summary response shape: {"totals":[{"totalTime":11962, "entriesCount":7, ...}], ...}
        // totalTime is in seconds; convert to milliseconds to match timeLimitMs.
        long totalSeconds = 0L;
        JsonArray totals = ClockifyJson.array(summary, "totals");
        if (totals != null) {
            for (JsonElement entry : totals) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject obj = entry.getAsJsonObject();
                if (obj.has("totalTime") && !obj.get("totalTime").isJsonNull()) {
                    JsonElement totalTime = obj.get("totalTime");
                    if (totalTime.isJsonPrimitive() && totalTime.getAsJsonPrimitive().isNumber()) {
                        totalSeconds += totalTime.getAsNumber().longValue();
                    } else {
                        // ISO-8601 style or other non-numeric representation.
                        totalSeconds += ClockifyJson.durationMillis(totalTime) / 1000L;
                    }
                } else if (obj.has("totalDuration") && !obj.get("totalDuration").isJsonNull()) {
                    // Fallback for ISO-8601 durations if ever returned.
                    totalSeconds += ClockifyJson.durationMillis(obj.get("totalDuration")) / 1000L;
                }
            }
        }
        return totalSeconds * 1000L;
    }

    BigDecimal extractSummaryBillable(JsonObject summary) {
        // Clockify summary response: totals[].amounts[] = [{type: "EARNED", value: 3.32, ...}].
        // Project budgetEstimate tracks billable amount (hourlyRate × billable hours, plus
        // expenses when includeExpenses is set), not internal cost. We read EARNED/BILLED
        // amount-type entries — Clockify uses both labels depending on workspace features.
        // We never request amounts on the filter (that requires Cost Analysis and 400s with
        // code 501 on workspaces without it); we just consume whatever is present. When the
        // amounts array is absent we fall back to top-level totalBillable / totalAmount, then
        // zero. COST entries are explicitly ignored because they reflect internal cost and
        // are unrelated to budget cap math.
        BigDecimal total = BigDecimal.ZERO;
        JsonArray totals = ClockifyJson.array(summary, "totals");
        if (totals != null) {
            for (JsonElement entry : totals) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject obj = entry.getAsJsonObject();
                BigDecimal billable = pickBillableAmount(obj);
                if (billable != null) {
                    total = total.add(billable);
                }
            }
        }
        return total;
    }

    private BigDecimal pickBillableAmount(JsonObject totalsEntry) {
        JsonArray amounts = ClockifyJson.array(totalsEntry, "amounts");
        if (amounts != null) {
            for (JsonElement element : amounts) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject a = element.getAsJsonObject();
                String type = ClockifyJson.string(a, "type").orElse("");
                if (("EARNED".equalsIgnoreCase(type) || "BILLED".equalsIgnoreCase(type))
                        && a.has("value") && !a.get("value").isJsonNull()) {
                    return ClockifyJson.decimal(a.get("value"));
                }
            }
        }
        if (totalsEntry.has("totalBillable") && !totalsEntry.get("totalBillable").isJsonNull()) {
            return ClockifyJson.decimal(totalsEntry.get("totalBillable"));
        }
        if (totalsEntry.has("totalAmount") && !totalsEntry.get("totalAmount").isJsonNull()) {
            return ClockifyJson.decimal(totalsEntry.get("totalAmount"));
        }
        return null;
    }

    private BigDecimal extractExpenseTotal(JsonObject expenses) {
        JsonObject totals = ClockifyJson.object(expenses, "totals");
        if (totals == null) {
            return BigDecimal.ZERO;
        }
        if (totals.has("totalAmount")) {
            return ClockifyJson.decimal(totals.get("totalAmount"));
        }
        return BigDecimal.ZERO;
    }
}

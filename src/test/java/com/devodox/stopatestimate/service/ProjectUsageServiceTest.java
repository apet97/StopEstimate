package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ResetWindow;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the summary-report body: the service must NOT request {@code amounts} /
 * {@code amountShown} because that forces Clockify workspaces to have Cost Analysis enabled, and
 * the request 400s with code 501 on workspaces without it. The service now derives cost from
 * {@code totals[].amounts[]} opportunistically when Clockify returns it, but never demands it.
 */
class ProjectUsageServiceTest {

    private ProjectUsageService service;
    private ClockifyBackendApiClient backendApiClient;

    @BeforeEach
    void setUp() {
        backendApiClient = Mockito.mock(ClockifyBackendApiClient.class);
        service = new ProjectUsageService(
                backendApiClient,
                Mockito.mock(ClockifyReportsApiClient.class),
                Mockito.mock(ClockifyResetWindowPlanner.class));
    }

    @Test
    void summaryFilterBodyDoesNotRequestCostAnalysis() {
        ResetWindow window = new ResetWindow(
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-18T00:00:00Z"),
                null);

        JsonObject body = service.summaryFilter(
                "project-42",
                window,
                Instant.parse("2026-04-18T10:00:00Z"),
                ZoneId.of("UTC"));

        assertThat(body.has("amounts"))
                .as("summary filter must not declare amounts — workspaces without Cost Analysis reject it")
                .isFalse();
        assertThat(body.has("amountShown"))
                .as("summary filter must not declare amountShown — workspaces without Cost Analysis reject it")
                .isFalse();

        // Sanity: body still contains the required range + projects scope.
        assertThat(body.has("dateRangeStart")).isTrue();
        assertThat(body.has("dateRangeEnd")).isTrue();
        assertThat(body.has("projects")).isTrue();
        assertThat(body.getAsJsonObject("projects").getAsJsonArray("ids").get(0).getAsString())
                .isEqualTo("project-42");
        assertThat(body.has("summaryFilter")).isTrue();
    }

    @Test
    void summaryFilterUsesInstallationTimezoneWhenPresent() {
        ResetWindow window = new ResetWindow(
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-18T00:00:00Z"),
                null);

        JsonObject body = service.summaryFilter(
                "project-42",
                window,
                Instant.parse("2026-04-18T10:00:00Z"),
                ZoneId.of("Europe/Belgrade"));

        assertThat(body.get("timeZone").getAsString()).isEqualTo("Europe/Belgrade");
    }

    @Test
    void summaryFilterFallsBackToUtcWhenTimezoneNull() {
        ResetWindow window = new ResetWindow(
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-18T00:00:00Z"),
                null);

        JsonObject body = service.summaryFilter(
                "project-42",
                window,
                Instant.parse("2026-04-18T10:00:00Z"),
                null);

        assertThat(body.get("timeZone").getAsString()).isEqualTo("UTC");
    }

    @Test
    void summaryExtractsEarnedAmountType() {
        JsonObject summary = summaryWithAmount("EARNED", "42");
        assertThat(service.extractSummaryBillable(summary)).isEqualByComparingTo("42");
    }

    @Test
    void summaryExtractsBilledAmountTypeCaseInsensitive() {
        JsonObject summary = summaryWithAmount("billed", "7");
        assertThat(service.extractSummaryBillable(summary)).isEqualByComparingTo("7");
    }

    @Test
    void summaryIgnoresCostAmountTypeForBudget() {
        // Project budgetEstimate is billable, not cost. A summary that returns only COST entries
        // (e.g. workspaces with Cost Analysis enabled but no billable rates) must contribute
        // nothing to budget usage — otherwise the addon would lock projects against an
        // unrelated metric.
        JsonObject summary = summaryWithAmount("COST", "99");
        assertThat(service.extractSummaryBillable(summary)).isEqualByComparingTo("0");
    }

    @Test
    void summaryFallsBackToTotalBillableWhenAmountsArrayAbsent() {
        JsonObject totalsEntry = new JsonObject();
        totalsEntry.addProperty("totalBillable", new BigDecimal("15"));
        JsonArray totals = new JsonArray();
        totals.add(totalsEntry);
        JsonObject summary = new JsonObject();
        summary.add("totals", totals);

        assertThat(service.extractSummaryBillable(summary)).isEqualByComparingTo("15");
    }

    // ----- TEST-12: extractSummaryTotalTime numeric, ISO-8601, multi-entry, and empty cases -----

    @Test
    void totalTimeNumericSecondsIsReturnedAsMilliseconds() {
        // Clockify reports totalTime in seconds; 3600s = 1h = 3_600_000ms.
        JsonObject summary = summaryWithTotalTime(3600);

        assertThat(service.extractSummaryTotalTime(summary)).isEqualTo(3_600_000L);
    }

    @Test
    void totalTimeIso8601StringIsParsedAsDuration() {
        // PT1H = 3,600,000 ms; the code round-trips through durationMillis and divides by 1000.
        JsonObject summary = summaryWithTotalTime("PT1H");

        assertThat(service.extractSummaryTotalTime(summary)).isEqualTo(3_600_000L);
    }

    @Test
    void totalTimeSumsAcrossMultipleTotalsEntries() {
        JsonArray totals = new JsonArray();
        totals.add(totalsObj(60));   // 60s
        totals.add(totalsObj(90));   // 90s
        JsonObject summary = new JsonObject();
        summary.add("totals", totals);

        // 150s = 150_000 ms.
        assertThat(service.extractSummaryTotalTime(summary)).isEqualTo(150_000L);
    }

    @Test
    void missingOrEmptyTotalsYieldsZero() {
        assertThat(service.extractSummaryTotalTime(new JsonObject())).isZero();

        JsonObject withEmptyTotals = new JsonObject();
        withEmptyTotals.add("totals", new JsonArray());
        assertThat(service.extractSummaryTotalTime(withEmptyTotals)).isZero();
    }

    private static JsonObject summaryWithTotalTime(long seconds) {
        JsonArray totals = new JsonArray();
        totals.add(totalsObj(seconds));
        JsonObject summary = new JsonObject();
        summary.add("totals", totals);
        return summary;
    }

    private static JsonObject summaryWithTotalTime(String iso8601) {
        JsonObject totalsEntry = new JsonObject();
        totalsEntry.addProperty("totalTime", iso8601);
        JsonArray totals = new JsonArray();
        totals.add(totalsEntry);
        JsonObject summary = new JsonObject();
        summary.add("totals", totals);
        return summary;
    }

    private static JsonObject totalsObj(long seconds) {
        JsonObject entry = new JsonObject();
        entry.addProperty("totalTime", seconds);
        return entry;
    }

    // ----- loadRunningEntriesByProject: batch workspace-wide running entries -----

    @Test
    void loadRunningEntriesByProject_makesExactlyOneBackendCall() {
        // Regression guard for the N+1 fix: previously callers looped loadRunningEntries per
        // project, which fetched the same workspace-wide list once per project and filtered in
        // memory. The batch method must hit the backend exactly once regardless of how many
        // projects the caller needs.
        when(backendApiClient.listInProgressTimeEntries(Mockito.any()))
                .thenReturn(List.of(
                        runningEntryJson("project-A", "user-1", "te-1", true),
                        runningEntryJson("project-B", "user-2", "te-2", false),
                        runningEntryJson("project-A", "user-3", "te-3", true)));

        Map<String, List<RunningTimeEntry>> byProject = service.loadRunningEntriesByProject(fakeInstallation());

        verify(backendApiClient, times(1)).listInProgressTimeEntries(Mockito.any());
        assertThat(byProject).containsOnlyKeys("project-A", "project-B");
    }

    @Test
    void loadRunningEntriesByProject_routesEntriesToCorrectProject() {
        // Correctness check: entries for project A must not be attributed to project B even
        // when both projects have concurrent in-progress timers. Verifies the grouping is
        // keyed on the entry's own projectId, not any ambient state.
        when(backendApiClient.listInProgressTimeEntries(Mockito.any()))
                .thenReturn(List.of(
                        runningEntryJson("project-A", "user-1", "te-A1", true),
                        runningEntryJson("project-B", "user-2", "te-B1", false),
                        runningEntryJson("project-A", "user-3", "te-A2", false)));

        Map<String, List<RunningTimeEntry>> byProject = service.loadRunningEntriesByProject(fakeInstallation());

        assertThat(byProject.get("project-A"))
                .extracting(RunningTimeEntry::timeEntryId)
                .containsExactly("te-A1", "te-A2");
        assertThat(byProject.get("project-B"))
                .extracting(RunningTimeEntry::timeEntryId)
                .containsExactly("te-B1");
        // Sanity: billable flag is parsed per entry, not per project.
        assertThat(byProject.get("project-A").get(0).billable()).isTrue();
        assertThat(byProject.get("project-A").get(1).billable()).isFalse();
    }

    @Test
    void loadRunningEntriesByProject_dropsEntriesWithoutProjectId() {
        when(backendApiClient.listInProgressTimeEntries(Mockito.any()))
                .thenReturn(List.of(
                        runningEntryJson(null, "user-1", "te-orphan", false),
                        runningEntryJson("project-A", "user-2", "te-A1", true)));

        Map<String, List<RunningTimeEntry>> byProject = service.loadRunningEntriesByProject(fakeInstallation());

        assertThat(byProject).containsOnlyKeys("project-A");
    }

    private static JsonObject runningEntryJson(String projectId, String userId, String id, boolean billable) {
        JsonObject obj = new JsonObject();
        if (projectId != null) {
            obj.addProperty("projectId", projectId);
        }
        obj.addProperty("userId", userId);
        obj.addProperty("id", id);
        obj.addProperty("billable", billable);
        JsonObject interval = new JsonObject();
        interval.addProperty("start", "2026-04-18T09:00:00Z");
        obj.add("timeInterval", interval);
        return obj;
    }

    private static InstallationRecord fakeInstallation() {
        Instant now = Instant.parse("2026-04-18T10:00:00Z");
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

    private static JsonObject summaryWithAmount(String type, String value) {
        JsonObject amount = new JsonObject();
        amount.addProperty("type", type);
        amount.addProperty("value", new BigDecimal(value));
        JsonArray amounts = new JsonArray();
        amounts.add(amount);
        JsonObject totalsEntry = new JsonObject();
        totalsEntry.add("amounts", amounts);
        JsonArray totals = new JsonArray();
        totals.add(totalsEntry);
        JsonObject summary = new JsonObject();
        summary.add("totals", totals);
        return summary;
    }
}

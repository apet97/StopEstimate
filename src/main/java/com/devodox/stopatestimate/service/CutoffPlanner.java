package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.GuardReason;
import com.devodox.stopatestimate.model.ProjectCaps;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.ProjectUsage;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.RunningTimeEntry;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure cutoff/lock decision engine extracted from EstimateGuardService.
 * No collaborators, no clock — every input is supplied by the caller. The chain is:
 * <pre>
 *   assess → exceededReason | cutoffPlan → immediateCutoff (NEW_TIMER_STARTED special case)
 * </pre>
 * The returned {@link Assessment} is consumed by EstimateGuardService to decide which
 * side effects to apply (stop, lock, schedule cutoff, no-op).
 */
@Service
public class CutoffPlanner {

    public Assessment assess(
            ProjectState projectState,
            ProjectUsage usage,
            List<RunningTimeEntry> runningEntries,
            Instant now,
            String source,
            JsonObject payload) {
        ProjectCaps caps = projectState.caps();
        if (caps == null || !caps.hasActiveCaps()) {
            return new Assessment(GuardReason.NO_ACTIVE_CAPS, null, false, GuardReason.NO_ACTIVE_CAPS);
        }
        GuardReason exceeded = exceededReason(caps, usage);
        if (exceeded != null) {
            return new Assessment(exceeded, immediateCutoff(source, payload, projectState, usage, runningEntries, now), true, exceeded);
        }
        CutoffPlan plan = cutoffPlan(projectState, usage, runningEntries, now);
        return new Assessment(GuardReason.BELOW_CAPS, plan.cutoffAt(), plan.lockNow(), plan.reason());
    }

    private GuardReason exceededReason(ProjectCaps caps, ProjectUsage usage) {
        if (caps.timeCapActive() && usage.trackedTimeMs() >= caps.timeLimitMs()) {
            return GuardReason.TIME_CAP_REACHED;
        }
        if (caps.budgetCapActive() && usage.budgetUsage(caps.includeExpenses()).compareTo(caps.budgetLimit()) >= 0) {
            return GuardReason.BUDGET_CAP_REACHED;
        }
        return null;
    }

    private Instant immediateCutoff(
            String source,
            JsonObject payload,
            ProjectState projectState,
            ProjectUsage usage,
            List<RunningTimeEntry> runningEntries,
            Instant now) {
        if (!source.contains("NEW_TIMER_STARTED") || payload == null || runningEntries.size() != 1) {
            return now;
        }
        RunningTimeEntry entry = runningEntries.get(0);
        if (entry.start() == null) {
            return now;
        }

        long elapsedMs = Math.max(0L, now.toEpochMilli() - entry.start().toEpochMilli());
        long trackedBeforeCurrent = Math.max(0L, usage.trackedTimeMs() - elapsedMs);
        BigDecimal budgetBeforeCurrent = usage.budgetUsage(projectState.caps().includeExpenses())
                .subtract(elapsedBillable(projectState, entry, elapsedMs))
                .max(BigDecimal.ZERO);

        if ((projectState.caps().timeCapActive() && trackedBeforeCurrent >= projectState.caps().timeLimitMs())
                || (projectState.caps().budgetCapActive()
                && budgetBeforeCurrent.compareTo(projectState.caps().budgetLimit()) >= 0)) {
            return entry.start();
        }
        return now;
    }

    private CutoffPlan cutoffPlan(ProjectState projectState, ProjectUsage usage, List<RunningTimeEntry> runningEntries, Instant now) {
        if (runningEntries.isEmpty()) {
            return new CutoffPlan(null, false, GuardReason.BELOW_CAPS);
        }

        ProjectCaps caps = projectState.caps();
        // Pair each candidate with the branch that produced it so the shared "already in the
        // past" fallback below returns the correct GuardReason instead of always TIME_CAP_REACHED.
        List<CutoffCandidate> candidates = new ArrayList<>();

        if (caps.timeCapActive()) {
            // Clockify Reports summary only counts completed entries, so usage.trackedTimeMs() does
            // not include elapsed time on still-running timers. Subtract that elapsed time here,
            // otherwise the cutoff keeps sliding forward by (cap - tracked) on every tick and the
            // scheduler never reaches lockNow when the due job fires.
            long elapsedRunningMs = runningEntries.stream()
                    .filter(e -> e.start() != null)
                    .mapToLong(e -> Math.max(0L, now.toEpochMilli() - e.start().toEpochMilli()))
                    .sum();
            long remainingMs = caps.timeLimitMs() - usage.trackedTimeMs() - elapsedRunningMs;
            if (remainingMs <= 0) {
                return new CutoffPlan(now, true, GuardReason.TIME_CAP_REACHED);
            }
            long concurrentTimers = runningEntries.size();
            candidates.add(new CutoffCandidate(
                    now.plusMillis(Math.max(0L, remainingMs / concurrentTimers)),
                    GuardReason.TIME_CAP_REACHED));
        }

        if (caps.budgetCapActive()) {
            BigDecimal remainingBudget = caps.budgetLimit().subtract(usage.budgetUsage(caps.includeExpenses()));
            // Same rationale as the time branch: subtract the billable amount already accrued on
            // still-running billable timers, so repeated reconciles converge rather than keeping
            // remaining flat. Non-billable entries do not accrue against the budget cap.
            BigDecimal elapsedBillableRunning = runningEntries.stream()
                    .filter(e -> e.start() != null)
                    .filter(RunningTimeEntry::billable)
                    .map(e -> elapsedBillable(projectState, e,
                            Math.max(0L, now.toEpochMilli() - e.start().toEpochMilli())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            remainingBudget = remainingBudget.subtract(elapsedBillableRunning);
            if (remainingBudget.compareTo(BigDecimal.ZERO) <= 0) {
                return new CutoffPlan(now, true, GuardReason.BUDGET_CAP_REACHED);
            }
            boolean anyBillable = runningEntries.stream().anyMatch(RunningTimeEntry::billable);
            if (anyBillable) {
                BigDecimal aggregateRate = aggregateBudgetRatePerMillisecond(projectState, runningEntries);
                if (aggregateRate.compareTo(BigDecimal.ZERO) <= 0) {
                    // SPEC §5 fail-closed: a billable timer is running but we can't determine its
                    // hourly rate, so budget math is ambiguous — lock immediately rather than
                    // letting the timer accrue past the cap.
                    return new CutoffPlan(now, true, GuardReason.BUDGET_CAP_REACHED);
                }
                long millis = remainingBudget.divide(aggregateRate, 0, RoundingMode.DOWN).longValue();
                candidates.add(new CutoffCandidate(
                        now.plusMillis(Math.max(0L, millis)),
                        GuardReason.BUDGET_CAP_REACHED));
            }
            // If no entries are billable, no labor accrues against the budget on this tick —
            // omit a budget candidate and let the time branch (if active) drive the cutoff.
        }

        CutoffCandidate earliest = candidates.stream()
                .min(Comparator.comparing(CutoffCandidate::cutoffAt))
                .orElse(null);
        if (earliest == null) {
            return new CutoffPlan(null, false, GuardReason.BELOW_CAPS);
        }
        if (!earliest.cutoffAt().isAfter(now)) {
            return new CutoffPlan(now, true, earliest.reason());
        }
        return new CutoffPlan(earliest.cutoffAt(), false, GuardReason.BELOW_CAPS);
    }

    private BigDecimal aggregateBudgetRatePerMillisecond(ProjectState projectState, List<RunningTimeEntry> runningEntries) {
        // Caller is expected to have at least one billable entry. Non-billable entries are
        // ignored. ZERO signals "rate missing on a billable entry" — caller must treat that
        // as fail-closed per SPEC §5.
        BigDecimal total = BigDecimal.ZERO;
        for (RunningTimeEntry entry : runningEntries) {
            if (!entry.billable()) {
                continue;
            }
            Optional<RateInfo> hourlyRate = projectState.hourlyRateForUser(entry.userId());
            if (hourlyRate.isEmpty() || !hourlyRate.get().present()) {
                return BigDecimal.ZERO;
            }
            total = total.add(hourlyRate.get().amount().divide(BigDecimal.valueOf(3_600_000L), 12, RoundingMode.HALF_UP));
        }
        return total;
    }

    private BigDecimal elapsedBillable(ProjectState projectState, RunningTimeEntry entry, long elapsedMs) {
        if (!entry.billable()) {
            return BigDecimal.ZERO;
        }
        return projectState.hourlyRateForUser(entry.userId())
                .filter(RateInfo::present)
                .map(rate -> rate.amount()
                        .multiply(BigDecimal.valueOf(elapsedMs))
                        .divide(BigDecimal.valueOf(3_600_000L), 12, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    public record Assessment(GuardReason reason, Instant cutoffAt, boolean lockNow, GuardReason plannedReason) {
        public GuardReason exceededReason() {
            return reason == GuardReason.TIME_CAP_REACHED || reason == GuardReason.BUDGET_CAP_REACHED ? reason : null;
        }
    }

    private record CutoffPlan(Instant cutoffAt, boolean lockNow, GuardReason reason) {
    }

    private record CutoffCandidate(Instant cutoffAt, GuardReason reason) {
    }
}

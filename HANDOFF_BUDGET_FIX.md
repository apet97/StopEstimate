# Handoff prompt — fix budget-cap to use billable (hourly rate), not cost

> Ready-to-paste into a new Claude Code session. Self-contained brief.

---

You're picking up a known bug in the `stop@estimate` Clockify addon at
`/Users/15x/Downloads/WORKING/addons-me/stop@estimate`. The time-cap enforcement is live-verified
end-to-end (see README.md and TODO.md "2026-04-18 gap-closing pass"). The **budget-cap path is
wrong** and has never been exercised against a real Clockify value. Your job is to fix it without
regressing the time-cap path and without touching any locked SPEC decisions.

**Read first:**
1. `CLAUDE.md`
2. `SPEC.md` §1 (manifest lock), §4 (persistence), §5 (guard logic)
3. `README.md` "How enforcement works"
4. `src/main/java/com/devodox/stopatestimate/service/EstimateGuardService.java`
5. `src/main/java/com/devodox/stopatestimate/service/ProjectUsageService.java`

## The bug in one sentence

Clockify's project `budgetEstimate` tracks the **billable amount** (`hourlyRate × billable hours` plus
optional expenses), not internal cost. The addon currently reads the `COST` amount type from the
summary report and uses the per-user `costRate` to compute elapsed budget on running timers. Both
of those are the wrong fields. On a workspace without Cost Analysis enabled, `COST` is never
returned, so `usage.budgetUsage()` stays at zero and budget-cap never breaches. On a workspace
with Cost Analysis enabled, it would breach against the wrong number entirely (internal cost
instead of billable).

Two project budget types exist in Clockify and both are billable-based, not cost-based:
- **MANUAL** — admin enters a $ total on the project.
- **PER_TASK** — the project's budget is the sum of per-task estimates.

Both track the same signal: invoiced-style billable amount. The fix should be type-agnostic
(don't branch on `budgetEstimate.type` for the rate lookup).

## Exact code to fix

All line numbers are against the current `main` branch; verify before editing.

### `src/main/java/com/devodox/stopatestimate/service/ProjectUsageService.java`

- Line 68 `extractSummaryCost(summary)` — rename/repurpose to `extractSummaryBillable(summary)`.
- Line 256–275 `extractSummaryCost(JsonObject)` — read billable, not cost. Accept either of:
  - `totals[].amounts[]` entries where `type` is `EARNED` or `BILLED` (both appear in Clockify
    responses depending on feature flags; accept either case-insensitively).
  - Top-level `totalBillable` or `totalAmount` when the array is absent.
  - Fall back to zero when neither is present (consistent with current behavior on Cost-Analysis-
    off workspaces, which now degrades gracefully instead of being structurally wrong).
- Line 278–295 `pickCostAmount(JsonObject)` — rename to `pickBillableAmount` and switch the type
  check to `EARNED` / `BILLED`.
- The `summaryFilter` body (line 181, post-Cost-Analysis fix) remains unchanged — we do NOT
  request `amounts:["EARNED"]` explicitly either, because enabling it would re-introduce the
  400 code 501 error we just removed. Clockify returns amount-type entries based on workspace
  features; we read whatever is present.

### `src/main/java/com/devodox/stopatestimate/service/EstimateGuardService.java`

- Line 371 `.subtract(elapsedCost(projectState, entry.userId(), elapsedMs))` — rename call to
  `elapsedBillable(...)`.
- Line 411–416 `elapsedCostRunning` and the per-entry `elapsedCost(...)` call — rename to
  `elapsedBillableRunning` / `elapsedBillable`.
- Line 435–445 `aggregateBudgetRatePerMillisecond` — switch from `projectState.costRateForUser(entry.userId())`
  to a new `projectState.hourlyRateForUser(entry.userId())` (mirror the existing method).
- Line 447–454 `elapsedCost` — rename to `elapsedBillable`. Switch `costRateForUser` to
  `hourlyRateForUser`. Zero-rate fallback stays the same.

### `src/main/java/com/devodox/stopatestimate/model/ProjectState.java`

- Add `hourlyRateForUser(String userId)` that mirrors `costRateForUser`:
  - Per-user membership `hourlyRate` first
  - Project default `defaultHourlyRate` second
  - `Optional.empty()` if neither is present (present check via `RateInfo::present`)
- Keep `costRateForUser` as-is — other code may still use it (grep before deleting).

### Billable-only entries (medium priority)

Clockify's budget only accrues on time entries where `billable=true`. The addon's `loadRunningEntries`
and summary totals currently include non-billable time. For the budget path specifically:

- When computing `usage.budgetUsage()` from summary, billable amount is already correct because
  Clockify's summary only sums billable time into the EARNED bucket.
- When computing `elapsedBillable` on a running timer, check the in-progress entry's `billable`
  field. If `false`, elapsed contribution for budget = zero (but still counts for time cap).
- Requires adding `billable` to `RunningTimeEntry` (record field) and propagating from
  `ProjectUsageService.loadRunningEntries`.

Verify Clockify's in-progress response carries `billable` before writing this — if not, accept a
small over-estimate and document in a code comment.

## What NOT to change

- Manifest, SPEC.md §1, scopes, webhooks — all locked.
- Time-cap enforcement path — currently correct and live-verified; any shared code changes must
  keep `ProjectCaps.timeLimitMs` and `usage.trackedTimeMs` semantics identical.
- `includeExpenses` handling — still adds the expense report total when true; orthogonal to the
  labor rate switch.
- The Cost-Analysis filter fix — summary report body must still NOT declare `amounts` or
  `amountShown` (see `ProjectUsageServiceTest.summaryFilterBodyDoesNotRequestCostAnalysis`).
  Reading the response's amounts is fine; requesting a specific amounts subset is not.

## Tests to add

Mirror the existing time-cap structure in `src/test/java/com/devodox/stopatestimate/EstimateGuardServiceTest.java`:

1. `budgetCapReachedLocksAndStopsTimers_usesHourlyRate` — mock summary to return
   `totals[].amounts[]` with `{type: "EARNED", value: 100}`, cap=$100, expect exceededReason =
   `BUDGET_CAP_REACHED`, lockProject called.
2. `budgetCapIgnoresCostAmountType` — mock summary with only `{type: "COST", value: 999}`, cap=$100,
   expect NO breach (cost not counted).
3. `cutoffAtAccountsForBillableElapsed` — cap=$60, no completed usage, one running timer at
   hourlyRate=$60/h on an entry started 50 min ago. Expect cutoffAt = entry.start() + 60 min
   (i.e. 10 min from now).
4. `elapsedBillableExceedingCapReturnsImmediateLockNow` — cap=$60, hourlyRate=$120/h, entry
   started 40 min ago (elapsed billable = $80 > $60). Expect `lockNow=true` with
   `BUDGET_CAP_REACHED`.
5. `nonBillableRunningTimerIgnoredForBudgetOnly` — if implemented: entry with `billable=false`,
   cap=$10, hourlyRate=$120/h, elapsed 30 min. Expect no lockNow (would be ~$60 if billable,
   but entry is not billable). Time-cap would still fire if it exists.

Update `ProjectUsageServiceTest` with:
6. `summaryExtractsEarnedAmountType` — response has `amounts[{type:"EARNED",value:42}]`, assert
   `extractSummaryBillable` returns 42.
7. `summaryExtractsBilledAmountTypeCaseInsensitive` — response has `amounts[{type:"billed",value:7}]`,
   assert returns 7.
8. `summaryIgnoresCostAmountTypeForBudget` — response has only `amounts[{type:"COST",value:99}]`,
   assert returns zero.

## Verification

1. `./mvnw test` — all new tests pass; existing count was 61, new total should be 61 + 8 ≈ 69.
2. Live smoke against the Clockify developer workspace (`69bda6b317a0c5babe34b4ff`, owner John
   Owner, regular user George) — see README.md "Running locally" and the historical test flow
   preserved in `.remember/` for the time-cap path. Recipe:
   - As owner, create a project with `budgetEstimate.active=true, estimate=<small>` and set the
     project's `hourlyRate` to a known value. Type can be MANUAL.
   - Add George as a member.
   - As George, start a timer. Compute expected cutoff = `estimate / hourlyRate` minutes.
   - Observe scheduler tick → `CUTOFF_SCHEDULED`, then due-job → `TIMER_STOPPED` / `LOCKED` with
     `guard_reason = BUDGET_CAP_REACHED`, exactly at the predicted minute.
   - Confirm `project.public=false`, memberships snapshotted, `guard_events` rows match.
   - Cleanup.
3. Regression check on time-cap: re-run the existing RAR5-style smoke (project with time
   estimate, no budget estimate, breach on schedule). Must behave identically to pre-fix.
4. Docs: update `SPEC.md` §5 "Budget Usage" to say "billable amount driven by `hourlyRate`" and
   remove any language implying cost basis. Update `README.md` "Enforcement semantics" to match.

## Scope guardrails

- Keep the PR focused on the budget-rate fix. No unrelated refactors.
- No manifest/scopes/webhooks changes.
- No changes to the async install retrier, guard_events REST endpoint, or scheduler cadence.
- Leave the Clockify-side webhook delivery bug alone — see `SUPPORT_TICKET.md`.

End with a table:

| Change | Files | Tests | Live verified |
|---|---|---|---|
| ... | ... | ... | ... |

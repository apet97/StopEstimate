# FOLLOWUP_TODO.md — Stop @ Estimate deferred audit items

Context: `SONNETTODO.md` is the original 84-finding audit. The first wave (P0+P1, 10 commits ending `a6311f2`) landed on `main` — see `git log 8007a9c..a6311f2`. That wave was validated in `~/.claude/plans/sonnettodo-md-ultrathink-review-the-sprightly-eclipse.md`. This doc tracks everything deferred from that wave.

All 71 existing tests pass today. Land each fix below as its own commit; re-run `./mvnw test` after every commit.

**Batch 1 status (2026-04-19):** landed on `main` in commits `2048e36..8a32c57` (7 commits, 71/71 tests green after each). Covered: BUG-03, BUG-04 narrow, BUG-07, BUG-09, RES-09, SEC-06, SEC-08, RES-05, RES-11.

**Batch 2 status (2026-04-19):** landed in commit `42dafce`. Covered: RES-01, RES-02, RES-03, RES-04, RES-06. 71/71 tests green.

**Batch 3/4 partial (2026-04-19):** landed in commit `961cc7d`. Covered: DB-07, DB-10, FE-10. Remaining Batch 3 (BUG-05, DB-06 with V1_0_8 migration, DB-08, DB-09) and Batch 4 (FE-11), plus Batch 5 (SEC-03 drain) and Batch 6 (TEST-01..13 + jacoco/dependency-check) still open.

**DO NOT TOUCH — verified as wrong or by-design:**
- **SEC-01** (webhook signature check). `ClockifyWebhookService.handleWebhook` already performs full RS256 verification at line 65 via `TokenVerificationService.verifyAndParseClaims`, with `workspaceId`/`addonId` claim checks on lines 69–80, and the per-route `constantTimeEquals` at line 81 is the documented defence-in-depth step #3. The audit misread `verifyStoredWebhookToken` in isolation. Confirmed against canonical-docs `01-canonical-docs/build/manifest/webhooks.md`.
- **FE-09** (unauthenticated `/sidebar`). Clockify iframe contract — HTML loads before token validation; all sensitive work happens in `/api/*`.

---

## P2 — land before next release

### BUG-03 — RateInfo.present() treats explicit zero as absent
`src/main/java/com/devodox/stopatestimate/model/RateInfo.java:13`. Current: `amount != null && amount.compareTo(BigDecimal.ZERO) > 0`. A user whose hourly rate is set to `0.00` is indistinguishable from "no rate set", so the budget-cap fail-closed path triggers a spurious lock. Add a `boolean configured` field to the record; set it to `true` in `ClockifyJson` deserialisation when the `amount` field is present (even if zero); `present()` returns `configured && amount != null && amount.compareTo(BigDecimal.ZERO) >= 0`.

### BUG-04 (narrow fix only) — `cutoffPlan` final fallback reason
`src/main/java/com/devodox/stopatestimate/service/EstimateGuardService.java:440`. The time and budget branches already return the correct `GuardReason`; only the shared "cutoff already in the past" fallback hardcodes `TIME_CAP_REACHED`. Track which branch contributed the minimum candidate and return its reason. **Do not apply the audit's sweeping fix — lines 401/420/429 are already correct.**

### BUG-05 — non-atomic upsertJob race
`EstimateGuardService.java:496–514`. Replace the check-delete-insert dance with a native `INSERT ... ON CONFLICT (workspace_id, time_entry_id) DO UPDATE SET job_id=EXCLUDED.job_id, cutoff_at=EXCLUDED.cutoff_at, updated_at=now()` on `CutoffJobRepository`. The unique constraint `uk_cutoff_jobs_workspace_time_entry` (added in V1_0_5) gives us the conflict target.

### SEC-06 — assertMatches silently skips missing claims
`src/main/java/com/devodox/stopatestimate/service/ClockifyLifecycleService.java:203–206`. `if (expected.isPresent() && ...)` means an absent workspaceId claim passes the check. Flip to `if (expected.isEmpty() || !expected.get().equals(actual)) throw new ClockifyRequestAuthException(...)`.

### SEC-08 — default DB password fail-fast
`src/main/resources/application.yml:7`. Currently `${SPRING_DATASOURCE_PASSWORD:stop_at_estimate}`. Change the default to empty and add a `@PostConstruct` guard in `SecurityConfig` that rejects the example `stop_at_estimate` value (mirror the existing encryption-key/salt anti-default checks at `SecurityConfig.java:68–77`).

### RES-01 — Thread.sleep in 429 retry blocks pool
`src/main/java/com/devodox/stopatestimate/api/ClockifyBackendApiClient.java` `exchangeOnce` retry loop. Throw `ClockifyApiException("Rate limited by Clockify (429); will retry on next tick", e)` on 429 rather than sleeping. The scheduler's next tick is the natural retry interval.

### RES-02 — ReportsApiClient no 429/401 differentiation
`src/main/java/com/devodox/stopatestimate/api/ClockifyReportsApiClient.java:77–80`. Differentiate:
```java
int code = e.getStatusCode().value();
if (code == 429) throw new ClockifyApiException("Reports rate limited (429)", e);
if (code == 401) throw new ClockifyRequestAuthException("Reports token rejected", e);
if (code == 403) throw new ClockifyBackendForbiddenException("Reports forbade the request", e);
throw new ClockifyApiException("Reports call failed with " + code, e);
```
(`ClockifyBackendForbiddenException` already exists — added in commit `30084f4`.)

### RES-03 — 10s read timeout too short for paginated calls
`src/main/java/com/devodox/stopatestimate/service/ClockifyInfrastructureConfiguration.java:26`. Split the `RestClient` customiser into two beans:
- Backend (pagination-heavy `listProjects`, `filterUsers`, `listInProgressTimeEntries`): 30s.
- Reports: 45s.

### RES-04 — retrier retries 401 auth failures
`src/main/java/com/devodox/stopatestimate/service/InstallReconcileRetrier.java:51`. Add a narrow catch for `ClockifyRequestAuthException` that logs and returns (no retry) before the broad `RuntimeException` catch. Auth failures won't recover without a token refresh.

### RES-06 — ContextApiController leaks backendUrl/reportsUrl
`src/main/java/com/devodox/stopatestimate/controller/ContextApiController.java:39–40`. Delete those two `payload.put(...)` lines. `sidebar.js` does not read them (already verified in the audit).

### DB-07 — webhook dedup read-then-write
`src/main/java/com/devodox/stopatestimate/service/ClockifyWebhookService.java:116–127`. Drop the `existsByIdEventIdAndIdSignatureHash` pre-check and go straight to `save`; the existing `DataIntegrityViolationException` catch already treats a duplicate insert as a duplicate delivery. One fewer DB round trip.

### FE-11 — surface `/api/guard/events` in the sidebar
`src/main/java/com/devodox/stopatestimate/controller/GuardApiController.java:61–76`. The endpoint exists but the UI never calls it. Add a collapsible "Recent events" panel below the projects table in `src/main/resources/templates/sidebar.html`; in `src/main/resources/static/js/sidebar.js` add `loadEvents()` to `loadAll()` using the existing `fetchJson` helper and render via DOM construction (same pattern as `renderProjects`). Keep the existing XSS-safe approach.

---

## P3 — housekeeping

### BUG-07 — YEARLY month fallback is fragile
`src/main/java/com/devodox/stopatestimate/model/ResetWindowSchedule.java:84`. `month == null ? nextYear.getMonth() : month` is accidentally correct only because the start month is January. Replace with `Month nextMonthValue = month == null ? Month.JANUARY : month;`.

### BUG-09 — ClockifyApiUrls.join null baseUrl
`src/main/java/com/devodox/stopatestimate/api/ClockifyApiUrls.java:14–18`. Currently produces `"null/path"` silently. Throw `IllegalArgumentException("Clockify baseUrl must not be null or blank")` up front.

### RES-05 — ManifestController throws JsonProcessingException on @GetMapping
`src/main/java/com/devodox/stopatestimate/controller/ManifestController.java:25`. Spring MVC does not route checked exceptions to `@ExceptionHandler`. Wrap in try/catch and throw `new IllegalStateException("Failed to serialize manifest", e)`.

### RES-09 — deleteStaleByProject bypasses CutoffJobStore
`src/main/java/com/devodox/stopatestimate/service/EstimateGuardService.java` — direct `cutoffJobRepository.deleteStaleByProject(...)`. Add `deleteStale(String workspaceId, String projectId, Set<String> keepIds)` to `CutoffJobStore`; remove the direct repo injection (`EstimateGuardService.java:46`).

### RES-11 — salt validation is half the key length
`src/main/java/com/devodox/stopatestimate/config/SecurityConfig.java:60`. Require `salt.length() < 64` instead of `< 32` so the salt is 256-bit like the key. Update the example in application.yml docstrings and any test setup.

### DB-06 — guard_events retention
- Add `@Modifying @Query("delete from GuardEventEntity e where e.createdAt < :cutoff") int deleteAllOlderThan(Instant cutoff)` to `GuardEventRepository`.
- Add a `@Scheduled @SchedulerLock("guard-events-purge") @Transactional` method to `CutoffJobScheduler` (retention default 30 days via `@Value`).
- New migration `V1_0_8__guard_events_retention.sql`: `CREATE INDEX idx_guard_events_created_at ON guard_events(created_at);`.

### DB-08 — redundant partial index on PK column
`src/main/resources/db/migration/V1_0_5__p0_integrity.sql:18–20`. The `idx_installations_active` partial index keys on `workspace_id` (the PK), so it adds no lookup benefit. Options: drop it, or replace with a query-specific index — add `@Query("select i from InstallationEntity i where i.status = 'ACTIVE' and i.enabled = true") List<InstallationEntity> findAllActive()` to `InstallationRepository` and have the scheduler use it, then keep the index as-is (it's now actually useful for the partial-predicate filter).

### DB-09 — N+1 in unlockWorkspaceProjects
`src/main/java/com/devodox/stopatestimate/service/ProjectLockService.java:108–112`. The loop calls `unlockProject(projectId)` which re-fetches the snapshot. Add an overload `unlockProjectFromSnapshot(InstallationRecord, ProjectLockSnapshot)` that accepts the already-loaded snapshot; `unlockWorkspaceProjects` calls that.

### DB-10 — cleanupWebhookEvents missing @Transactional
`src/main/java/com/devodox/stopatestimate/scheduler/CutoffJobScheduler.java` — annotate the method with `@Transactional` alongside `@Scheduled` and `@SchedulerLock`. Low risk today but cleaner boundary.

### FE-10 — aria-live on status elements
`src/main/resources/templates/sidebar.html:145,167`. Add `role="status" aria-live="polite"` to `#mode-pill` and `role="status" aria-live="polite" aria-atomic="true"` to `#status-line`. Optionally add `aria-describedby="status-line"` on the reconcile button.

### SEC-03 follow-up — drain legacy-format encrypted tokens
`src/main/java/com/devodox/stopatestimate/config/SecurityConfig.java:85–115`. The current `textEncryptor` bean reads legacy `Encryptors.text()` ciphertext as a fallback so SEC-03 shipped zero-downtime. Finish the job:
- Add a `@Transactional` startup task (or one-shot Flyway Java migration) that iterates `installations` and `webhook_registrations`, decrypts (hits fallback), re-encrypts (modern), persists.
- After the next deploy confirms all rows are re-encrypted, remove the `legacy` fallback from the bean.

---

## Test coverage gaps (P1 priority, broken out)

All 13 TEST-* findings are confirmed. Re-visit the original `SONNETTODO.md` §6 for the specific cases each test should cover. High-impact-first order:

1. **TEST-01** — `EstimateGuardService.processDueJobs` (zero tests today, the entire enforcement path). Cover: normal fire, timer already stopped, ownership race (deleteByJobId returns 0), caps removed between schedule and fire, and — critical — `stopRunningTimer` throws but `lockProject` succeeds vs. the reverse (exercises the BUG-11 fix).
2. **TEST-02** — `ClockifyWebhookService` (zero tests). Cover: `PROJECT_UPDATED` routing, null stored token, workspace mismatch, dedup fallback with body hash, the full RSA-verify path.
3. **TEST-06** — `ClockifyLifecycleService` (zero tests). Cover: `statusChanged` for unknown workspace, `handleDeleted` cascades both stores, `extractBooleanSetting` with JsonPrimitive("false"), null `authToken` entry.
4. **TEST-10** — `VerifiedAddonContextService` (zero tests). Cover: default language "en", blank theme → "DEFAULT", missing userId → null (not exception).
5. **TEST-04** — multi-timer concurrency in `stopRunningEntries` + `cutoffPlan` division + `syncCutoffJobs` stale pruning.
6. **TEST-03** — `cutoffPlan` fail-closed missing-rate branch (`RateInfo.empty()`, no member rates, running billable timer → `BUDGET_CAP_REACHED`). Pair with BUG-03 so zero-rate vs. absent-rate are both asserted.
7. **TEST-05** — `reconcileProject` observe-mode (`enforcing=false` with usage at cap → no lock) and `caps.hasActiveCaps()==false` unlock branch.
8. **TEST-07** — `reconcileProject` already-locked skip: `lockNow=true` but `isLocked=true` → `lockProject` never called.
9. **TEST-08** — `TokenVerificationService.normalizeClaims` legacy aliases (`activeWs`, `apiUrl`, `user`).
10. **TEST-09** — `immediateCutoff` asserts `cutoffAt == entry.start` (not `now`) when usage is already at cap.
11. **TEST-11** — `ResetWindowSchedule` month-end (`dayOfMonth=31`, Feb) and DST (spring-forward at 2am America/New_York).
12. **TEST-12** — `ProjectUsageService.extractSummaryTotalTime` (numeric ms, ISO-8601 `PT1H`, two totals entries, null).
13. **TEST-13** — `InstallReconcileRetrier` interrupt path (`Long.MAX_VALUE` backoff, interrupt thread, assert `reconcileKnownProjects` never called and interrupt flag set).

---

## Execution plan for a follow-up session

Suggested batching (land each batch as one PR, run `./mvnw test` between commits):

- **Batch 1 (correctness — small):** ✅ LANDED (commits `2048e36..8a32c57`). BUG-03, BUG-04 narrow fix, BUG-07, BUG-09, RES-09, SEC-06, SEC-08, RES-05, RES-11. 71/71 tests green after each commit.
- **Batch 2 (resilience — medium):** RES-01, RES-02, RES-03, RES-04, RES-06. ~0.5 day.
- **Batch 3 (DB housekeeping):** BUG-05 (ON CONFLICT upsert), DB-06 (retention + new migration V1_0_8), DB-07, DB-08, DB-09, DB-10. ~1 day.
- **Batch 4 (accessibility + UI):** FE-10, FE-11. ~0.5 day.
- **Batch 5 (SEC-03 finalisation):** re-encrypt job + fallback removal, staged over two deploys. ~0.5 day + deploy windows.
- **Batch 6 (tests — biggest gap):** TEST-01..13 in the priority order above. 2–3 days.

**Acceptance check per batch:** `./mvnw test` green; `0_TO_WORKING.md` scenarios still pass (manifest → install → webhook → cap-reached hard-stop → settings-updated unlock).

# TODO — `stop@estimate` Audit

Generated 2026-04-17 from 10 parallel Opus subagents (security, services, controllers/config, perf/DB, spec, tests, frontend, build/infra, bugs, architecture). Severity scale: **CRITICAL > HIGH > MED > LOW**.

---

## 2026-04-19 quality / efficiency / stability pass — resolved

- [x] HTTP connect/read timeouts (5s / 10s) applied to every Clockify call via a `RestClientCustomizer` in `ClockifyInfrastructureConfiguration`. Previously both clients built `RestClient` with no timeouts and a stalled Clockify socket could hang a webhook thread indefinitely.
- [x] `ClockifyReportsApiClient` now caches the built `RestClient` in `@PostConstruct` instead of rebuilding per call (matches the backend client's pattern, stops ephemeral-port churn).
- [x] `ClockifyBackendApiClient.exchange` now does a single `Retry-After`-honoring retry on HTTP 429 (capped at 10s sleep) before falling through to `classify()`.
- [x] `EstimateGuardService.upsertJob` now catches `DataIntegrityViolationException` from the `uk_cutoff_jobs_workspace_time_entry` unique constraint and either returns (identical cutoffAt already written) or re-reads and overwrites. Parallel webhook deliveries for the same timer no longer leak a 500 up to Clockify.
- [x] Removed the redundant recursive `reconcileProject(...)` call at the tail of `processDueJobs` — the enclosing code has already stopped the timer and locked the project, so the recursive call was paying for 3 extra Clockify API calls just to observe the terminal state.
- [x] `webhook_events` retention: new hourly `@Scheduled` + ShedLock-guarded `cleanupWebhookEvents` in `CutoffJobScheduler` calls `deleteAllOlderThan(now-24h)`. The repository method existed but was never invoked, so the table grew unbounded.
- [x] Deleted dead `ProjectUsageService.findFirstElement` / `findDirectElement` (never called; callers use `ClockifyJson.findFirstString`).
- [x] Added `EstimateGuardServiceTest.upsertJobRecoversFromConcurrentInsertRace` and `upsertJobConcurrentWinnerWithSameCutoffAtReturnsQuietly` covering both DIVE-retry branches.

---

## 2026-04-18 gap-closing pass — resolved

The following follow-up items (flagged post-initial-audit, some surfacing after runtime validation)
were closed in the 2026-04-18 commit:

- [x] `guard_events` was dead code (entity + migration existed but nothing wrote to it). Now
      `EstimateGuardService` records `LOCKED`/`UNLOCKED`/`CUTOFF_SCHEDULED`/`TIMER_STOPPED` at the
      four outcome points in `reconcileProject` and `processDueJobs`. Exposed via
      `GET /api/guard/events?limit=N&projectId=...`.
- [x] `webhook_registrations.event_type` was always NULL on install. Now resolved at install time
      by cross-referencing the INSTALLED payload's path against the authoritative manifest map
      (`AddonManifestConfiguration.webhookPathToEvent`).
- [x] Regression test coverage added for the Cost Analysis filter fix
      (`ProjectUsageServiceTest.summaryFilterBodyDoesNotRequestCostAnalysis`) and the cutoffPlan
      elapsed-time subtraction fix (`cutoffAtAccountsForRunningTimerElapsedTime`,
      `elapsedExceedingCapReturnsImmediateLockNow`, `budgetCapElapsedCostAccountingReachesImmediateLock`).
- [x] `Reached the maximum number of URI tags for 'http.client.requests'` log spam — mitigated by
      raising `management.metrics.web.client.max-uri-tags` to 500. URI-template normalization
      remains a follow-up (see new entry below).
- [x] Install-time 401 gap — `InstallReconcileRetrier` now does a 2s/5s/10s async backoff after
      `handleInstalled` so the first durable reconcile lands within ~17s instead of waiting for
      the 60s scheduler tick.
- [x] Docs drift on webhook count (10 → 5 declared), scope count (6 → 7, adding `REPORTS_READ`),
      and Cost Analysis prerequisite callout in `PUBLISH_CHECKLIST.md`. SPEC.md §1 wording on the
      5 no-op handlers tightened to mark them as intentional hibernated plumbing.

### Still open (added this pass)

- [ ] **[LOW]** Swap the bumped `max-uri-tags` for proper `WebClientCustomizer` URI-template
      normalization (`/v1/workspaces/{id}/projects/{pid}` tag rather than per-request IDs). Today's
      fix just raises the cap; a principled fix collapses the cardinality.

### External blocker — not addon-side

- [ ] **[EXTERNAL — dev only, not release-blocking]** Clockify's addon webhook delivery for the
      developer workspace `69bda6b317a0c5babe34b4ff` is stuck — zero delivery attempts on all 5
      manifest-declared events, including from SEND TEST. **Production verified 2026-04-19:** the
      same 5 webhooks deliver normally on prod Pro workspaces, so this is isolated to the one dev
      tenant. Lifecycle POSTs arrive fine to the same baseUrl on dev; webhook POSTs never do. Full
      uninstall + reinstall does not reset the state. Addon-registered webhooks can't be deleted
      from the Clockify admin UI. Matches a known Clockify-side bug previously fixed by their
      engineering for user webhooks (https://forum.clockify.me/t/webhooks/21). **Resolution path:**
      file a support ticket when the dev tenant is needed for webhook smoke again — draft ready at
      [`SUPPORT_TICKET.md`](./SUPPORT_TICKET.md). Webhook handlers stay registered as declared.

---

## P0 — BLOCKERS (fix before any deploy)

### Storage / data integrity
- [ ] **[HIGH]** Replace `FileBacked{Installation,LockSnapshot,CutoffJob}Store` with the JPA `*Repository`s on every hot path; CLAUDE.md non-negotiable. Files: `service/EstimateGuardService.java:14,36-50,159-186,236-246,388-440`, `service/ProjectLockService.java:9,25`, `service/ClockifyLifecycleService.java:7-9,32-34`. Then delete `store/FileBacked*` package and the dead `throws IOException` boilerplate in callers.
- [ ] **[HIGH]** Add `@Transactional` to mutating service methods after Postgres swap — `lockProject`/`unlockProject`/`reconcileProject`/`processDueJobs`. `service/ProjectLockService.java:59-104`, `service/EstimateGuardService.java:159-186`. Sequence: API call first → persist snapshot last so an orphan snapshot can't fool the next reconcile.
- [ ] **[HIGH]** Add unique constraint `(workspace_id, time_entry_id)` on `cutoff_jobs` and switch `upsertJob` to `INSERT … ON CONFLICT DO UPDATE`. `db/migration/V1_0_3__cutoff_jobs.sql:13`, `service/EstimateGuardService.java:388-399`.
- [ ] **[HIGH]** Add `@Version` (optimistic locking) to `InstallationEntity`, `ProjectLockSnapshotEntity`, `CutoffJobEntity` so concurrent webhook+scheduler writes can't clobber.

### Security
- [ ] **[HIGH]** `TokenVerificationService.java:33-65` — JWT accepts missing/non-numeric `exp`, no `nbf`/`iat`/`aud` check, no clock skew leeway, uses `System.currentTimeMillis` instead of injected `Clock`. Require numeric `exp`, validate `nbf`/`iat` ±30–60 s, enforce max-age (~5 min) for sidebar tokens, inject `Clock`.
- [ ] **[HIGH]** `ClockifyUrlNormalizer.java:11-43` — accepts any scheme, no host allow-list. A forged JWT's `backendUrl: http://attacker/...` would receive the installation token via `X-Addon-Token`. Reject scheme ≠ `https` and host not in `{*.clockify.me, regional CDN hosts}`.
- [ ] **[HIGH]** `ClockifyReportsApiClient.java:45,56,67` — full URL/request/response bodies logged at INFO (`REPORTS-DEBUG`). Workspace financial + time data leak. Drop or guard behind `log.isTraceEnabled()` with redaction.
- [ ] **[HIGH]** `ClockifyWebhookService.java:46-89` — `Clockify-Signature` is treated as a static bearer JWT; one leak = forever auth. Body is never bound to the signature. Fix: constant-time compare, never log; strictly validate `exp/iat`; prefer HMAC-of-body if Clockify offers it.
- [ ] **[HIGH]** Add `spring-boot-starter-security` + a `SecurityFilterChain`. Currently no security headers (no CSP `frame-ancestors https://*.clockify.me`, no HSTS, no `X-Content-Type-Options`, no `X-Frame-Options`), no CSRF, no centralized `X-Addon-Token` enforcement, actuator unprotected. `config/SecurityConfig.java`, `pom.xml:55`.
- [ ] **[HIGH]** Centralize `X-Addon-Token` validation in a `HandlerInterceptor`/filter for `/api/**`; today every protected handler hand-rolls `try/catch InvalidAddonTokenException` (`GuardApiController.java:33,48`, `ContextApiController.java:32`) — easy to forget on next route.
- [ ] **[HIGH]** Add `@RestControllerAdvice` mapping `ClockifyApiException`, `JsonSyntaxException`, generic `Exception` → sanitized envelope; set `server.error.include-message=never`, `include-stacktrace=never`. `ManifestController.java:25` and others currently leak Spring default error JSON / 500s.

### Spec / manifest
- [ ] **[HIGH]** `AddonManifestConfiguration.java:46` calls `.requireProPlan()` while `SPEC.md:13` says `free-compatible`. Tests + PRD + README all assume PRO. Either keep PRO and fix SPEC, or remove `.requireProPlan()` and update tests. Lock the decision in CLAUDE.md.
- [ ] **[HIGH]** Spring Boot pinned to non-existent version `3.5.13` in `pom.xml:10` — build will fail or pull a snapshot. Pin a verified GA (e.g. 3.4.x or current 3.5.x).
- [ ] **[HIGH]** `pom.xml` — no JWT library declared (`jjwt`/`nimbus-jose-jwt`); RS256 verification can't compile from clean. Add `io.jsonwebtoken:jjwt-{api,impl,jackson}` (or Nimbus).

### Bugs (data correctness)
- [ ] **[HIGH]** `ClockifyBackendApiClient.java:118-128` + `ProjectLockService.java:75-78` — restoring per-user rates writes only `amount`, drops `currency`. After unlock, EUR rates revert to workspace default → cost reports wrong. Send `currency` too; skip the rate write entirely when unchanged.
- [ ] **[HIGH]** `EstimateGuardService.java:206-229` — `summarizeProject` calls `projectState.caps().hasActiveCaps()` without null-check → 500s the whole sidebar `GET /api/projects` if caps is null.
- [ ] **[HIGH]** `EstimateGuardService.java:116-125` — when project is already locked and assessment says `lockNow`, code unlocks (restoring memberships) then immediately re-locks. Transient open window + double API churn. Skip unlock when `assessment.lockNow()` is true.
- [ ] **[HIGH]** Webhook deduplication missing — Clockify retries hit the same handler twice. `ClockifyWebhookService.java:30-79`. Persist `(event_id, signature_hash)` with unique constraint and short-circuit on duplicate. Without this, double `stopRunningTimer` + double `lockProject`.
- [ ] **[HIGH]** `processDueJobs` race vs SETTINGS_UPDATED / cancellation. `EstimateGuardService.java:159-186`. Re-load installation/job in one `@Transactional`; use `deleteByJobId(...)` returning rows-affected to confirm ownership before calling Clockify.
- [ ] **[HIGH]** `CutoffJobScheduler` is single-threaded with no `ShedLock`/leader election; multi-instance deploy double-stops timers. Add `ShedLock` (Postgres provider) and a `ThreadPoolTaskScheduler` with pool-size ≥ 2.
- [ ] **[HIGH]** `ProjectLockService.lockProject` re-snapshot risk when called twice while already locked — if snapshot is later cleared (race with DELETED) and recreated from a locked-state read, originals are captured as locked state → unlock cannot restore. Guard: never create a new snapshot while project is known-locked.
- [ ] **[HIGH]** `LifecycleController` returns 400 for unknown workspace on STATUS_CHANGED / SETTINGS_UPDATED; SPEC §8 says safe-ignore + 200. `service/ClockifyLifecycleService.java:128-129, 149-150`.
- [ ] **[HIGH]** `ClockifyLifecycleService.handleInstalled` not idempotent — re-INSTALLED clobbers existing webhook tokens, status, settings (`createdAt` reset). Lookup existing record and merge.

### Performance (high impact)
- [x] **[HIGH]** `ClockifyBackendApiClient.java:244` + `ClockifyReportsApiClient.java:47` — `restClientBuilder.build()` per request. No connection pooling, no timeouts, no retry/backoff. — **Closed 2026-04-19**: single `RestClient` per client via `@PostConstruct`, 5s/10s timeouts via `RestClientCustomizer`, Retry-After-honoring retry on 429. 5xx retry + connection pool tuning still open as a follow-up.
- [ ] **[HIGH]** `processDueJobs` `findAllJobs()` then in-memory filter — `idx_cutoff_jobs_cutoff_at` unused. Add `findAllByCutoffAtLessThanEqual(Instant now)` with `LIMIT` + ORDER BY.
- [ ] **[HIGH]** `knownProjectIds()` paginates whole workspace projects on every reconcile (every 60 s × workspaces). Add Caffeine cache (TTL 30–60 s) keyed by `workspaceId`.

---

## P1 — HIGH (next iteration)

### Bugs
- [ ] **[MED]** `ResetWindowSchedule.java:35` uses default-locale `toUpperCase()` — breaks under `tr_TR`. Use `Locale.ROOT`.
- [ ] **[MED]** `ResetWindowSchedule` + `ProjectUsageService.baseReportFilter:226` — DST mismatch: caps reset at workspace local midnight but report query hardcodes `"UTC"`. Off by ±1 h on DST days. Send the report with the workspace's `timeZone`.
- [ ] **[MED]** `ClockifyLifecycleService.java:276` — `getAsBoolean()` on a `JsonPrimitive` string `"true"` returns false. Inspect type explicitly.
- [ ] **[MED]** `ClockifyBackendApiClient.java:152-191, 201-219` — unbounded paginated loops can OOM. Cap at ~1000 pages, log + throw.
- [ ] **[MED]** `WebhookController` doesn't catch `ClockifyApiException` → returns 500 → Clockify retries forever, compounds the missing-dedup bug. Catch and return 200 (or 503), let scheduler reconcile later.
- [ ] **[MED]** `ProjectLockService.unlockProject` — no rollback if `updateProjectMemberships` succeeds but visibility update fails. Persist a "pending unlock" state or wrap in single tx with compensation log.
- [ ] **[MED]** `EstimateGuardService.cutoffPlan:319-323` — variable name `aggregateDurationRate = N` is misleading; integer division can cut a few ms early; no recomputation when 2nd timer starts mid-window without a webhook (e.g. import). Persist running-count and re-derive on every tick.
- [ ] **[MED]** `processDueJobs` always locks on `TIME_CAP_REACHED` even if user manually stopped earlier. Re-run `assess(...)` first; only lock if `exceededReason() != null`.
- [ ] **[MED]** `ClockifyLifecycleService.extractWebhookTokens:243-263` silently drops malformed entries — log at WARN.
- [ ] **[MED]** `ClockifyLifecycleService.reconcileQuietly` / `suppressEnforcementQuietly:168-182` log only `e.getMessage()` — pass exception so stack trace survives.
- [ ] **[MED]** `ProjectUsageService.loadRunningEntries:73-96` filters in memory after fetching all workspace in-progress entries. Use project-scoped endpoint or per-tick cache.

### Security / hardening
- [ ] **[MED]** `application.yml:5-7` ships real default Postgres creds (`stop_at_estimate`/`stop_at_estimate`). Drop default password or fail-fast if it equals the example. Same for `docker-compose.yml:5-9`.
- [ ] **[MED]** `AddonProperties.baseUrl` defaults to `https://example.ngrok-free.app`. `application.yml:41`. Default to empty + `@PostConstruct` validate non-blank HTTPS.
- [ ] **[MED]** Frontend: `sidebar.js:191-197` falls back to `postMessage(..., '*')` and accepts any-origin inbound when `parentOrigin` is null. Refuse to operate; allow-list `*.clockify.me`.
- [ ] **[MED]** Frontend: inline `<style>` block + `style="display:none"` in `sidebar.html:7-135` require `style-src 'unsafe-inline'`. Move to `/css/sidebar.css` + `.is-hidden` utility class.
- [ ] **[MED]** Frontend: no CSRF / `X-Requested-With` on `POST /api/guard/reconcile`. `sidebar.js:60-67`. Set `credentials:'omit'`, add anti-replay header.
- [ ] **[MED]** Frontend: token kept in module variable forever; refresh interval keeps firing when iframe hidden. Clear `token`, `clearInterval`, `removeEventListener` on `pagehide`/`visibilitychange=hidden`.
- [ ] **[MED]** `WebhookController` accepts any media type. Add `consumes = MediaType.APPLICATION_JSON_VALUE` so non-JSON returns 400 not 500.

### Build / infra
- [ ] **[MED]** Add Maven Surefire + Failsafe with explicit versions and `**/*IT.java` includes. Currently no integration-test phase wired.
- [ ] **[MED]** Add `org.testcontainers:postgresql` and drop H2 + the parallel `db/migration-test/` tree. Today H2-with-PG-mode hides PG-only behavior (JSONB, partial indexes, advisory locks); duplicate migration trees can drift silently.
- [ ] **[MED]** `Dockerfile:1-7` — `dependency:go-offline || true` swallows resolver failures; no Maven cache mount; hardcoded JAR filename. Use BuildKit cache mount and `COPY --from=build /build/target/*.jar /app/app.jar`.
- [ ] **[MED]** No structured (JSON) logging for prod; add `logback-spring.xml` with `prod` profile + logstash-encoder.
- [ ] **[MED]** Define a `ThreadPoolTaskScheduler` bean in `ClockifySchedulingConfiguration` so a long reconcile doesn't block cutoff job processing.
- [ ] **[MED]** Cache compiled manifest body and the loaded `RSAPublicKey` as singleton beans; ETag the `/manifest` endpoint.

### DB
- [ ] **[MED]** `cutoff_jobs.time_entry_id` lookup not unique — already covered as P0 unique constraint above.
- [ ] **[MED]** `installations` table — `idx_installations_status` is low-cardinality. Replace with partial: `CREATE INDEX idx_installations_active ON installations(workspace_id) WHERE status='ACTIVE' AND enabled=TRUE;`
- [ ] **[MED]** HikariCP — add `connection-timeout: 5000`, `max-lifetime: 1800000`, `leak-detection-threshold: 30000`.
- [ ] **[MED]** Bulk-delete in `syncCutoffJobs` via `@Modifying @Query` instead of per-row deletes. `EstimateGuardService.java:381-385`.

### Tests
- [ ] **[HIGH]** Top 10 missing tests (highest ROI):
  1. `TokenVerificationServiceTest#expiredJwtRejected` (`exp` 1 s in past).
  2. `TokenVerificationServiceTest#wrongIssuerKeyRejected` (foreign RSA).
  3. `TokenVerificationServiceTest#algNoneOrHs256Rejected`.
  4. `TokenVerificationServiceTest#missingWorkspaceClaimRejected`.
  5. `ClockifyUrlNormalizerTest` parameterized (trailing slash, region variants, null/blank, scheme rejection, host allowlist).
  6. `ClockifyLifecycleServiceIT#reinstallIsIdempotent`.
  7. `ProjectLockServiceIT#unlockRestoresOriginalMembershipsAndCurrency`.
  8. `CutoffJobSchedulerConcurrencyIT` with Testcontainers Postgres + 2 parallel ticks.
  9. `WebhookControllerWebMvcTest#missingSignatureHeader401` + `bodyWorkspaceMismatchRejected`.
  10. `ClockifyBackendApiClientTest` with `MockRestServiceServer` — 401/403/429/5xx mapping to typed exceptions.
- [ ] **[MED]** Add `@DataJpaTest` for every repository.
- [ ] **[MED]** Add `@WebMvcTest` slices for `ManifestController`, `SidebarController`, `ContextApiController`, `GuardApiController`.
- [ ] **[MED]** `EstimateGuardServiceTest` mocks owned services (`ProjectUsageService`, `ProjectLockService`, `ClockifyLifecycleService`, `FileBackedCutoffJobStore`) — masks bugs. Replace with thin integration / fakes.
- [ ] **[MED]** Replace deprecated `@MockBean` with `@MockitoBean` (Spring Boot 3.4+).
- [ ] **[MED]** Test fixtures hardcode `https://api.clockify.me`. Centralize and add a comment: "production must extract from JWT."

---

## P2 — MED / LOW (cleanup, polish)

### Architecture / naming
- [ ] **[LOW]** Rename `FileBacked*Store` → `*Store` (or fold into `*Repository`); move out of `store/`.
- [ ] **[LOW]** Drop fake `throws IOException` from store signatures and the matching catch blocks in callers (~8 sites).
- [ ] **[LOW]** Remove `implements Serializable` from `ProjectCaps`/`ProjectState`/`ProjectUsage`/`InstallationRecord` records — no consumer.
- [ ] **[LOW]** Resolve `@Lazy ClockifyCutoffService` cycle in `ClockifyLifecycleService.java:46` via an orchestrator service or `ApplicationEvent`.
- [ ] **[LOW]** Introduce `ClockifyClient` facade returning domain records — services should not see `JsonObject`.
- [ ] **[LOW]** Convert `GuardReason` to `sealed interface GuardOutcome permits Triggered, BelowCap, NoCaps`.
- [ ] **[LOW]** Move HTTP-derived exceptions (`Clockify{AccessForbidden,RequestAuth}Exception`, `InvalidAddonTokenException`) into `api/`, give them a shared `ClockifyException` base.
- [ ] **[LOW]** Move `ClockifyInfrastructureConfiguration` to `config/`.
- [ ] **[LOW]** Reserve `Clockify*` prefix for adapters/clients only; rename internal services (`CutoffService`, `ResetWindowPlanner`, `WebhookIngestService`).
- [ ] **[LOW]** `GuardEventEntity` + `GuardEventRepository` exist but have no writer. Either wire `GuardEventRecorder` into every guard fire or delete entity+repo+migration.
- [ ] **[LOW]** Drop unused `enforcementMode` field on `InstallationRecord` (always `"ENFORCE"`); centralize setting parsing in a `SettingsParser`.
- [ ] **[LOW]** Mark service classes `final`; hoist `Gson` to `private static final`.
- [ ] **[LOW]** `ClockifyUrlNormalizer.normalize` — `appendV1` parameter is dead; remove.
- [x] **[LOW]** Delete `ProjectUsageService.findFirstElement` / `findDirectElement` — unused. **Closed 2026-04-19.**
- [ ] **[LOW]** Inject `Clock` into `TokenVerificationService` and `ClockifyWebhookService`.

### Frontend polish
- [ ] **[LOW]** `sidebar.js:105-114` — replace `tr.innerHTML` + `safe()` with `document.createElement('td')` + `textContent` for cells.
- [ ] **[LOW]** `sidebar.html` — add `<meta name="referrer" content="no-referrer">`; clear `performance.clearResourceTimings()` after token strip; add `aria-live="polite"`/`role="status"` on `#status-line`/`#mode-pill`; wrap content in `<main>`; add a `<caption class="visually-hidden">`.
- [ ] **[LOW]** `sidebar.js:60-67` — disable reconcile button while in-flight, add `AbortSignal.timeout(15000)`, debounce.
- [ ] **[LOW]** Token refresh: track consecutive failures, halt on 401, exponential backoff.
- [ ] **[LOW]** Friendlier error states: map status codes to user copy + add a retry affordance.

### DB / perf polish
- [ ] **[LOW]** `webhook_registrations` — drop redundant `idx_webhook_registrations_workspace` (covered by composite unique).
- [ ] **[LOW]** `guard_events` retention — partition by `created_at` or schedule purge `WHERE created_at < now() - interval '90 days'`.
- [ ] **[LOW]** Expose a single `Gson` `@Bean` (or drop Gson if SDK doesn't require it — Jackson already managed).

### Spec / docs sync.
- [ ] **[LOW]** README:42-43 + 0_TO_WORKING.md:86 conflate the 5 manifest-declared webhooks with the 5 no-op routes; scope count omits `REPORTS_READ`. Rewrite as "5 declared + 5 no-op routes; 7 scopes".
- [ ] **[LOW]** "No-op" webhook routes (`WebhookController.java:65-103`) actually trigger reconcile via `ClockifyWebhookService.java:73-78`. Either return 200 immediately or update SPEC.md §1.

### Build / infra polish
- [ ] **[LOW]** `.gitignore` — add `*.iws`, `.vscode/`, `.classpath`, `.project`, `.settings/`, `HELP.md`, `dependency-reduced-pom.xml`, `*.jfr`, `hs_err_pid*.log`.
- [ ] **[LOW]** `Dockerfile` JVM flags — add `-XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -XX:MaxRAMPercentage=75.0`.
- [ ] **[LOW]** Populate `/actuator/info` via `spring-boot-maven-plugin build-info` or remove from exposure.
- [ ] **[LOW]** `LifecycleController` — add `DataIntegrityViolationException → 200` (idempotent install) and a generic catch-all.

### Bugs (low)
- [ ] **[LOW]** `ClockifyResetWindowPlanner.java:49-54` — silently coerces invalid `dayOfMonth` (0/-1). Reject explicitly.
- [ ] **[LOW]** `ClockifyJson.findFirstString` deep-recursive lookup of `workspaceId`/`projectId` finds nested unrelated values. Resolve fields by known location per event type.
- [ ] **[LOW]** `ProjectUsageService.extractSummaryTotalTime:242` uses unchecked `getAsLong()` on possible decimal seconds.

---

## Architecture verdict

Refactor, don't rewrite. Layering is clean (controller → service → repository), domain is mostly Spring-free, only one cycle (`@Lazy`), no SQL string concatenation. Pain is **(1) the file-backed→Postgres swap is half-done**, **(2) misleading `FileBacked*` naming + fake `IOException`**, **(3) HTTP `JsonObject` leaking into services**, **(4) duplicate per-controller error mapping**, **(5) zero security headers / no `SecurityFilterChain`**. A focused 1–2 day pass on those clears most of P0/P1. `EstimateGuardService` (~450 LOC) is approaching the size where you'd split orchestrator from calculator.

---

## What is correctly aligned (do NOT touch)

- Manifest schema 1.3, key `stop-at-estimate`, single admin sidebar `/sidebar`, 4 lifecycle routes, 5 declared webhooks, 7 scopes, settings tabs/groups.
- Per-route webhook token equality enforced.
- Hard-stop on either time OR budget cap with snapshot-based restoration.
- Sidebar uses only protected APIs after stripping `auth_token` (history.replaceState).
- No observe-only mode, no widget, no extra UI surfaces.
- All outbound Clockify calls use `X-Addon-Token` (never `Authorization`).
- Installation tokens encrypted at rest via `TextEncryptor` (AES) with env-only key/salt.
- TokenVerificationService enforces RS256 (rejects `alg=none`, `HS256`).
- Production code contains no hardcoded `clockify.me` URLs.
- All repositories are Spring Data JPA derived queries (no SQL injection surface).

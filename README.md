# Stop @ Estimate

> Clockify marketplace add-on that **stops running timers and locks projects the moment either a time estimate or a budget estimate is reached**, and automatically restores them when the cap condition clears.

Built for workspaces where an estimate is a *commitment*, not a soft target. The add-on enforces the cap on the server side — users can't keep racking up tracked time or cost past the number the admin set.

---

## At a glance

| | |
|---|---|
| **Status** | Runtime-verified end-to-end on the Clockify developer workspace. Ready for private install on Pro workspaces. |
| **Tests** | 61 / 61 passing (`./mvnw test`) |
| **Manifest** | schema `1.3`, PRO, 1 admin sidebar, 4 lifecycle, 5 webhooks, 7 scopes, 2 settings |
| **Hard-stop mechanism** | Webhook (instant) + scheduler tick (≤60s backstop). See [How enforcement works](#how-enforcement-works). |
| **Audit trail** | Every lock / unlock / cutoff / timer-stop persisted in `guard_events`, exposed via `GET /api/guard/events` |
| **Data store** | PostgreSQL (not file-backed). Installation tokens + webhook tokens AES-256 at rest. |
| **Webhook delivery** | Confirmed working on production Pro workspaces (verified 2026-04-19). The developer workspace `69bda6b317a0c5babe34b4ff` shows zero delivery attempts — Clockify-side state issue isolated to that one tenant; see [Known issues](#known-issues). Production cutover takes the webhook path; the 60s scheduler tick remains the durable backstop on either workspace. |

---

## How enforcement works

Two independent paths notice timer activity. Either one triggers the same guard engine, so the addon is correct even if one path is temporarily unavailable.

```
┌─────────────────────────────┐    ┌─────────────────────────────────────┐
│  Webhook (Clockify → addon) │    │  Scheduler (addon → Clockify)       │
│  Near-instant reaction      │    │  60s tick, polling Reports API       │
│  /webhook/new-timer-started │    │  Everything in scheduler:tick /      │
│  /webhook/timer-stopped     │    │  scheduler:due-job source tag        │
│  /webhook/new-time-entry    │    └───────────────┬─────────────────────┘
│  /webhook/time-entry-updated│                    │
│  /webhook/time-entry-deleted│                    │
└──────────────┬──────────────┘                    │
               │                                   │
               ▼                                   ▼
        ┌────────────────────────────────────────────┐
        │  EstimateGuardService.reconcileProject()   │
        │    ├── load usage from Reports API         │
        │    ├── load running timers                 │
        │    ├── assess vs ProjectCaps               │
        │    ├── exceeded?   → stopRunningTimer      │
        │    │                  lockProject          │
        │    ├── lockNow?    → same                  │
        │    └── cutoff?     → syncCutoffJobs        │
        │                                            │
        │  Every outcome writes guard_events row     │
        │  (LOCKED / UNLOCKED / CUTOFF_SCHEDULED /   │
        │   TIMER_STOPPED) with reason + source      │
        └────────────────────────────────────────────┘
```

### Enforcement semantics

| Scenario | Behavior |
|---|---|
| Running timer crosses the cap | Timer end is set at the exact cap boundary (`cutoffAt.toString()`), not where the scheduler happened to notice. |
| Manual entry pushes the project over the cap | The entry itself lands (addon can't retro-reject it — it's already posted), but the project locks immediately after. Worst case = cap + one user's one manual entry. |
| Project already over cap | Subsequent timer starts are stopped on the next tick; new manual entries are blocked at the Clockify API layer because membership has been pruned. |
| Cap crosses back under (admin raises the estimate, reset window) | Next reconcile unlocks, restores memberships from `project_lock_snapshots`, writes `UNLOCKED` event. |
| Budget cap basis | Billable amount = `hourlyRate × billable duration` (per-user `hourlyRate` first, project `defaultHourlyRate` fallback) plus expenses when `includeExpenses` is set. Non-billable entries do not accrue against the budget cap (they still accrue against the time cap if one exists). Internal cost (`costRate`) is not used. |

---

## Manifest contract (locked)

- Schema `1.3`
- 1 admin-only sidebar at `/sidebar` (displays current guard state and recent events)
- 4 lifecycle routes: `installed`, `deleted`, `status-changed`, `settings-updated`
- 5 manifest-declared webhooks (`NEW_TIMER_STARTED`, `TIMER_STOPPED`, `NEW_TIME_ENTRY`, `TIME_ENTRY_UPDATED`, `TIME_ENTRY_DELETED`). `PROJECT_UPDATED` and the four `EXPENSE_*` events are not declarable under schema 1.3; their cap-state changes are picked up by the 60s reconcile scheduler. See [SPEC.md §1](./SPEC.md) for the full rationale and the hibernated controller plumbing.
- 7 scopes: `TIME_ENTRY_READ/WRITE`, `PROJECT_READ/WRITE`, `USER_READ`, `EXPENSE_READ`, `REPORTS_READ`
- 2 admin-only settings: `enabled` (checkbox, default `true`), `defaultResetCadence` (dropdown: `NONE` / `WEEKLY` / `MONTHLY` / `YEARLY`)
- `minimalSubscriptionPlan: "PRO"`
- Hard-stop behavior only — no observe-only mode

---

## Stack

- Java 21, Spring Boot 3.3, Maven
- Clockify `com.cake.clockify:addon-sdk:1.5.3`
- PostgreSQL 16, Flyway, Spring Data JPA
- Thymeleaf sidebar shell + vanilla JavaScript
- Docker + docker-compose for parity

---

## Repository layout

| Area | Contents |
|---|---|
| `src/main/java` | Spring Boot service: manifest, lifecycle, webhooks, guard engine, lock service, scheduler, protected APIs, async install retrier |
| `src/main/resources` | `application.yml`, sidebar Thymeleaf + JS, Flyway migrations, Clockify RS256 public key |
| `src/test` | Unit tests for guard engine + `@SpringBootTest` coverage for manifest, auth, lifecycle, webhooks, protected APIs, async backoff |
| `Dockerfile`, `docker-compose.yml`, `.env.example` | Local + production build and run |
| [`PRD.md`](./PRD.md), [`SPEC.md`](./SPEC.md), [`TECH_STACK.md`](./TECH_STACK.md), [`ARCHITECTURE.md`](./ARCHITECTURE.md), [`IMPLEMENTATION.md`](./IMPLEMENTATION.md) | Locked product + technical contract |
| [`0_TO_WORKING.md`](./0_TO_WORKING.md) | Runbook from clean machine to a working private install for validation |
| [`PUBLISH_CHECKLIST.md`](./PUBLISH_CHECKLIST.md) | Marketplace submission readiness checklist |
| [`TODO.md`](./TODO.md) | Prioritized engineering backlog, with a resolved-items log at the top |

---

## Security posture

- **JWT verification** — RS256 against the published Clockify public key, with hard checks on `iss`, `type`, `sub`, numeric `exp`, and when present `nbf` / `iat`.
- **Tokens at rest** — installation tokens and per-workspace webhook tokens are AES-256 encrypted via `spring-security-crypto` `Encryptors.text`. `addon.encryption-key-hex` and `addon.encryption-salt-hex` have **no defaults**; the app fails fast at startup if either is unset or matches the well-known checked-in example values in `.env.example`.
- **Per-route webhook tokens** — a token registered for one webhook path cannot authenticate a different webhook path. Constant-time compare.
- **Protected APIs** — every `/api/*` call requires `X-Addon-Token`. `auth_token` is stripped from the iframe URL before any backend call.
- **Local assets only** — the sidebar serves all CSS/JS from this origin; no third-party CDN dependencies.
- **URL allow-list** — `ClockifyUrlNormalizer` enforces HTTPS and `*.clockify.me` host; a forged JWT cannot redirect outbound calls.
- **Deduplication** — inbound webhooks are short-circuited on replay by `(event_id, signature_hash)` uniqueness in `webhook_events`. Rows are purged hourly past a 24h retention window by a ShedLock-guarded scheduler.
- **Bounded outbound calls** — every Clockify call is subject to 5s connect / 10s read timeouts and one `Retry-After`-honoring retry on HTTP 429, so a stalled or rate-limiting Clockify cannot pin webhook threads.

---

## Running locally

See [`0_TO_WORKING.md`](./0_TO_WORKING.md) for the full runbook. Summary:

```bash
docker compose up -d postgres

cp .env.example .env
# Generate real encryption material into .env
openssl rand -hex 32   # APP_ENCRYPTION_KEY_HEX
openssl rand -hex 16   # APP_ENCRYPTION_SALT_HEX

export $(grep -v '^#' .env | xargs)

./mvnw -B test             # 61/61 green
./mvnw -B spring-boot:run  # starts on :8080
```

Expose `http://localhost:8080` over HTTPS (ngrok, cloudflared) and install privately in a Clockify Pro workspace for smoke validation. The manifest is at `GET {public-url}/manifest`.

### One-command sanity checks

```bash
curl -s http://localhost:8080/manifest | jq '{schema:.schemaVersion, webhooks:(.webhooks|length), scopes:(.scopes|length), plan:.minimalSubscriptionPlan}'
# → {"schema":"1.3","webhooks":5,"scopes":7,"plan":"PRO"}

curl -s http://localhost:8080/actuator/health
# → {"status":"UP"}
```

---

## Known issues

### External — Clockify addon webhook delivery stuck on the dev workspace only

**Production status:** webhook deliveries arrive normally on production Pro workspaces (verified 2026-04-19) — `NEW_TIMER_STARTED` / `NEW_TIME_ENTRY` etc. fire as soon as the user-side action lands, and the addon's per-route handlers receive the signed POST and reconcile within seconds. The 5 manifest-declared webhooks are the primary low-latency path in prod.

**Dev workspace `69bda6b317a0c5babe34b4ff`:** Previously observed that addon-registered webhooks never received deliveries. 
This happens if the addon responded with an error (e.g. `401 Unauthorized` cascading from API unresponsiveness) or timed out during the initial `POST /lifecycle/installed` exchange. In such cases, Clockify **silently aborts registering the webhooks** specified in the manifest, even though the application reports active.

**Resolution**: To fix missing webhooks, simply click **Save** in the Clockify Developer Console or fully uninstall and reinstall the add-on to force a new attempt to register manifest webhooks.

**Impact on this addon**: Minimal. In prod the webhook path drives near-instant reconcile; if webhooks fail or get stuck, the 60s scheduler tick acts as a durable backstop (≤60s from breach) to stop timers.

### Internal — see [TODO.md](./TODO.md) for the full backlog

The 2026-04-18 pass closed: `guard_events` writes, webhook `event_type` persistence, regression tests for two runtime fixes, URI-tag log noise, install-time 401 backoff. Full table at the top of `TODO.md`.

---

## Publishing to the Clockify Marketplace

See [`PUBLISH_CHECKLIST.md`](./PUBLISH_CHECKLIST.md). Key items:

- The Pro plan gate is fixed at publish time.
- Submission requires a reviewer-accessible install plus brand assets, a privacy/security summary, and validation evidence across at least two Pro workspaces.
- Budget-cap enforcement requires workspace **Cost Analysis** enabled (time-cap works without it).

---

## Completion gates

- [x] `./mvnw -B test` — 61/61 green
- [x] `GET /manifest` — schema `1.3`, `minimalSubscriptionPlan == "PRO"`, 1 admin sidebar, 4 lifecycle, 5 webhooks, 7 scopes, 2 settings
- [x] `GET /actuator/health` — `UP`, including database connectivity
- [x] Private install on a Pro workspace succeeds and persists workspace state
- [x] Hard-stop and reconcile flows verified against a real project with caps — timer end set at cap boundary, memberships pruned to snapshot, `guard_events` rows landed
- [x] Webhook deliveries confirmed working on production Pro workspaces (2026-04-19); dev-tenant unstick (Clockify-side, draft in `SUPPORT_TICKET.md`) is no longer release-blocking
- [ ] Submission checklist in [`PUBLISH_CHECKLIST.md`](./PUBLISH_CHECKLIST.md) complete

# Stop @ Estimate

> Clockify marketplace add-on that stops running timers and locks projects the moment either a time estimate or a budget estimate is reached, and restores them when the cap condition clears.

Server-side enforcement: once a project hits its cap, contributors can't keep tracking time or cost against it until an admin raises the estimate, a reset window reopens, or earlier entries are edited down.

---

## At a glance

| | |
|---|---|
| **Status** | Private install validated on a Pro workspace. Hard-stop, restore, and webhook paths verified against live data. |
| **Tests** | 202 / 202 passing (`./mvnw -B test`); CI runs unit + Testcontainers jobs on ubuntu-latest. |
| **Manifest** | schema `1.3`, PRO, 1 admin sidebar, 4 lifecycle, 5 webhooks, 8 scopes, 2 settings |
| **Hard-stop mechanism** | Webhook (instant) + scheduler tick (≤60s backstop). See [How enforcement works](#how-enforcement-works). |
| **Audit trail** | Every lock / unlock / cutoff / timer-stop persisted in `guard_events`, exposed via `GET /api/guard/events` |
| **Data store** | PostgreSQL (not file-backed). Installation tokens + webhook tokens AES-256 at rest. |
| **Webhook delivery** | Confirmed working on production Pro workspaces (verified 2026-04-19). The 60s scheduler tick remains the durable backstop. |

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
- 8 scopes: `TIME_ENTRY_READ/WRITE`, `PROJECT_READ/WRITE`, `USER_READ`, `EXPENSE_READ`, `REPORTS_READ`, `WORKSPACE_READ` (workspace timezone lookup)
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
| [`SPEC.md`](./SPEC.md), [`ARCHITECTURE.md`](./ARCHITECTURE.md) | Locked product + technical contract |

---

## Security posture

- **JWT verification** — RS256 against the published Clockify public key, with hard checks on `iss`, `type`, `sub`, numeric `exp`, and when present `nbf` / `iat`.
- **Tokens at rest** — installation tokens and per-workspace webhook tokens are AES-256 encrypted via `spring-security-crypto` `Encryptors.delux` (AES-256-CBC, random 16-byte IV per value — non-deterministic). `addon.encryption-key-hex` and `addon.encryption-salt-hex` have **no defaults**; the app fails fast at startup if either is unset or matches the well-known checked-in example values in `.env.example`.
- **Per-route webhook tokens** — a token registered for one webhook path cannot authenticate a different webhook path. Constant-time compare.
- **Protected APIs** — every `/api/*` call requires `X-Addon-Token`. `auth_token` is stripped from the iframe URL before any backend call.
- **Local assets only** — the sidebar serves all CSS/JS from this origin; no third-party CDN dependencies.
- **URL allow-list** — `ClockifyUrlNormalizer` enforces HTTPS and `*.clockify.me` host; a forged JWT cannot redirect outbound calls.
- **Deduplication** — inbound webhooks are short-circuited on replay by `(event_id, signature_hash)` uniqueness in `webhook_events`. Rows are purged hourly past a 24h retention window by a ShedLock-guarded scheduler.
- **Bounded outbound calls** — every Clockify call is subject to 5s connect / 10s read timeouts and one `Retry-After`-honoring retry on HTTP 429, so a stalled or rate-limiting Clockify cannot pin webhook threads.

---

## Running locally

Summary:

```bash
docker compose up -d postgres

cp .env.example .env
# Generate real encryption material into .env
openssl rand -hex 32   # APP_ENCRYPTION_KEY_HEX  (>= 64 hex chars / 256 bits)
openssl rand -hex 32   # APP_ENCRYPTION_SALT_HEX (>= 64 hex chars / 256 bits)

export $(grep -v '^#' .env | xargs)

./mvnw -B test             # 202/202 green
./mvnw -B spring-boot:run  # starts on :8080
```

Expose `http://localhost:8080` over HTTPS (ngrok, cloudflared) and install privately in a Clockify Pro workspace for smoke validation. The manifest is at `GET {public-url}/manifest`.

### One-command sanity checks

```bash
curl -s http://localhost:8080/manifest | jq '{schema:.schemaVersion, webhooks:(.webhooks|length), scopes:(.scopes|length), plan:.minimalSubscriptionPlan}'
# → {"schema":"1.3","webhooks":5,"scopes":8,"plan":"PRO"}

curl -s http://localhost:8080/actuator/health
# → {"status":"UP"}
```


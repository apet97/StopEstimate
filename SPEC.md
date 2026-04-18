# Stop @ Estimate Specification

**Status:** implementation-ready  
**Date:** 2026-04-16

## 1. Manifest Contract

- `schemaVersion`: `1.3`
- `key`: `stop-at-estimate`
- `name`: `Stop @ Estimate`
- `description`: Stops timers and locks projects when time or budget estimates are reached.
- `baseUrl`: runtime-injected HTTPS value
- `minimalSubscriptionPlan`: `PRO` — the add-on declares `requireProPlan()` in the manifest. Project time/budget estimates and the Reports API surfaces it relies on are PRO-tier features; the SDK builder enforces this at startup.

### Components

- One sidebar component
- Label: `Stop @ Estimate`
- Path: `/sidebar`
- Access level: admins only

### Lifecycle

- `POST /lifecycle/installed`
- `POST /lifecycle/deleted`
- `POST /lifecycle/status-changed`
- `POST /lifecycle/settings-updated`

### Webhooks

Manifest schema `1.3` restricts the declarable webhook-event enum to 22 events; it does **not**
include `PROJECT_UPDATED`, `PROJECT_DELETED`, or any `EXPENSE_*` event. Those events exist in the
runtime Webhooks API (`POST /v1/workspaces/{id}/webhooks`) but cannot be declared in a 1.3
manifest. Stop @ Estimate therefore:

- declares the 5 directly-supported events in the manifest, and
- picks up project-updated and expense-driven cap changes via the periodic reconcile scheduler
  (default 60s), which bounds enforcement latency for those event classes.

#### Declared in manifest (schema 1.3)

- `POST /webhook/new-timer-started`   — event `NEW_TIMER_STARTED`
- `POST /webhook/timer-stopped`       — event `TIMER_STOPPED`
- `POST /webhook/new-time-entry`      — event `NEW_TIME_ENTRY`
- `POST /webhook/time-entry-updated`  — event `TIME_ENTRY_UPDATED`
- `POST /webhook/time-entry-deleted`  — event `TIME_ENTRY_DELETED`

#### Reconciled only via scheduler (not declarable in 1.3)

- `PROJECT_UPDATED` (estimate or cap changes on the project)
- `EXPENSE_CREATED`, `EXPENSE_UPDATED`, `EXPENSE_DELETED`, `EXPENSE_RESTORED`
  (budget-cap relevant when `includeExpenses` is set)

The HTTP handlers for the 5 undeclared events remain registered in `WebhookController` but
receive no traffic under schema 1.3. They are retained deliberately so that a future schema
upgrade (or runtime registration via `POST /v1/workspaces/{id}/webhooks`) can re-enable them
without re-introducing the controller plumbing. Any behavioral change to these handlers must
re-enter review — they are not dead code.

### Scopes

- `TIME_ENTRY_READ`
- `TIME_ENTRY_WRITE`
- `PROJECT_READ`
- `PROJECT_WRITE`
- `USER_READ`
- `EXPENSE_READ`
- `REPORTS_READ` — required by the Clockify Reports API calls that drive guard summaries
  (summary report for tracked time + labor cost, expense report when `includeExpenses` is set)

### Structured Settings

- `enabled`
  - checkbox
  - default `true`
  - workspace-admin editable
  - master switch for enforcement and reconcile activity
- `defaultResetCadence`
  - single-select dropdown
  - allowed values: `NONE`, `WEEKLY`, `MONTHLY`, `YEARLY`
  - workspace-admin editable
  - fallback cadence when project estimate metadata does not define a usable reset interval

## 2. Auth And Security Rules

- Verify Clockify JWTs with RS256 using the Clockify public key.
- Reject tokens whose `sub` does not match `stop-at-estimate`.
- Validate issuer, type, expiry, workspace context, and route context.
- Read `backendUrl`, `reportsUrl`, theme, language, and workspace context from verified claims.
- Use `X-Addon-Token` for protected addon APIs.
- Never send installation tokens to the browser.
- Remove `auth_token` from sidebar URLs after extraction.
- Webhook and lifecycle verification must fail closed on missing or invalid signature/token.

## 3. Request Surface

### Public Endpoints

- `GET /manifest`
  - return manifest JSON
- `GET /sidebar`
  - return iframe page
- `GET /actuator/health`
  - readiness/liveness

### Protected Addon APIs

- `GET /api/context`
  - sidebar bootstrap payload
  - requires `X-Addon-Token`
- `GET /api/guard/projects`
  - return workspace guard summary and current project list
  - requires `X-Addon-Token`
- `POST /api/guard/reconcile`
  - force reconcile for known workspace projects
  - requires `X-Addon-Token`

### Internal Processing

- Lifecycle and webhook routes are backend-only operational endpoints.
- Scheduled cutoff processing runs in the app and is not user-facing.

## 4. Persistence Model

Use PostgreSQL with Flyway. All records are workspace-scoped.

### `installations`

Store one row per workspace with:

- `workspace_id`
- `addon_id`
- `installation_token_enc`
- `backend_url`
- `reports_url`
- `status`
- `enabled`
- `default_reset_cadence`
- `installed_at`
- `updated_at`

### `webhook_registrations`

Store one row per installed webhook registration with:

- `workspace_id`
- `event_type`
- `route_path`
- `webhook_token_enc`
- `created_at`
- `updated_at`

### `project_lock_snapshots`

Store original project state so unlock can restore it:

- `workspace_id`
- `project_id`
- `original_is_public`
- `original_memberships_json`
- `original_user_groups_json`
- `lock_reason`
- `locked_at`
- `updated_at`

### `cutoff_jobs`

Store pending cutoff jobs for running timers:

- `id`
- `workspace_id`
- `project_id`
- `user_id`
- `time_entry_id`
- `cutoff_at`
- `created_at`

### `guard_events`

Store operational event history:

- `id`
- `workspace_id`
- `project_id`
- `event_type`
- `guard_reason`
- `source`
- `payload_fingerprint`
- `outcome`
- `created_at`

#### Observability — what is written

`EstimateGuardService` writes one row per user-visible side effect at each of the four outcome
points in `reconcileProject` and `processDueJobs`:

- `LOCKED` — a new project lock was executed; `guard_reason` is the cap that drove it
  (`TIME_CAP_REACHED` / `BUDGET_CAP_REACHED`).
- `UNLOCKED` — a previously-locked project was unlocked. `guard_reason` is `BELOW_CAPS` when
  usage dropped under cap, `NO_ACTIVE_CAPS` when the project's caps were removed, or null when
  the installation was deactivated or enforcement was disabled.
- `CUTOFF_SCHEDULED` — a non-null cutoff timestamp was persisted into `pending_cutoff_jobs` for
  one or more running timers; `guard_reason` is the cap that set the bound.
- `TIMER_STOPPED` — the guard called `POST /v1/workspaces/{id}/user/{userId}/time-entries/end`
  on at least one running timer.

`source` mirrors the reconcile trigger (for example `webhook:NEW_TIMER_STARTED`,
`scheduler:tick`, `api:manual`, or `lifecycle:installed:retry-2`). `payload_fingerprint` is the
literal string `scheduler` for scheduler- and API-triggered reconciles, and a 16-char SHA-256
prefix of the webhook JSON body otherwise. Rows are read back via `GET /api/guard/events`
(newest first, `X-Addon-Token` required, `limit` and `projectId` are optional query params).

## 5. Guard Logic

### Threshold Rules

- A project is guarded if it has an active time estimate, an active budget estimate, or both.
- Breach condition is true when:
  - tracked time is greater than or equal to the time cap
  - or tracked budget is greater than or equal to the budget cap

### Time Usage

- Read project usage from Clockify reports APIs.
- Include in-progress timer contribution when computing the effective cutoff timestamp.

### Budget Usage

- Use project hourly-rate data plus billable tracked duration to derive the billable amount; this matches Clockify's `budgetEstimate` semantics for both `MANUAL` and `PER_TASK` budget types. Non-billable time entries do not accrue against the budget cap.
- Read the billable amount from the summary report's `totals[].amounts[]` entries of type `EARNED` or `BILLED` (Clockify uses both labels depending on workspace features). Fall back to top-level `totalBillable` then `totalAmount`. `COST` entries are explicitly ignored — they reflect internal cost and are unrelated to budget cap math. The summary filter must not request a specific amounts subset (that requires Cost Analysis and 400s with code 501 on workspaces without it).
- Include expenses when the project cap semantics require them.
- If a budget cap exists but the required rate data is missing for a billable running timer, treat the project as requiring reconcile attention and fail closed for enforcement decisions that depend on ambiguous budget math.

### Cutoff Timing

- If the breach is already true at reconcile time, stop immediately.
- If there is exactly one running timer on the project when a new timer starts, compute the earliest cutoff timestamp from the remaining time or remaining budget.
- Persist pending jobs for active timers so the scheduler can stop them on time.

## 6. Lock And Unlock Behavior

### Lock

When a project breaches a cap:

1. Save a lock snapshot if one does not already exist.
2. Stop running timers for the project.
3. Set project visibility to non-public.
4. Replace project access with only:
   - owners
   - workspace admins
   - project managers on that project

### Unlock

On reconcile, if the project no longer breaches a cap:

1. Restore original direct memberships.
2. Restore original user groups.
3. Restore original visibility.
4. Delete the active lock snapshot.
5. Remove obsolete cutoff jobs.

## 7. Reconcile Sources

Reconcile must run on:

- lifecycle install
- lifecycle status change
- lifecycle settings update
- each **declared** webhook event that can affect project usage or cap state (the 5 timer and
  time-entry events listed in §1)
- scheduled cutoff processing, which also carries the reconcile load for the 5 undeclared event
  classes (`PROJECT_UPDATED`, `EXPENSE_*`) — latency is bounded by the scheduler interval
  (default 60s)
- manual reconcile API call

## 8. Failure Modes

- Missing or invalid JWT: return `401` and do nothing else.
- Missing workspace installation: return success for webhook receipt only if the event can be safely ignored; otherwise log and skip.
- Unknown project in webhook payload: log, store guard event, do not crash.
- Clockify backend `403` or `404`: record event and surface in reconcile/sidebar state.
- Database unavailable: fail request or scheduled cycle loudly and return health failure.
- Partial lock mutation failure: keep the snapshot, log the failure, and require follow-up reconcile to converge state.

## 9. Tests Claude Must Implement

- Manifest contains the required component, lifecycle routes, webhook count, and settings.
- Lifecycle tokens reject workspace mismatch.
- Webhook signature or event-type mismatch fails closed.
- `GET /api/guard/projects` rejects invalid `X-Addon-Token`.
- Time-cap breach stops timers and locks project.
- Budget-cap breach stops timers and locks project.
- Reconcile restores original state after cap clears.
- Disabled or inactive workspaces do not enforce.

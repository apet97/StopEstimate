# Stop @ Estimate Architecture

## 1. System Topology

```text
Clockify
  ├─ lifecycle events
  ├─ webhook events
  └─ sidebar iframe load
         │
         ▼
HTTPS Spring Boot service
  ├─ manifest controller
  ├─ lifecycle controller
  ├─ webhook controller
  ├─ sidebar controller
  ├─ protected /api/* controllers
  ├─ scheduler
  ├─ Clockify backend client
  ├─ Clockify reports client
  └─ Postgres repositories
         │
         ▼
PostgreSQL
  ├─ installations
  ├─ webhook_registrations
  ├─ project_lock_snapshots
  ├─ cutoff_jobs
  └─ guard_events
```

## 2. Main Subsystems

### Manifest And Iframe Layer

- `GET /manifest` serves the Clockify add-on definition.
- `GET /sidebar` renders the admin iframe.
- Sidebar JS extracts `auth_token`, strips it from the URL, and fetches `/api/context`.

### Lifecycle Layer

Responsibilities:

- verify lifecycle token
- persist installation state
- capture webhook registration tokens
- update enabled/default reset cadence on settings changes
- clean up workspace state on delete
- trigger initial or follow-up reconcile

### Webhook Layer

Responsibilities:

- verify webhook signature and event type
- resolve workspace installation
- extract project identity from the payload where applicable
- trigger targeted reconcile or due-job refresh
- record operational events

### Guard Engine

Split across two classes:

- `CutoffPlanner` — pure decision math. Inputs: `ProjectState`,
  `ProjectUsage`, running entries, `now`, source, optional payload.
  Output: an `Assessment` (breach reason, planned cutoff timestamp,
  `lockNow` flag, planned reason). No clock, no DB, no Clockify calls.
- `EstimateGuardService` — side-effect orchestration. Reads project
  state, calls the planner, and turns the `Assessment` into the right
  combination of stop-timer / lock / unlock / schedule-cutoff /
  no-op. Owns the BUG-11 partial-failure invariants in
  `processDueJob` (wrap each side effect, reinsert on stop-fail, log
  on lock-fail).

Combined responsibilities:

- read project state
- determine active caps
- compute current usage
- determine breach reason
- compute earliest cutoff timestamp for active timers
- synchronize pending cutoff jobs
- decide whether to lock now, unlock now, or remain unchanged

### Lock Service

Responsibilities:

- persist original project snapshot before first lock
- stop active timers
- update project visibility
- replace memberships with the allowed admin/manager set
- restore the original snapshot on unlock

### Scheduler

Responsibilities:

- process pending cutoff jobs
- run periodic reconcile of known guarded projects
- delete stale jobs once timers have ended or a project is unlocked

## 3. Data Flow

### Sidebar Load

1. Clockify opens `/sidebar?auth_token=...`.
2. Frontend extracts and removes `auth_token`.
3. Frontend calls `/api/context` with `X-Addon-Token`.
4. Frontend loads `/api/guard/projects`.
5. Admin can trigger `/api/guard/reconcile`.

### Install

1. Clockify sends `POST /lifecycle/installed`.
2. Backend verifies token and payload.
3. Backend stores installation token, URLs, settings, and webhook registration tokens.
4. Backend runs initial reconcile.

### Webhook

1. Clockify sends a signed webhook event.
2. Backend verifies the signature.
3. Backend extracts workspace and project context.
4. Backend runs reconcile for the affected project or workspace.
5. Reconcile either leaves the project alone, schedules/stops cutoff jobs, locks it, or unlocks it.

### Scheduled Cutoff

1. Scheduler loads due `cutoff_jobs`.
2. Backend stops the relevant timers.
3. Backend ensures the project lock is still applied.
4. Backend records the result and clears the processed jobs.

## 4. Locking Model

The lock is not a custom Clockify feature. It is implemented by mutating native project visibility and project access:

- set `isPublic=false`
- retain only owners, workspace admins, and project managers in project memberships

This is why the snapshot table is mandatory: the add-on must be able to restore the exact original state later.

## 5. Restore Model

Restore happens during reconcile when the current project state no longer breaches a cap. Reconcile is authoritative. The addon should not attempt speculative restore from a timer-stopped event alone.

## 6. Failure Containment

- Signature/auth failures return `401` and do nothing else.
- Missing installation or project context should not crash the process.
- Scheduler failures must be logged and surfaced through health or operational logs.
- Partial project mutation failures must leave enough data in Postgres to retry safely.

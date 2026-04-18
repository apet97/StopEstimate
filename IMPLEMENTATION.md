# Stop @ Estimate Implementation Plan

This is the exact build order Claude Code should follow.

## Phase 1. Read And Lock Inputs

1. Read every file in this folder in the order defined by [README.md](./README.md).
2. Read the source-of-truth folders in this order:
   - `../../ClockifyAddonAIPack/01-canonical-docs/`
   - `../../ClockifyAddonAIPack/03-ai-pack/starter-java-sdk/`
   - `../../ClockifyAddonAIPack/05-reference-addons/clockify-estimate-guard/`
   - `../../ClockifyAddonAIPack/05-reference-addons/clockify-http-actions/`
   - `../../ClockifyAddonAIPack/02-openapi-and-events/`
3. Do not ask the user for implementation details that those folders already answer.

## Phase 2. Scaffold The Project

1. Copy the Java starter shape, not the file-backed storage model.
2. Rename the addon identity to:
   - key `stop-at-estimate`
   - name `Stop @ Estimate`
3. Keep the starter’s proven auth, iframe, lifecycle, and `/api/context` patterns intact.
4. Replace starter sample webhook wiring with the fixed ten-webhook set.

## Phase 3. Replace Prototype Storage With Postgres

1. Add PostgreSQL datasource config.
2. Add Flyway.
3. Create migrations for:
   - `installations`
   - `webhook_registrations`
   - `project_lock_snapshots`
   - `cutoff_jobs`
   - `guard_events`
4. Implement repositories or DAO services for those tables.

## Phase 4. Implement Auth And Lifecycle

1. Verify Clockify lifecycle and iframe JWTs with RS256.
2. Enforce `sub == stop-at-estimate`.
3. Persist installation token and verified URLs on install.
4. Persist webhook registration tokens from the install payload.
5. Handle:
   - install
   - delete
   - status change
   - settings update
6. Reconcile after install, status change, and settings update.

## Phase 5. Implement Clockify Clients

1. Backend API client:
   - get project
   - list in-progress time entries
   - stop running timer
   - update project visibility
   - update project memberships
   - list/filter users for allowed lock access
2. Reports API client:
   - fetch usage summaries for tracked time
   - fetch budget/expense totals needed for cap evaluation
3. Do not hardcode Clockify URLs. Use verified claim URLs.

## Phase 6. Implement Guard Engine

1. Build the domain model for:
   - project caps
   - project usage
   - running time entries
   - guard reason
   - project guard summary
2. Implement breach detection:
   - time cap reached
   - budget cap reached
3. Implement cutoff planning for in-progress entries.
4. Implement reset-window handling using `defaultResetCadence` as fallback.
5. Persist pending cutoff jobs in Postgres.

## Phase 7. Implement Lock And Unlock Services

1. Before first lock, save the original project visibility and memberships.
2. Stop active timers when a breach occurs.
3. Lock the project by retaining only:
   - owners
   - workspace admins
   - project managers
4. Unlock by restoring the exact saved snapshot.
5. Make lock/unlock idempotent.

## Phase 8. Implement Webhooks And Scheduler

1. Wire the fixed ten webhook routes.
2. Each route must:
   - verify the signature
   - verify expected event type
   - resolve workspace installation
   - trigger targeted reconcile
3. Add scheduler jobs to:
   - process due cutoff jobs
   - periodically reconcile known guarded projects

## Phase 9. Implement Addon APIs And Sidebar

1. Keep `/api/context` from the starter pattern.
2. Add:
   - `GET /api/guard/projects`
   - `POST /api/guard/reconcile`
3. Build a sidebar that shows:
   - addon/workspace state
   - guarded projects
   - lock state
   - breach reason
   - manual reconcile button
4. Keep frontend minimal and server-rendered.

## Phase 10. Test And Harden

1. Unit test manifest, auth, lifecycle, guard logic, cutoff planning, lock/unlock, and restore.
2. Add MVC/integration tests for protected routes and failure cases.
3. Add health endpoint and structured logging.
4. Add Dockerfile and local production-like run path.

## Phase 11. Verify Against The Runbook

1. Follow [0_TO_WORKING.md](./0_TO_WORKING.md) exactly.
2. Do not claim completion until:
   - tests pass
   - local app runs
   - manifest private install works
   - sidebar loads
   - hard-stop flow is verified

## Final Check

If a detail is still unclear after reading the local docs and the source-of-truth folders, ask the user only about product intent. Do not ask the user for manifest, auth, webhook, or starter details that the repository already contains.

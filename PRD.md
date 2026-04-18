# Stop @ Estimate PRD

**Status:** implementation-complete, marketplace-publish-pending
**Date:** 2026-04-16
**Audience:** product, engineering, security review, marketplace submission reviewers

## 1. Product Summary

Stop @ Estimate is a Clockify admin add-on that enforces project estimate limits across an entire workspace. When a project reaches either its tracked time estimate or its budget estimate, the add-on stops active timers and locks the project so normal contributors cannot continue tracking time against it. It is intended for **publication to the Clockify Marketplace** and therefore must satisfy Clockify's marketplace requirements for auth, install eligibility, security, and operational hygiene.

The product promise is:

- Respect project caps automatically across every guarded project in the workspace.
- Stop overspend and overrun as close to the threshold as the Clockify API permits.
- Preserve enough project state to restore access cleanly when the cap condition clears.
- Ship as a reusable marketplace add-on that a Pro-plan workspace admin can install from a listing without bespoke onboarding.

## 2. Problem

Clockify projects can carry time and budget expectations, but workspaces still overrun them because native enforcement is advisory. Admins need an add-on that reacts to timers, time entries, project changes, and expense updates fast enough to stop further tracking once a project hits its cap — without fragile manual reconciliation.

## 3. Users

| Persona | Need | What Stop @ Estimate provides |
| --- | --- | --- |
| Workspace owner | Prevent estimate overrun across projects | Automatic cap enforcement and restore |
| Workspace admin | See which projects are guarded or locked and recover without losing state | Admin sidebar with current state and manual reconcile |
| Project manager | Keep operational access during a lock | Project managers remain on locked projects |
| Contributor | Avoid logging more time after cap | Running timers stop, project access is restricted, restore happens when the cap clears |

## 4. Goals

- Enforce project limits when either time or budget estimate is reached.
- Stop running timers at the effective cutoff time.
- Lock project access for normal contributors without losing the original project state.
- Restore original project visibility and memberships when the guard condition clears.
- Give admins a single operational sidebar for status and manual reconcile.
- Be publishable to the Clockify Marketplace — i.e., install cleanly from a listing on any eligible workspace, with no operator-side bootstrap steps beyond providing encryption material.

## 5. Non-Goals

- User-facing warning banners or soft-warning UX in v1.
- Widget support.
- Additional tabs beyond one sidebar.
- Observe-only mode (hard-stop is the only behavior).
- Custom rule engine beyond project estimates already present in Clockify.
- Per-project override UI in v1.

Note: earlier drafts treated "marketplace submission polish" as a non-goal. That has been **superseded** by the decision to publish. The implementation now satisfies the publish-time requirements (see §7 and `PUBLISH_CHECKLIST.md`).

## 6. Core Product Behavior

### 6.1 Guarded Project Discovery

The add-on watches projects that have active time or budget caps and keeps a snapshot of their current usage and lock state.

### 6.2 Hard Stop

When usage crosses either threshold:

1. Determine whether the breach is time-based, budget-based, or both.
2. Stop any running timers for that project at the computed cutoff time.
3. Lock the project by:
   - making it non-public
   - replacing project membership with only owners, workspace admins, and project managers
4. Save the original visibility and membership state so it can be restored later.

### 6.3 Restore

The project is unlocked when a later reconcile shows the cap condition is no longer true. Triggers:

- the estimate value increased
- time or expense records were removed or edited down
- the reset window reopened the project

Unlock means restoring original direct memberships, original user groups, original public/private visibility, and removing the active lock snapshot.

### 6.4 Sidebar

Admin-only. Shows:

- addon status and current workspace summary
- guarded project summaries (breach reason, lock state, next cutoff timing)
- manual reconcile action

## 7. Product Constraints

- Clockify manifest schema `1.3`.
- `minimalSubscriptionPlan: "PRO"` — install eligibility is locked at publish time.
- Fixed webhook set and fixed scopes per [SPEC.md](./SPEC.md).
- Settings are limited to `enabled` (checkbox) and `defaultResetCadence` (dropdown) — both admin-only.
- Installation tokens and webhook tokens must never be exposed to the browser.
- All Clockify URLs derive from verified JWT claims, never hardcoded.
- The add-on must support multi-workspace isolation in a single deployment.
- Encryption material (`APP_ENCRYPTION_KEY_HEX`, `APP_ENCRYPTION_SALT_HEX`) must be supplied per deployment; the service refuses to start with any well-known default value.
- Webhook authentication is strict per-route: the token registered for route A cannot authenticate route B.

## 8. Success Criteria

### Product outcomes

- A project that reaches a time cap is stopped and locked without admin intervention.
- A project that reaches a budget cap is stopped and locked without admin intervention.
- Admins can manually reconcile and see accurate lock state from the sidebar.
- Restores are clean and do not lose original visibility or memberships.
- A Pro-plan workspace can install the published add-on from the marketplace with no bespoke configuration.

### Operational outcomes

| Metric | Target |
| --- | --- |
| Lifecycle install success (private and marketplace) | 100 percent in smoke and review runs |
| Timer stop flow | no silent failure in covered tests |
| Lock/restore correctness | original state restored in all covered scenarios |
| Secrets in frontend or logs | zero tolerated |
| Local-to-private-install path | reproducible from docs |
| Publish checklist completion | 100 percent before submission |

## 9. Launch Gates

Stop @ Estimate is not ready for submission until all of the following are true:

1. Private install works from `GET /manifest` and persists workspace state.
2. Lifecycle install, delete, status, and settings flows are covered by automated tests.
3. JWT verification is fail-closed; per-route webhook token equality is enforced.
4. Time-cap and budget-cap flows are verified in a live Pro workspace.
5. Lock snapshot save and restore are verified in a live Pro workspace.
6. PostgreSQL migrations are reproducible from a clean database.
7. Local run, HTTPS exposure, and smoke path are documented and verified on at least two workspaces.
8. `PUBLISH_CHECKLIST.md` is satisfied end to end, including brand assets and reviewer access plan.

## 10. References

- Technical contract: [SPEC.md](./SPEC.md)
- Stack choice: [TECH_STACK.md](./TECH_STACK.md)
- System design: [ARCHITECTURE.md](./ARCHITECTURE.md)
- Build order: [IMPLEMENTATION.md](./IMPLEMENTATION.md)
- Runbook: [0_TO_WORKING.md](./0_TO_WORKING.md)
- Publish readiness: [PUBLISH_CHECKLIST.md](./PUBLISH_CHECKLIST.md)

# Stop @ Estimate — Marketplace Publish Checklist

Readiness gate for submitting Stop @ Estimate to the Clockify Marketplace. This document is **operational** — it lists what must be true before submission, what evidence to capture, and what is deliberately left for a human to verify against the live marketplace.

Nothing in this checklist has been completed by the automated build. Treat each item as unverified until a human signs off in the margin.

---

## 1. Code / build readiness

All items should be confirmed from the current commit.

- [ ] `./mvnw -B clean verify` is green on a fresh checkout (expected: 17+ tests passing).
- [ ] Manifest assertions hold at runtime:
  - `schemaVersion == "1.3"`
  - `key == "stop-at-estimate"`
  - `minimalSubscriptionPlan == "PRO"`
  - 1 sidebar with `accessLevel == "ADMINS"` at path `/sidebar`
  - 4 lifecycle routes, 5 webhooks, 8 scopes
  - 2 admin-only settings: `enabled`, `defaultResetCadence`
- [ ] Reviewer workspace has **Cost Analysis** enabled if budget-cap enforcement is part of the validation plan. Time-cap enforcement works without it; budget-cap enforcement derives labor cost from the summary report, which only returns `amounts` on workspaces with Cost Analysis enabled.
- [ ] `GET /actuator/health` returns `UP` including database check.
- [ ] App refuses to start when `APP_ENCRYPTION_KEY_HEX` / `APP_ENCRYPTION_SALT_HEX` are missing or set to any checked-in example value.
- [ ] RS256 verification rejects: missing header, non-RS256 alg, bad signature, wrong `sub`, expired `exp`.
- [ ] Webhook per-route token equality: a token for route A is rejected on route B (covered by `webhookTokenFromDifferentRouteIsRejected`).

## 2. Security review

- [ ] No third-party CDN resources in the sidebar; all CSS/JS served from this origin.
- [ ] Installation token and per-workspace webhook tokens are AES-256 encrypted at rest (`Encryptors.delux` — AES-256-CBC, random 16-byte IV per value — non-deterministic).
- [ ] Encryption material is provided exclusively via environment; no defaults ship in the jar.
- [ ] `auth_token` is removed from the iframe URL by the sidebar JS before any backend call (`history.replaceState`).
- [ ] HTTPS-only base URL (enforced by the marketplace manifest contract; confirm your deploy terminates TLS correctly).
- [ ] Secrets are not logged. Grep confirms no `installationToken`, `authToken`, or raw `X-Addon-Token` values in logs.
- [ ] Scopes are the minimum needed (`TIME_ENTRY_READ/WRITE`, `PROJECT_READ/WRITE`, `USER_READ`, `EXPENSE_READ`, `REPORTS_READ`). Any marketplace reviewer comment requesting scope reduction must be re-evaluated before submission.

## 3. Operational readiness

- [ ] Persistent PostgreSQL (not a container volume on an ephemeral host) with backups.
- [ ] Flyway migrations apply cleanly from an empty database.
- [ ] Docker image builds with `docker build -t stop-at-estimate .` and runs with `/actuator/health` green.
- [ ] Encryption key rotation plan documented (re-encrypt `installation_token_enc` and `webhook_token_enc` columns on key change).
- [ ] Deployment supplies `APP_ENCRYPTION_KEY_HEX` and `APP_ENCRYPTION_SALT_HEX` via secret manager, not `.env` files baked into images.
- [ ] Scheduler runs on exactly one replica (or a distributed lock has been introduced if multi-replica is planned).
- [ ] Uptime + error alerting wired to `/actuator/health` and log aggregation.

## 4. Functional validation matrix (two workspaces, both on Pro)

Run the full matrix on **two different Pro workspaces** to catch workspace-scoping bugs. Capture screenshots for each row.

| # | Scenario | Workspace A | Workspace B | Notes |
| --- | --- | --- | --- | --- |
| 1 | Private install succeeds; `/lifecycle/installed` stores encrypted token + webhook rows | ☐ | ☐ | Verify DB row and that the decrypted token is never in logs |
| 2 | Sidebar loads for a workspace admin | ☐ | ☐ | Non-admin users must not see the sidebar option |
| 3 | Time-cap breach stops running timer + locks project | ☐ | ☐ | Project becomes non-public; memberships = owners + admins + PMs |
| 4 | Budget-cap breach stops running timer + locks project | ☐ | ☐ | Check cost rate present; otherwise document fail-closed behavior |
| 5 | Reconcile after cap clears restores original memberships, user groups, visibility | ☐ | ☐ | Snapshot row deleted after restore |
| 6 | Reset window reopens project (WEEKLY / MONTHLY / YEARLY where applicable) | ☐ | ☐ | `defaultResetCadence` fallback exercised |
| 7 | `PROJECT_UPDATED` webhook with a higher estimate clears the lock | ☐ | ☐ | End-to-end webhook trigger |
| 8 | Expense webhook (CREATED/UPDATED/DELETED/RESTORED) flips budget-cap state | ☐ | ☐ | Four sub-rows, one per event |
| 9 | Settings update disables the add-on; running timers are not stopped and existing locks are lifted | ☐ | ☐ | `enabled=false` must suppress enforcement |
| 10 | Status change to INACTIVE suppresses enforcement on both workspaces independently | ☐ | ☐ | Multi-workspace isolation check |
| 11 | `/lifecycle/deleted` removes all workspace rows (installations, webhooks, snapshots, cutoff jobs) | ☐ | ☐ | Workspace B remains unaffected when A is uninstalled |
| 12 | Cross-route webhook token from workspace A rejected on workspace B | ☐ | ☐ | Both workspaceId claim and per-route token must match |

## 5. Admin-only visibility checks

- [ ] Sidebar is not visible to non-admin users in the Clockify UI (manifest `accessLevel == "ADMINS"`).
- [ ] Settings are not editable by non-admins (each setting carries `accessLevel == "ADMINS"`).
- [ ] If an admin loses admin role mid-session, the sidebar stops appearing after refresh. (Clockify enforces this server-side; confirm manually.)

## 6. Uninstall / rollback verification

- [ ] Uninstall from a workspace via the Clockify UI:
  - calls `/lifecycle/deleted` with a valid lifecycle token
  - removes the row from `installations`
  - cascades to `webhook_registrations`
  - removes `project_lock_snapshots` (projects remain in their current state — Clockify does not automatically restore visibility on uninstall)
  - removes `cutoff_jobs`
- [ ] Service rollback: downgrading to a prior image with a narrower DB schema is not supported (Flyway is forward-only). Document the roll-forward strategy.
- [ ] Emergency kill-switch: setting `enabled=false` via `/lifecycle/settings-updated` (or the Clockify settings UI) stops enforcement immediately and unlocks existing locked projects on the next reconcile tick.

## 7. Listing assets required by Clockify

- [ ] Short product name: `Stop @ Estimate`
- [ ] Tagline (≤ 80 chars): e.g. *"Stop timers and lock projects automatically when caps are reached."*
- [ ] Long description (≤ Clockify's listing limit): covers the product promise, scope justification, admin-only nature, and data handling.
- [ ] Icon: PNG, square, ≥ 512×512, on transparent or neutral background.
- [ ] Screenshots: at least three at Clockify's required resolution. Recommended shots:
  1. Sidebar with one guarded project in `OK` state.
  2. Sidebar with one guarded project in `LOCKED` state, showing `TIME_CAP_REACHED` reason.
  3. Sidebar after manual reconcile restores a project.
- [ ] Short demo video (optional but strongly recommended for marketplace): 45–90 seconds showing install → breach → lock → reconcile.
- [ ] Privacy / data handling statement: lists what is stored (workspace id, addon id, encrypted installation/webhook tokens, project lock snapshots, cutoff jobs, guard events), where it is stored (your PostgreSQL), and how it is deleted (`/lifecycle/deleted`).
- [ ] Support contact: email or URL the Clockify review team can reach.
- [ ] Terms-of-service and privacy-policy URLs if the listing requires them.

## 8. Reviewer access plan

- [ ] Production deployment URL ready and stable (or a dedicated staging deployment reachable from the reviewer's location).
- [ ] A Clockify test workspace pre-populated with a project that has a time estimate *and* a project with a budget estimate, ready to demonstrate lock + reconcile.
- [ ] Admin credentials for the reviewer (never the production workspace admin's real credentials — create a dedicated reviewer account).
- [ ] Smoke test script or README snippet the reviewer can follow step-by-step in ≤ 10 minutes.
- [ ] Runbook for restoring the reviewer's test workspace after the review (unlock any projects, remove the installation).

## 9. Submission metadata

- [ ] Manifest URL is the production (not ngrok) HTTPS URL.
- [ ] Minimal subscription plan declared in the listing matches the manifest (`PRO`). **This is immutable after publish.**
- [ ] Webhook list in the listing matches the manifest 1:1.
- [ ] Scopes list in the listing matches the manifest 1:1.
- [ ] Changelog for v1.0 prepared.

## 10. What is NOT covered by this checklist

- Clockify's own marketplace review process (turnaround, questions, required edits).
- Living SLAs for the hosted deployment.
- Marketing launch collateral beyond what Clockify requires for the listing.
- Localization beyond the sidebar shell; the sidebar inherits theme + language from the Clockify JWT claims but no string catalog is shipped.
- A load test — the scheduler is expected to stay under typical small-workspace load; a load test is required only if you intend to serve many large workspaces from a single replica.

---

## Evidence to attach to the submission ticket

1. Full text output of `./mvnw -B clean verify` on the release commit.
2. JSON of `GET /manifest` from the production URL.
3. Signed-off copy of §4 matrix, with attached screenshots per row.
4. Confirmation that at least one uninstall + reinstall cycle succeeded on both workspaces.
5. `grep` evidence that no installation or webhook token appeared in a production log sample.

## Known limits of this repo's automated readiness

- The automated test suite covers unit-level and Spring Boot integration flows with H2. **It does not execute a live Clockify install**; that path requires ngrok/production and a human-operated workspace.
- The checklist has not been executed. Every box above is `☐` until a human ticks it.
- `PRD.md` §9 launch gates 4, 5, 7, and 8 explicitly require live-workspace evidence that this automated build cannot produce.

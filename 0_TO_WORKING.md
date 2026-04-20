# Stop @ Estimate: 0 To Working

Runbook from a clean machine to a locally running Stop @ Estimate add-on ready for private install on a Clockify **Pro-plan** workspace. Pro-workspace install is the validation step that gates marketplace submission — see [PUBLISH_CHECKLIST.md](./PUBLISH_CHECKLIST.md) for the full submission flow.

## 1. Prerequisites

- Java 21
- Docker Desktop (or compatible engine) for PostgreSQL parity
- Maven wrapper (ships with the repo)
- GitHub Packages access for the Clockify Java `addon-sdk`
- `ngrok` or equivalent HTTPS tunnel
- A Clockify **Pro-plan** workspace (required — the manifest declares `minimalSubscriptionPlan: "PRO"` and lifecycle install on a Free-plan workspace will be rejected by Clockify)

## 2. Maven / GitHub Packages access

The Clockify `addon-sdk` artifact comes from GitHub Packages. Ensure `~/.m2/settings.xml` contains a `github` server entry with a PAT that has `read:packages`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PAT</password>
    </server>
  </servers>
</settings>
```

## 3. Local database

Use the provided `docker-compose.yml`:

```bash
docker compose up -d postgres
```

If port `5432` is already bound by a local Postgres (e.g. `brew services` postgres), either stop it or remap the container port. The database is named `stop_at_estimate` with role `stop_at_estimate`.

## 4. Required environment variables

Copy `.env.example` to `.env` and fill in **real** values. The app will refuse to start if encryption material is missing or left at the checked-in example values.

```bash
cp .env.example .env

# Generate per-deploy encryption material — never reuse the .env.example values
openssl rand -hex 32   # paste as APP_ENCRYPTION_KEY_HEX in .env (>= 64 hex chars)
openssl rand -hex 32   # paste as APP_ENCRYPTION_SALT_HEX in .env (>= 64 hex chars)

# Export into the current shell
export $(grep -v '^#' .env | xargs)
```

Variables consumed by the app:

| Variable | Purpose |
| --- | --- |
| `SERVER_PORT` | HTTP port (default 8080) |
| `ADDON_KEY` | Manifest key — must stay `stop-at-estimate` |
| `ADDON_NAME` / `ADDON_DESCRIPTION` / `ADDON_SIDEBAR_LABEL` | Manifest metadata |
| `ADDON_BASE_URL` | HTTPS URL Clockify will call — set to the ngrok URL during local testing |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Postgres connection |
| `APP_ENCRYPTION_KEY_HEX` | AES-256 key for installation + webhook tokens (≥ 64 hex chars, not the example value) |
| `APP_ENCRYPTION_SALT_HEX` | AES-256 salt (≥ 32 hex chars, not the example value) |
| `CLOCKIFY_CUTOFF_INTERVAL_MS` | Scheduler interval (default 60000) |

## 5. Run tests first

```bash
./mvnw -B test
```

Expected: 17+ tests passing across manifest structure, lifecycle auth, webhook per-route token enforcement, guard engine, and reset-window math.

## 6. Start the app

```bash
./mvnw -B spring-boot:run
```

Confirm:

- App starts cleanly (no `IllegalStateException` about encryption material).
- Flyway migrations apply (`flyway_schema_history`, `installations`, `webhook_registrations`, `project_lock_snapshots`, `cutoff_jobs`, `guard_events`).
- `GET /manifest` returns valid JSON with `schemaVersion == "1.3"`, `key == "stop-at-estimate"`, `minimalSubscriptionPlan == "PRO"`, 1 admin-only sidebar, 4 lifecycle routes, 5 webhooks, 7 scopes, 2 settings.
- `GET /actuator/health` returns `{"status":"UP"}`.

## 7. Expose HTTPS

```bash
ngrok http 8080
```

Copy the HTTPS forwarding URL, set it as `ADDON_BASE_URL` in your shell, and restart the app so the manifest reflects the new URL.

## 8. Private install on a Pro workspace

1. Open `GET /manifest` in a browser and confirm the fields in §6.
2. In a Clockify **Pro-plan** workspace, use the "install private add-on" flow with your manifest URL.
3. Confirm `POST /lifecycle/installed` returns 200 and the workspace row lands in the `installations` table (installation token is encrypted).
4. Open the sidebar. Confirm:
   - `auth_token` is stripped from the URL by the sidebar JS.
   - `/api/context` returns 200 with `X-Addon-Token`.
   - `/api/guard/projects` returns a list (may be empty if no caps).

## 9. Hard-stop smoke

Before claiming success, verify this end-to-end path on the live Pro workspace:

1. Create or choose a project with a time estimate and optionally a budget estimate.
2. Start a timer on that project.
3. Lower the estimate below current tracked time (or let the timer run past the cap).
4. Confirm the timer is stopped at the cutoff time.
5. Confirm the project is non-public and only owners / admins / project managers remain on it.
6. Open the sidebar. Confirm the project appears as `LOCKED` with the correct `reason`.
7. Edit tracked time or expenses so the cap is no longer breached.
8. Trigger "Manual Reconcile" from the sidebar.
9. Confirm original visibility and memberships are restored; the lock snapshot is gone.

## 10. Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup fails with `addon.encryption-key-hex ... must be set` | Env not exported into this shell, or still using the `REPLACE_WITH_...` placeholder |
| Startup fails with `... is set to a checked-in example value` | You kept the `.env.example` default — generate a fresh key/salt |
| Private install returns 403 from Clockify | Your workspace is not on the Pro plan |
| Webhook returns 403 `webhook_token_mismatch` | Signature JWT does not match the token stored for that exact route |
| `/api/*` returns 401 | `X-Addon-Token` missing, not RS256, wrong `sub`, or expired |
| Timers not stopping / Scheduler stalled | ShedLock timestamp was stored with a timezone offset causing future lockouts (often caused by unhandled 401s pinning threads). Run `DELETE FROM shedlock;` and restart. |
| Webhooks not arriving at all | If `POST /lifecycle/installed` returned an error or timed out initially, Clockify silently skips registering webhooks. Fix by clicking **Save** in your Developer Console or reinstalling. |

## 11. Source-of-truth references

If something is still ambiguous after this runbook, inspect in order:

1. `../../ClockifyAddonAIPack/01-canonical-docs/`
2. `../../ClockifyAddonAIPack/03-ai-pack/starter-java-sdk/`
3. `../../ClockifyAddonAIPack/05-reference-addons/clockify-estimate-guard/`
4. `../../ClockifyAddonAIPack/05-reference-addons/clockify-http-actions/`
5. `../../ClockifyAddonAIPack/02-openapi-and-events/`

## 12. Next step: marketplace submission

Once the smoke path in §9 passes on at least two Pro workspaces, move to [PUBLISH_CHECKLIST.md](./PUBLISH_CHECKLIST.md).

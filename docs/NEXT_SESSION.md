# Next-session kickoff — stop@estimate

Paste this as the opening message for the next Claude Code session working
on `stop@estimate`. It's self-contained; don't assume prior chat memory.

---

Context: Clockify add-on `stop@estimate` at
`/Users/15x/Downloads/WORKING/addons-me/stop@estimate`. Java 21 + Spring
Boot 3.3.5 + Postgres + Flyway + Thymeleaf + vanilla JS. `main` has 22
merged PRs; unit tests 181/181 via `./mvnw -B test`. CI on ubuntu-latest
(unit + Testcontainers) is GREEN end-to-end.

READ FIRST: `CLAUDE.md`. Non-negotiables (summary):

- schema `1.3`, manifest key `stop-at-estimate`
- `X-Addon-Token` header, not `Authorization`
- RS256 JWT verify, numeric `exp` required
- `backendUrl` host allowlist `*.clockify.me`, port 443 or absent
- installation tokens backend-only, never in frontend or logs
- PostgreSQL only (no file-backed storage)
- no observe-only mode, no extra UI surfaces

## Workflow constraints

- Direct push to `main` blocked. One branch + one PR per concern.
- Merge via `gh pr merge N --merge`, then pull main, delete local+remote
  branch.
- Before every merge: `gh pr view N --json mergeable,mergeStateStatus`.
  CI is a real gate — don't merge on UNSTABLE. Use `gh pr checks N --watch`
  after push.
- `./mvnw -B test` after each change; stay at 181+ green.
- Testcontainers IT suite is skipped on this laptop (colima Docker API
  < docker-java 3.4.0 minimum). CI on ubuntu-latest runs it. Don't fight
  local infra.
- `.env` already populated and gitignored. If Postgres stopped:
  `brew services start postgresql@16`, `psql -U 15x -d stop_at_estimate`.
- Tunnel URL rotates per restart: `cloudflared tunnel --url http://localhost:8080`
  and update `ADDON_BASE_URL` before booting.

## What landed in the previous session (don't redo)

- **#19** Drop `implements Serializable` from 12 model records. JPA
  composite-key inner classes (`ProjectLockSnapshotEntity.Key`,
  `WebhookEventEntity.Key`) intentionally kept — required by `@IdClass`.
- **#20** CI auth fix. GitHub Packages Maven registries need explicit
  auth; the workflow now pulls `com.cake.clockify:addon-sdk` via a
  `GH_PACKAGES_TOKEN` repo secret (PAT with `read:packages`) wired through
  `actions/setup-java` server-auth. `server-id: github` must match
  `<repository><id>github</id>` in `pom.xml`. If CI is red with 401,
  the secret is missing or expired.
- **#21** `ClockifyInfrastructureConfiguration` moved from `service/` to
  `config/`. Pure relocation; component scan picked it up either way.
- **#22** Clockify HTTP exceptions (`ClockifyRequestAuthException`,
  `ClockifyAccessForbiddenException`, `ClockifyBackendForbiddenException`)
  moved from `service/` to `api/` alongside `ClockifyApiException`. **No
  hierarchy change** — the two `RuntimeException` subclasses still do NOT
  extend `ClockifyApiException`. Reparenting was deferred because three
  catch sites (`ProjectLockService.java:87`, `ProjectLockService.java:153`,
  `WebhookController.java:124`) would change behavior if broadened.
  `service/InvalidAddonTokenException` stays in `service/` — our own
  auth error, not a Clockify response.

Before touching any item below, re-verify it against the current tree.
Earlier audit docs have been retired from the repo; rely on `git log`,
`grep`, and code reading rather than stale scratchpads. Paste `file:line`
evidence into PR bodies so the next reader doesn't have to repeat the
investigation.

## Work list for THIS pass, ranked

### High-value, safe

**H1. Shared `ClockifyException` base — behavioral audit required.**

PR #22 intentionally left `ClockifyRequestAuthException` and
`ClockifyAccessForbiddenException` extending `RuntimeException` directly,
not `ClockifyApiException`. Reparenting would change what three existing
`catch (ClockifyApiException)` blocks swallow:

- `ProjectLockService.java:87` and `:153` — fallback paths that retry
  membership calls. If an auth error bubbles there and gets caught, the
  fallback fails the same way and the `GlobalExceptionHandler` mapping
  never fires. Current behavior is correct by design; reparenting would
  break it.
- `WebhookController.java:124` — catches `ClockifyApiException` to emit a
  webhook error response. Broadening to auth/forbidden may be fine, but
  needs a test asserting the desired status/body.

Only do this PR if you tighten the catches first (narrow to specific
subtypes) OR cover the new behavior with failing-first tests. If neither
is clean, skip and document the decision in a code comment on
`ClockifyApiException` so future readers see why the hierarchy is split.

**H2. MockRestServiceServer tests for `classify()` in both clients.**

Targets:

- `api/ClockifyBackendApiClient.classify` (lines 385–405):
  401 → `ClockifyRequestAuthException`,
  403 → `ClockifyBackendForbiddenException`,
  429 → `ClockifyApiException`,
  others → `ClockifyApiException`.
- `api/ClockifyReportsApiClient.classify` (lines 100–117): same map.

These lock the contract PR #22 deliberately didn't touch. Small,
high-value, no production behavior change.

**H3. Silence "Using generated security password" startup warning.**

Cosmetic but noisy. Two options:

- `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`
  on `StopAtEstimateApplication`. Risk: if future code adds Spring
  Security user-details, the exclusion masks it.
- Provide an empty `InMemoryUserDetailsManager` bean. Lower risk,
  one-liner. **Recommend this.**

Pick one, document why, add a test that boots the context and asserts the
bean exists (or the auto-config stayed out).

### Judgment calls

**J1. Sealed `GuardOutcome permits Triggered, BelowCap, NoCaps`.**

Audit every `GuardReason` usage first. If consumers already
`switch (reason)`, sealed buys exhaustiveness checks at compile time. If
most consumers do `.name()` for logging (as `EstimateGuardService` does),
sealed costs more than it saves. Grep `GuardReason` across `src/` and
report before implementing.

**J2. Rename Clockify-prefixed services.** Reserve `Clockify*` for
adapters/clients. Candidates: `ClockifyResetWindowPlanner`,
`ClockifyWebhookService`. Only rename if the new name clarifies something
concrete. Pure bikeshed otherwise.

### Defer

- **R2** (explicit pending-unlock marker): only if a concrete failure
  scenario emerges that current idempotent retry doesn't cover.
- **I1** (separate actuator management port): deploy-model coordination.
- **I3** (Testcontainers upgrade past 1.20.3): blocked on local
  docker-java; CI doesn't need it.
- **T2 / T3** (`@DataJpaTest` / `@WebMvcTest` slice tests): only when
  touching those layers naturally.

## Before each PR

1. Re-verify target behavior with `grep`/`Read` against current tree.
   Paste `file:line` evidence in the PR body.
2. Branch, edit, `./mvnw -B test`, push, open PR, wait for CI green
   (`gh pr checks N --watch`), merge via `gh pr merge N --merge`.
3. `git checkout main && git pull && git branch -d <br> && git push origin --delete <br>`.

Start with **H2** (the `classify()` tests). Mechanical, safest first move,
and the coverage it adds makes any future reparenting (H1) provably
non-regressing. Then H1 if the catch-site audit comes out cleanly, then
H3. Evaluate J1/J2 only after those.

Go.

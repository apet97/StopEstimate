# CLAUDE.md — stop@estimate

Clockify add-on. Hard-stops timers and locks projects when a project's time
or budget estimate is reached.

## Stack

Java 21 · Spring Boot 3.3.5 · PostgreSQL 16 + Flyway · Thymeleaf · vanilla JS
sidebar · Testcontainers ITs · ShedLock-coordinated scheduler · Caffeine
caches.

## Read first

- `AGENTS.md` — implementation contract (manifest key, schema, scopes,
  hard rules). Treat as immutable unless the user explicitly approves a
  change.
- `SPEC.md` — product spec. Same rule.
- `ARCHITECTURE.md` — subsystem map and data flows.

`docs/` is reserved for living technical notes. Do not place historical
session notes there — they rot. Use `git log` and `gh pr list` for history.

## Non-negotiables

- Manifest schema `1.3`, key `stop-at-estimate`, `requireProPlan()` declared.
- `X-Addon-Token` header for every Clockify API call, never `Authorization`.
- RS256 JWT verify with the embedded public key. Numeric `exp` required;
  60s skew; 5min iat for sidebar tokens. Never trust unverified JWT bodies.
- `backendUrl` host allowlist `*.clockify.me`, port 443 or absent. Run
  every Clockify URL through `ClockifyUrlNormalizer`.
- Strip `auth_token` from the iframe URL after extraction.
- Installation tokens are backend-only. Never log them, never return
  them in responses, never send them to the iframe.
- PostgreSQL only. No file-backed storage. H2 has been removed.
- One admin sidebar; no observe-only mode; no widget; no extra UI surfaces.
- Default to no comments. Add one only when the WHY is non-obvious
  (hidden constraint, subtle invariant, workaround for a specific bug).

## Workflow

Direct push to `main` is **blocked**. One branch + one PR per concern.

```
git checkout -b <kind>/<slug>
# edit
./mvnw -B test                       # surefire 202+ green is the gate
git push -u origin <branch>
gh pr create --title "..." --body "..."
gh pr checks <N> --watch             # CI is a real gate
gh pr view <N> --json mergeable,mergeStateStatus
# refuse on UNSTABLE; merge only on CLEAN
gh pr merge <N> --merge --delete-branch
git checkout main && git pull --ff-only
```

PR body convention: paste `file:line` evidence for every claim — re-verify
against the current tree with `grep`/`Read` before writing the PR.

`gh` is authenticated as `apet97`. If a PR-create fails with "must be a
collaborator," run `gh auth switch --user apet97`.

## CI workflows

| Workflow | What it runs | Trigger |
|---|---|---|
| `test` | `./mvnw -B test` (surefire) | push/PR to main |
| (verify) | `./mvnw -B verify` (Testcontainers IT) | push/PR to main |
| `coverage` | `./mvnw -P coverage verify` + JaCoCo HTML | push/PR to main |
| `codeql` | java-kotlin security-and-quality query pack | push/PR to main + Thursday cron |
| `security-scan` | OWASP Dependency-Check (skips without `NVD_API_KEY`) | Monday cron, `pom.xml`/suppressions PR paths, manual |

Testcontainers IT is skipped locally on this Mac (colima). CI on
`ubuntu-latest` runs it. Don't fight local infra.

## Dependabot secret scope

`GH_PACKAGES_TOKEN` (`read:packages` on `clockify/addon-java-sdk`) MUST exist
at **both** Actions and Dependabot scopes — GitHub stores them separately.
If Dependabot Maven PRs fail with `MAVEN_PASSWORD: ` empty in CI logs, the
Dependabot-scope copy is missing or expired:

```
gh secret set GH_PACKAGES_TOKEN --app actions    --body "$TOKEN" --repo apet97/StopEstimate
gh secret set GH_PACKAGES_TOKEN --app dependabot --body "$TOKEN" --repo apet97/StopEstimate
gh secret list --app dependabot --repo apet97/StopEstimate
```

## Where things live

| Concern | Path |
|---|---|
| Manifest | `service/ManifestService` + `controller/ManifestController` |
| JWT verify | `service/TokenVerificationService` (hand-rolled RS256) |
| URL allowlist | `service/ClockifyUrlNormalizer` |
| Lifecycle | `service/ClockifyLifecycleService`, `controller/LifecycleController` |
| Webhook auth + dedup | `service/ClockifyWebhookService`, `controller/WebhookController` |
| Cutoff/lock decision math | `service/CutoffPlanner` (pure, stateless) |
| Side-effect orchestration | `service/EstimateGuardService` |
| Lock/restore | `service/ProjectLockService` |
| Scheduler + ShedLock | `scheduler/CutoffJobScheduler` |
| HTTP exception map | `api/ClockifyHttpClassifier` |
| Audit events | `service/GuardEventRecorder` |
| Migrations | `db/migration/V1_0_*.sql` |

## Common pitfalls

- Tests that load Spring context must extend `it/AbstractPostgresIT`.
  Singleton Testcontainer keeps the Spring TestContext cache valid across
  IT classes — don't `@Container` per class.
- `EstimateGuardService` has no method-wide `@Transactional` (BUG-06).
  Each collaborator commits its own small transaction; partial failure is
  acceptable because the next reconcile tick reconverges state.
- `processDueJob` BUG-11 invariants: wrap each side effect, reinsert on
  stop-fail, log on lock-fail. See `EstimateGuardService:329-353` and the
  asymmetric-failure tests at `EstimateGuardServiceProcessDueJobsTest:185,206`.
- HSTS is forced on every response — Tomcat sees HTTP behind cloudflared,
  so the usual "only over HTTPS" guard would suppress the header.
- All `RestClient` calls use URI templates so Micrometer tags by
  endpoint template, not resolved URL. Don't hand-build URI strings.
- 401 on a sidebar `/api/*` call should trigger a single
  `requestTokenRefresh()` with backoff (1s/2s/5s, then banner). Don't
  stack refresh requests.

## Test gate

`./mvnw -B test` must stay green before push. Current count is the floor —
new tests are welcome, removed tests need a justification in the PR body.

## Out of scope without explicit user approval

- Adding a widget, tab, send-invoice-to, or any second UI surface.
- Adding an observe-only mode.
- Removing `requireProPlan()`.
- Switching off `X-Addon-Token` in favor of `Authorization`.
- File-backed storage of any installation/secret state.
- Bumping Spring Boot to 4.x — that's deliberately scoped as its own
  multi-PR migration, not a casual upgrade.

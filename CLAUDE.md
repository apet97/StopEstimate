# stop@estimate — Claude guide

Clockify add-on. Hard-stops project timers when a time-budget or money-budget
estimate is reached. Java 21 + Spring Boot 3.3 + PostgreSQL + Flyway +
Thymeleaf + vanilla JS.

**Phase:** maintenance. Implementation is live; `./mvnw -B test` is 181/181
on `main`; CI (unit + Testcontainers, ubuntu-latest) is green end-to-end.

## Non-negotiables

Locked by manifest, spec, or security posture. Don't weaken without explicit
product approval.

- Manifest key `stop-at-estimate`, schema `1.3`.
- `requireProPlan()` stays. SPEC §1, PRD, README, and the manifest test all
  assume PRO.
- `X-Addon-Token` header for addon APIs — never `Authorization`.
- RS256 JWT verification; require numeric `exp`; validate `nbf`/`iat` when
  present.
- Never hardcode Clockify API URLs — derive from installation. Normalize
  `backendUrl` via `ClockifyUrlNormalizer`: HTTPS only, host must match
  `*.clockify.me`, port 443 or absent.
- Installation tokens stay backend-only. Strip `auth_token` from iframe URLs
  after extraction.
- PostgreSQL for all state. No file-backed storage.
- One admin sidebar. No widget, no observe-only mode, no extra UI surfaces.

## Workflow

- Direct push to `main` is blocked. One concern = one branch = one PR.
- Merge via `gh pr merge N --merge`, then
  `git checkout main && git pull && git branch -d <br> && git push origin --delete <br>`.
- `./mvnw -B test` after every change; must stay 181+ green.
- CI gates merges. Before merging, check
  `gh pr view N --json mergeable,mergeStateStatus` — don't merge on UNSTABLE.
  Use `gh pr checks N --watch` after push.
- Testcontainers locally are skipped (colima Docker API < docker-java 3.4.0
  minimum). CI runs them on ubuntu-latest.
- Never run destructive git operations (`reset --hard`, `push --force`,
  `branch -D`) without explicit user instruction.
- If a pre-commit hook fails, fix and re-commit; never bypass with
  `--no-verify`.

## CI auth (GitHub Packages)

`pom.xml` pulls `com.cake.clockify:addon-sdk` from
`maven.pkg.github.com/clockify/addon-java-sdk`. GitHub Packages Maven
registries require auth. The workflow reads a `GH_PACKAGES_TOKEN` repo
secret (classic PAT with `read:packages`) via `actions/setup-java`
server-auth. `server-id: github` in `.github/workflows/test.yml` matches
`<repository><id>github</id>` in `pom.xml`.

If CI fails with `401 Unauthorized` resolving the addon-sdk, the secret is
missing or expired — rotate the PAT and update the repo secret.

## Local boot

- `.env` (gitignored) carries secrets.
- Postgres: `brew services start postgresql@16`, then
  `psql -U 15x -d stop_at_estimate`.
- Tunnel URL rotates per restart:
  `cloudflared tunnel --url http://localhost:8080`, then update
  `ADDON_BASE_URL` before booting.

## Package layout

| Package       | Purpose                                                         |
| ------------- | --------------------------------------------------------------- |
| `api/`        | Clockify HTTP clients + their response-mapped exceptions.       |
| `config/`     | `@Configuration` beans: security, RestClients, properties.      |
| `controller/` | REST, webhook, and sidebar entry points.                        |
| `migration/`  | One-shot data migrations (e.g. token re-encryption).            |
| `model/`      | Domain records; JPA entities under `model/entity/`.             |
| `repository/` | Spring Data JPA repositories.                                   |
| `scheduler/`  | Timed reconciliation + cutoff-job firing.                       |
| `service/`    | Domain logic. Our own auth errors (`InvalidAddonTokenException`) live here. |
| `store/`      | Repository-backed persistence facades.                          |
| `util/`       | JSON helpers, small pure utilities.                             |
| `web/`        | `GlobalExceptionHandler` and web adapters.                      |

## Source lookup order

If an implementation detail is uncertain, inspect in this order before
asking:

1. `../../ClockifyAddonAIPack/01-canonical-docs/`
2. `../../ClockifyAddonAIPack/03-ai-pack/starter-java-sdk/`
3. `../../ClockifyAddonAIPack/05-reference-addons/clockify-estimate-guard/`
4. `../../ClockifyAddonAIPack/05-reference-addons/clockify-http-actions/`
5. `../../ClockifyAddonAIPack/02-openapi-and-events/`

Don't guess if the repo answers it. Ask only when product intent is still
ambiguous after checking.

## Domain references (read when relevant)

- `README.md` — elevator pitch.
- `PRD.md` — product contract.
- `SPEC.md` — feature spec, routes, scopes, lifecycle, webhook set.
- `TECH_STACK.md`, `ARCHITECTURE.md`, `IMPLEMENTATION.md` — design.
- `0_TO_WORKING.md` — local boot verification.
- `TODO.md`, `FOLLOWUP_TODO.md`, `SONNETTODO.md` — audit backlog. Partially
  stale; re-verify each item against the current tree before acting.
- `docs/NEXT_SESSION.md` — kickoff prompt for the next maintenance pass.

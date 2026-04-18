# Clockify Estimate Guard

Clockify add-on workspace generated from `devodox/clockify-addon-ai-pack/starter-java-sdk` and expanded with the near-full Estimate Guard behavior from the local Java reference.

## What It Includes

- manifest schema `1.3`
- one admin sidebar dashboard
- four lifecycle handlers
- ten webhook handlers for timer, time entry, project, and expense events
- file-backed installations, lock snapshots, and pending cutoff jobs under `.data/`
- fail-closed Clockify JWT verification
- protected admin APIs on `X-Addon-Token`
- scheduled cutoff processing and workspace reconcile

## Settings

- `enabled`
- `enforcementMode` with `OBSERVE_ONLY` and `ENFORCE`
- `defaultResetCadence` with `NONE`, `WEEKLY`, `MONTHLY`, and `YEARLY`

## Local Run

1. Source `.env.example`.
2. Set `ADDON_BASE_URL` to a reachable HTTPS URL.
3. Ensure GitHub Packages auth is configured in `~/.m2/settings.xml` for the Clockify `addon-sdk`.
4. Run `./mvnw spring-boot:run`.
5. Use `GET /manifest` for private installation.

## Test

```bash
./mvnw test
```

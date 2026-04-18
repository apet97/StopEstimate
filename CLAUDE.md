# CLAUDE.md

Use this folder as the full implementation contract for `stop@estimate`.

## Read First

Read these files in order before writing code:

1. `README.md`
2. `PRD.md`
3. `SPEC.md`
4. `TECH_STACK.md`
5. `ARCHITECTURE.md`
6. `IMPLEMENTATION.md`
7. `0_TO_WORKING.md`

## What You Are Building

Build a Clockify add-on with:

- folder `addons-me/stop@estimate`
- manifest key `stop-at-estimate`
- product name `Stop @ Estimate`
- one admin sidebar
- hard-stop enforcement when either a project time estimate or budget estimate is reached
- Java 21 + Spring Boot + PostgreSQL + Flyway + Thymeleaf + vanilla JS

This folder is docs-only right now. Your job is to turn it into a runnable implementation without changing the locked product decisions unless the user explicitly asks.

## Source Lookup Order

If any implementation detail is uncertain, inspect these folders in this exact order before asking the user:

1. `../../ClockifyAddonAIPack/01-canonical-docs/`
2. `../../ClockifyAddonAIPack/03-ai-pack/starter-java-sdk/`
3. `../../ClockifyAddonAIPack/05-reference-addons/clockify-estimate-guard/`
4. `../../ClockifyAddonAIPack/05-reference-addons/clockify-http-actions/`
5. `../../ClockifyAddonAIPack/02-openapi-and-events/`

Rules:

- Do not guess if the repo already contains the answer.
- Do not ask the user for manifest, auth, lifecycle, webhook, or starter details that those folders already define.
- Ask the user only when product intent is still ambiguous after checking the source folders.

## Non-Negotiable Rules

- Keep manifest key `stop-at-estimate`.
- Use schema `1.3`.
- Manifest declares `requireProPlan()` — do not drop without explicit product approval; SPEC §1, PRD, README, and the manifest test all assume PRO.
- Use only the fixed sidebar, lifecycle routes, scopes, settings, and webhook set from `SPEC.md`.
- Use `X-Addon-Token` for addon APIs.
- Never hardcode Clockify API URLs.
- Keep installation tokens backend-only.
- Verify Clockify JWTs with RS256 and validate claims (require numeric `exp`; validate `nbf`/`iat` when present).
- Normalize `backendUrl` through `ClockifyUrlNormalizer`: HTTPS only, host must match `*.clockify.me`.
- Strip `auth_token` from iframe URLs after extraction.
- Use PostgreSQL, not file-backed storage.
- Do not add an observe-only mode, widget, or extra UI surfaces.

## Implementation Order

1. Scaffold from the Java starter.
2. Replace storage with PostgreSQL + Flyway.
3. Implement auth and lifecycle.
4. Implement Clockify clients.
5. Implement guard logic and cutoff scheduling.
6. Implement lock/unlock restoration.
7. Implement webhook handlers.
8. Implement protected APIs and sidebar.
9. Implement tests.
10. Verify using `0_TO_WORKING.md`.

## Completion Standard

Do not claim completion until:

- tests pass
- the app runs locally
- `GET /manifest` works for private install
- the sidebar loads and uses protected APIs
- hard-stop and unlock flows are verified

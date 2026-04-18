# Review Feedback

The current implementation looks broadly functional and the local test suite passes, but it is not yet aligned with the clarified marketplace publication requirements. This review is based on the actual code and config in `addons-me/stop@estimate`, not on the implementation report alone.

## Findings

- `P1` The manifest is hardcoded to `requireFreePlan()` instead of `PRO`, which conflicts directly with the intended marketplace contract. This is a publication blocker because Clockify treats `minimalSubscriptionPlan` as a fixed marketplace property after publish, so shipping the wrong plan gate now can lock in the wrong install eligibility. See [AddonManifestConfiguration.java](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/java/com/devodox/stopatestimate/config/AddonManifestConfiguration.java:38).
- `P1` Webhook route verification is too permissive. `verifyStoredWebhookToken` first checks the expected token for the current route, but then falls back to accepting any stored webhook token value for the workspace. That weakens per-webhook authentication and allows a valid token from one registered route to satisfy a different route. See [ClockifyWebhookService.java](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/java/com/devodox/stopatestimate/service/ClockifyWebhookService.java:81).
- `P1` The app can boot with hardcoded default encryption key and salt values from config, which is unsafe for a marketplace-targeted multi-tenant deployment. Even though `SecurityConfig` validates format and length, it does not reject the checked-in defaults, so a misconfigured production deploy could encrypt all tenant tokens with known static secrets. See [application.yml](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/resources/application.yml:37) and [SecurityConfig.java](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/java/com/devodox/stopatestimate/config/SecurityConfig.java:19).
- `P2` The folder docs are stale relative to the actual implementation and to the clarified publish intent. The root docs still describe this as a docs-only or private-install-oriented package instead of a marketplace-targeted addon that must require `PRO` and be ready for broad workspace installation. See [README.md](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/README.md:1) and [0_TO_WORKING.md](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/0_TO_WORKING.md:1).
- `P3` The implementation removed observe-only mode, but the context/sidebar layer still exposes `OBSERVE_ONLY` fallback values. That is misleading for operators and leaves stale product language in the UI/API contract even though enforcement is now hard-wired. See [ContextApiController.java](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/java/com/devodox/stopatestimate/controller/ContextApiController.java:45), [sidebar.js](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/resources/static/js/sidebar.js:76), and [sidebar.html](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/resources/templates/sidebar.html:150).

## What Looks Good

- The sidebar is correctly admin-only at the manifest/component layer via `.allowAdmins()`. See [AddonManifestConfiguration.java](/Users/15x/Downloads/WORKING/addons-me/stop@estimate/src/main/java/com/devodox/stopatestimate/config/AddonManifestConfiguration.java:68).
- The persistence layer is consistently workspace-scoped, which is the right structural basis for multi-workspace and multi-admin usage from the marketplace.
- The local automated baseline is green: `./mvnw -B test` passed with 16 tests.

## Marketplace Readiness Gaps

- The addon is still tuned around private install and local smoke usage, not end-to-end marketplace publication.
- The addon folder does not yet contain marketplace-specific publication guidance, listing preparation notes, or an updated runbook for the clarified `PRO`-only contract.
- There is no explicit review evidence yet for marketplace listing assets, submission checklist alignment, or a live cross-workspace marketplace-style validation pass.

## Recommended Next Fixes

1. Switch the manifest from `requireFreePlan()` to `requireProPlan()`.
2. Require an exact route-token match for each webhook instead of accepting any stored workspace token.
3. Fail startup unless encryption secrets are supplied externally and differ from checked-in defaults.
4. Update the addon docs from docs-only/private-install framing to marketplace publication framing.
5. Remove stale observe-only references and add test coverage for `PRO` + admin-only manifest expectations.

## Evidence And Limits

- This review is code-and-config based and references the current implementation directly.
- It includes evidence from the local passing test run `./mvnw -B test`.
- It does not claim the addon fails to run locally.
- It does not substitute for a live Clockify marketplace submission, a real private-install smoke on multiple workspaces, or a full cross-workspace publish validation.

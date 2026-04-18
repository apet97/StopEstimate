# Clockify Support Ticket Draft — Addon Webhook Delivery Stuck

> Ready-to-paste body for a Clockify (CAKE.com Marketplace) support ticket about manifest-declared
> addon webhooks never receiving delivery attempts in a developer workspace, even from SEND TEST.

---

**Subject:** Addon webhook delivery stuck — 0 attempts on all 5 declared webhooks including SEND TEST

**Body:**

Hi Clockify team,

I have an addon installed on the Clockify developer environment where manifest-declared webhooks show zero delivery attempts for any event, including when I click SEND TEST from the Add-ons → Webhooks admin page. Lifecycle callbacks to the same `baseUrl` succeed cleanly, so the tunnel and routing are demonstrably healthy.

This looks identical to the class of issue previously resolved in https://forum.clockify.me/t/webhooks/21, where delete + recreate fixed it — but for **addon-registered** webhooks the admin UI doesn't offer a delete/recreate control other than uninstalling the addon, which I have already done cleanly (uninstall fires `/lifecycle/deleted` on our side; reinstall fires `/lifecycle/installed` and re-provisions everything) without unsticking delivery.

### Install context

- **Workspace:** `69bda6b317a0c5babe34b4ff` (Marketplace Workspace, Pro plan)
- **Addon:** `stop-at-estimate` — addon id `69e3e91cccf5b45c971e33af`
- **Base URL:** `https://stewart-machines-prize-resist.trycloudflare.com`
- **Declared webhooks (5):** `NEW_TIMER_STARTED`, `TIMER_STOPPED`, `NEW_TIME_ENTRY`, `TIME_ENTRY_UPDATED`, `TIME_ENTRY_DELETED` at `/webhook/*`

### What works

- `GET /manifest` returns schema 1.3 with 5 webhooks, 7 scopes, PRO
- `POST /lifecycle/installed`, `/lifecycle/deleted`, `/lifecycle/status-changed`, `/lifecycle/settings-updated` — all arrive, parse cleanly, return 200
- `GET /sidebar?auth_token=...` — arrives, serves the admin sidebar
- `GET /api/context`, `GET /api/guard/projects` — accessed from the sidebar with `X-Addon-Token`, return 200

### What doesn't work

- `POST /webhook/*` — **zero** arrivals ever, for any of the 5 declared events
- SEND TEST on the NEW_TIME_ENTRY webhook admin page → `Add-on webhook attempts: 0` (literally no attempt recorded)
- All 5 events show identical 0-attempt state

### Evidence

- Tomcat access log on the addon shows 0 `POST /webhook/*` entries from any source, while `POST /lifecycle/*` entries land correctly with status 200
- External probe to `/webhook/new-timer-started` with a bad signature returns 401 (our handler is alive and reachable through the tunnel)
- Manual reconcile via our fallback scheduler path successfully reads timers / stops them / locks projects, proving the addon is correctly installed and authenticated end-to-end against your REST API

### Request

Can you look at the webhook delivery state for this addon install on your side and unstick it? If there's a recommended procedure for addon webhooks (equivalent to delete-and-recreate for user webhooks), I'd be happy to follow it.

Thanks.

---

### Attachments to include

1. Screenshot of the Webhooks admin page for NEW_TIME_ENTRY showing `Add-on webhook attempts: 0` after clicking SEND TEST
2. (Optional) Export of our Tomcat access log for the 10 minutes around the SEND TEST click — shows only lifecycle POSTs, no webhook POSTs

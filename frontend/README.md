# GRND Loyalty frontend

React + TypeScript + Vite + Tailwind CSS + React Router. The GRND Jump engine,
controls, fixed 60 Hz physics, renderer, animation and original artwork remain in
place. Issue #31 connects its lifecycle to the real backend. Asset provenance is
in `src/assets/README.md`; backend contracts are in `../docs/architecture`.

## Local setup

Use Node.js 22.12+ and npm. Start PostgreSQL and the Java backend following
[`backend/README.md`](../backend/README.md), including the required local QR signing
configuration. Provision a CUSTOMER through the existing registration API; this
frontend does not add a registration form or seed customer accounts.

From `frontend/`:

```sh
npm ci
cp .env.example .env.local
# API_PROXY_TARGET defaults to http://127.0.0.1:8080; adjust only if the backend uses another port.
npm run dev
```

Open `/game`. A visitor is sent to `/login`; use the actual registered email and
password. There is no demo sign-in, localStorage auth token or Bearer JWT. Session
bootstrap reads `/api/v1/auth/me`, browser cookies accompany requests, and a game API
401 returns to the existing login route. After sign-in the requested route is restored.
Bootstrap network failures show retry instead of inventing a session.

`api/client.ts` is the shared same-origin client. It obtains an in-memory CSRF token
from `/api/v1/auth/csrf` before unsafe requests, refreshes it after login/logout,
clears stale CSRF on 401/403, includes cookies and parses Problem Details/Retry-After.
Requests have a 15-second timeout and unsafe requests are never automatically retried.
Normal UI uses allowlisted error copy, never raw internal server details.

Vite proxies `/api` through API_PROXY_TARGET; components use only relative API paths.
Production output is the repository `dist/` folder. A production same-origin gateway
must route `/api` to the backend and other React routes to `index.html`; a static-only
Sites deployment cannot provide this backend itself. Keep HttpOnly/Secure cookie
settings from the backend. No wildcard CORS or cross-site cookie workaround is added.
Never place credentials or signing secrets in VITE_* configuration.

## Game flow

- `api/game.ts` defines typed lives, session, score and summary DTOs and API methods.
- `features/game/hooks/useLives.ts` loads backend lives. Until it succeeds Play is
  disabled; a failed read never assumes three lives. The existing cup images reflect
  availableLives/maxLives. Recharge display anchors backend serverTime to monotonic
  `performance.now()`, so changing the device wall clock cannot grant lives.
- At countdown zero the client asks the backend again. It never increments lives,
  schedules a local refund, writes local lives or changes recharge timestamps.
- `useGameLifecycle.ts` keeps request state separate from the canvas. One deliberate
  Play action creates a UUID Idempotency-Key and waits for session creation before
  starting exactly one game loop. The response supplies remaining lives and recharge
  time without an unnecessary second GET. Only supported engine v1 is played.
- A network/timeout/temporary failure offers retry using the same start key. An
  ambiguous retry also reads the owned session: old replayed ACTIVE creation snapshots
  are not blindly played after expiration. Zero lives never starts a local run.
- The first game-over callback freezes one final safe integer and UUID key, stops
  gameplay and submits only `{ score }`. Repeated callbacks cannot submit another
  result. The result stays visible while saving; failed saves retry the same payload
  and key. Unresolved saves block a new run from replacing the pending operation.
- Accepted score data is server-owned. Personal best comes from game-summary on load
  and after acceptance. Summary failure alone does not block play. No game best or
  weekly score is persisted locally, and old localStorage lives, personal best and member authentication are ignored.
- SCORE_ALREADY_SUBMITTED can recover the existing owned accepted score; the frozen
  local run is retained separately and shown when different. SCORE_IMPLAUSIBLE shows
  generic verification copy, without formula internals. Expired runs refetch lives
  and require a new session without any local refund.
- 403 shows a safe access error. 429 respects Retry-After using a display countdown
  before another write; transport parsing retains status, code and requestId.

All API calls happen at load/start/end/retry/recovery/recharge boundaries. The engine
and physics loop contain no networking. Personal-best/summary updates and request
status changes cannot create a second engine instance. Engine cleanup removes its
input listeners and animation frame on navigation/unmount.

## Active sessions and refresh

Session authority and pending keys are kept in memory only. Refresh/navigation may
lose the local run or pending operation, but never refunds its consumed life. The
backend has no current-active-session discovery endpoint. If a new tab/page receives
GAME_SESSION_ACTIVE without a known recoverable session, it shows a wait/reload-after-
expiry message and blocks repeated starts in that mounted page. A default active run
lasts at most 15 minutes. This issue adds no discovery, cancellation or resume API.
Refresh after an ambiguous score save may lose the local result; backend personal
best and immutable accepted scores remain correct. Do not invent a new key to replace
an uncertain result in the same mounted page; use the provided retry action.

## Screens still using prototype fixtures

The game is integrated; other product areas are not integrated by this issue.
Home/Card points, streak, QR/Wallet and simulated scan remain presentation/demo flows;
those displayed values are never sent to game APIs. Profile identity uses the backend
display name. Backend `/auth/me` does not return email, so it is blank after refresh
rather than fabricated. Opponents/leaderboard are explicitly previews; gameplay no
longer writes demo weekly scores or claims a real rank or active prize award. There
is no leaderboard/prize/weekly-period API integration, telemetry or game-to-points
conversion. The visual layout, font, art and gameplay controls remain unchanged.

## Checks

```sh
npm run lint
npm run typecheck
npm test
npm run build
```

Vitest + React Testing Library cover the lifecycle hook, component/engine boundary,
shared cookie/CSRF client and session bootstrap. Tests cover loading, zero lives,
server countdown refetch, no browser grants/refunds, start/end locks, stable retry
keys, pending/accepted/error states, permission/session/validation failures, Retry-After,
personal best and navigation cleanup. No physics rewrite or production test endpoint
is needed.

For real Chromium checks, use a **disposable database**, start backend and Vite, then:

```sh
npx playwright install chromium
npm run test:e2e
# Optional alternate Vite URL:
E2E_BASE_URL=http://127.0.0.1:5174 npm run test:e2e
```

The browser suite registers synthetic CUSTOMER accounts through the real CSRF-
protected API and signs in through the UI. It plays the actual engine, exhausts three
lives, verifies score/personal-best persistence after refresh, checks active conflicts,
and simulates lost successful start/score responses to exercise same-key recovery.
A controlled request-tampering test verifies real server rejection and safe UI copy.
No engine mock or backend change is used in these browser checks. Registration and
gameplay create durable history: run this suite only with disposable development data.
Two-hour recharge is covered by injected-clock backend tests plus frontend countdown/
refetch tests; the browser suite does not wait two wall-clock hours.

Issue #32 remains unstarted. Weekly/all-time/friends leaderboard integration, weekly
periods, prizes and advanced anti-cheat remain deferred.

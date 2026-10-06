# REST API conventions

Completes [issue #6](https://github.com/amiraharcs/Loyalty/issues/6).
Status: proposed v1 contract; no backend endpoints exist in this repository yet.
Apply [role boundaries](roles-and-permissions.md), [business rules](business-rules.md)
and the [domain model](domain-model.md) to every endpoint.

## Transport, naming and versioning

Use HTTPS in production and base path `/api/v1`. URLs use plural resource nouns
and lowercase kebab-case segments; JSON uses camelCase. UUIDs are opaque resource
IDs, never permission grants. Prefer `/customers/me` for customer-owned data.

Version a breaking contract change with a new major path. Additive optional
response fields need no new version; consumers must tolerate them. Document
request fields explicitly and reject unknown mutation fields so clients cannot
inject roles, points, statuses or ownership through mass assignment.

Send/accept UTF-8 `application/json`, except errors use `application/problem+json`.
Do not return persistence entities directly. Use request and response DTOs that
expose only fields required by the caller's role and purpose. No passwords/hashes,
session IDs, internal stack traces or database details appear in DTOs.

Authenticated identity determines `customerId` and action actor. The DTO may
carry a target resource/store ID when permitted, but never the acting user,
effective role, earned amount, calculated balance or acceptance timestamp.

Dates use `YYYY-MM-DD`; instants use RFC 3339 UTC strings, such as
`2026-10-06T10:30:00Z`. Response countdowns include absolute timestamps and
`serverTime`. Enum strings are uppercase. Scores/points are integers; the API
rejects values outside JavaScript's safe-integer range. Money is not a v1 type.

## HTTP conventions

| Status | Use |
| --- | --- |
| `200 OK` | Reads and updates with a response; a repeated already-completed domain operation may return its existing result. |
| `201 Created` | New user, token, resolution, visit, redemption, game session or accepted score; include a same-origin relative `Location` for retrievable resources. |
| `204 No Content` | Successful logout or update intentionally returning no body. |
| `400 Bad Request` | Malformed JSON, unknown fields, invalid types/ranges, unsupported filters or field validation errors. |
| `401 Unauthorized` | Missing/expired session for a protected resource or generic invalid credentials. APIs never redirect to an HTML login page. |
| `403 Forbidden` | Authenticated role lacks the action, CSRF check fails, or an account is disabled. |
| `404 Not Found` | Resource absent or outside ownership/store scope; do not distinguish these externally. |
| `409 Conflict` | Valid request conflicts with business state: insufficient points, duplicate visit, no life, expired QR/session/prize, active session, changed idempotency payload. |
| `415 Unsupported Media Type` | Mutation body uses an unsupported content type. |
| `429 Too Many Requests` | Rate limit reached; include `Retry-After`. |
| `500 Internal Server Error` | Unexpected failure; safe problem response plus server-side correlation details. |
| `503 Service Unavailable` | Temporary operational dependency failure; retry information when known. |

`GET`/`HEAD` never change business state. Creation uses `POST`; a partial allowlisted
profile/catalogue update uses `PATCH`. Do not delete historical transactions,
visits, completed sessions, redemptions or audits through HTTP. Account/catalogue
deactivation is an explicit authorised state update, not a cascade delete.

## Authentication and header convention

The production browser uses an opaque server session, not a bearer token in
localStorage. Spring Security/Spring Session manage the session server-side.
The chosen production deployment serves React and `/api/v1` on the same origin
through a reverse proxy; the API remains a separate internal service.

| Header/cookie | Convention |
| --- | --- |
| `Cookie: __Host-loyalty-session=...` | Browser-managed auth cookie: Secure, HttpOnly, SameSite=Lax, Path=/ and no Domain. Do not set it in JavaScript or log it. |
| `X-CSRF-TOKEN: ...` | Required on unsafe requests, including login, logout and registration. Obtain the token from `/auth/csrf`; refresh it after login/logout. |
| `Idempotency-Key: <uuid>` | Required for visits, redemptions, fulfilment, points adjustments, game starts and score submissions. Other endpoints specify it when needed. |
| `X-Request-ID` | Server-generated UUID in every response and logs. Treat any incoming correlation ID as untrusted; replace it with the server ID. |
| `Accept: application/json` | Expected for success responses; clients also parse problem JSON on errors. |

There is no accepted `Authorization: Bearer` scheme in browser v1. If a mobile or
third-party API is introduced later, define a separate token contract rather
than accepting arbitrary JWTs alongside these sessions.

Fetch the CSRF token into memory before a state-changing request. Use Spring's
supported SPA token handling, including deferred-token/BREACH behaviour, rather
than disabling CSRF. After session expiry, return to sign-in; do not endlessly
retry an unsafe operation using old credentials.

During local integration prefer Vite's `/api` proxy to the local Spring server.
If direct cross-origin development requests are needed, allow only explicit
development origins with credentials. Production requires a verified same-origin
gateway; the current static Sites demo does not already proxy a backend. Do not
silently deploy a cross-site cookie dependency or wildcard credentialed CORS.

## Representative resource map

This is the naming/ownership baseline, not implemented OpenAPI coverage.

| Method and path, relative to `/api/v1` | Caller and purpose |
| --- | --- |
| `GET /auth/csrf` | Visitor/authenticated browser gets CSRF token; no-store. |
| `POST /auth/registrations` | Visitor creates CUSTOMER; registration does not automatically sign in. |
| `POST /auth/sessions` | Validates email/password, rotates session ID and returns current-user DTO. |
| `DELETE /auth/sessions/current` | Idempotent logout; invalidates server session and expires cookie; CSRF protected. |
| `GET /auth/me` | Authenticated principal ID, display name and explicit roles. |
| `GET/PATCH /customers/me/profile` | CUSTOMER reads/updates own display name, leaderboard alias/privacy preference. |
| `GET /customers/me/loyalty` | Balance, reward progress and streak summary. |
| `GET /customers/me/visits` | Own qualifying visit history. |
| `GET /customers/me/point-transactions` | Own immutable ledger projection. |
| `POST /customers/me/qr-tokens` | Creates 60-second identity QR and invalidates previous token/resolutions. |
| `GET /stores` and `GET /rewards` | Authenticated service/customer active catalogue. |
| `POST /staff/scans` | Assigned STAFF resolves token into a short-lived ScanResolution. |
| `GET /staff/customers?identifier=...&storeId=...` | Assigned STAFF gets service-limited lookup; avoid putting unnecessary personal data in query/logs. |
| `POST /staff/visit-resolutions` | Assigned STAFF binds an authorised manual customer lookup to a store and short-lived confirmation; audit lookup source. |
| `POST /staff/visits` | Assigned STAFF consumes their ScanResolution and confirms one daily qualifying visit. |
| `POST /customers/me/redemptions` | CUSTOMER reserves a points reward or owned prize for an active store. |
| `GET /customers/me/redemptions` | Own redemption history. |
| `POST /customers/me/redemptions/{id}/cancellations` | Owner cancels an unfulfilled pending redemption. |
| `POST /staff/redemptions/{id}/fulfilments` | Assigned STAFF fulfils a valid pending redemption at its store. |
| `GET /customers/me/game-lives` | Server lives, nextRechargeAt and serverTime. |
| `POST /customers/me/game-sessions` | Creates versioned run and atomically consumes one life. |
| `GET /customers/me/game-sessions/{id}` | Owned session state/result for retry recovery. |
| `POST /customers/me/game-sessions/{id}/scores` | Owner submits final score to that active run. |
| `GET /customers/me/game-summary` | Own personal best and current-week best. |
| `GET /leaderboards/weekly` and `/leaderboards/all-time` | CUSTOMER sees limited aliases/ranks, own position and paged results. |
| `GET /customers/me/prizes` | Owned entitlement state and expiry. |
| `GET /admin/customers` and `/admin/staff` | ADMIN support/management projections. |
| `PATCH /admin/users/{id}` | ADMIN allowlisted status/role changes; not a general entity patch. |
| `POST /admin/staff-store-assignments` | ADMIN assigns staff to store. |
| `POST /admin/customers/{id}/points-adjustments` | ADMIN correction with signed amount/reason; ledger append. |
| `POST/PATCH /admin/rewards[/{id}]` and `/admin/prizes[/{id}]` | ADMIN manages catalogue with no historical rewrite. |
| `GET /admin/audit-logs` and `/admin/reports/...` | ADMIN scoped operational data with permitted filters. |

Background recharge/expiry/weekly settlement is internal. Do not publish an
unprotected "system" route or accept a caller-supplied SYSTEM role.

Use opaque customer/member IDs for staff lookup when possible. If email lookup
is introduced, use a protected body-based search contract, redact request bodies
and audit the purpose; do not expand the illustrated query into logged email PII.

## DTO examples

Confirm a previously resolved QR without accepting an amount or actor:

```http
POST /api/v1/staff/visits
Content-Type: application/json
X-CSRF-TOKEN: <csrf-token>
Idempotency-Key: 16c91991-a1a5-4da0-a96d-f66c4dfd8949

{"scanResolutionId":"ba3a31c5-dd17-4a3a-8305-e6d7ff013c52"}
```

The server derives the customer, staff, store, 100-point award and business date.
Return the Visit ID, confirmedAt, businessDate and earnedPoints plus the updated
loyalty summary. A redemption request accepts `rewardId`, `storeId` and optional
`customerPrizeId`, never a client price, balance or fulfilment status.

Game result submission accepts `score` only; session ownership, engine version,
elapsed time, period, rank and personal best are resolved server-side.

## Pagination, filters and ordering

Use cursor pagination: `size` defaults to 20, maximum 100; responses have `items`,
`nextCursor` (null at the end) and `hasNext`. Reject zero/negative/oversized sizes,
malformed cursors and unsupported filters with `400`, not silent truncation.

Opaque cursors encode the final sort tuple and filter/period scope. Validate and
bind them to the query; they must not override caller ownership. Histories use
`createdAt DESC, id DESC`. Rankings use the documented score/tie-break order and
return a separate `currentUser` rank object. Settlement results are stable;
in-progress ranking pages may change as new valid scores arrive.

Filters are explicitly allowlisted: e.g. `from` inclusive/`to` exclusive UTC
instants for history, `periodStart` as a Monday business date for weekly ranking,
and defined `status` values. Restrict audit/support range and result sizes.
Use allowlisted sort names, never raw database field names or SQL fragments.

## Idempotency and concurrency

Generate a key once per intended mutation and retain it across timeout retries.
Scope stored results by authenticated actor, operation and key; compare a hash
of canonical nonsecret business payload. Reuse with a changed payload returns
`409 IDEMPOTENCY_KEY_REUSED`. A same-payload retry returns the original result,
including creation status and Location, without a second debit/visit/life.

Retain keys/results for at least 24 hours. Even after key cleanup, durable source
constraints protect visits, session scores, prize assignments and refunds.
Recheck current authentication/scope before returning a stored result; expired
credentials or removed permissions must not retrieve it. Never hash/store raw
credentials or log keys together with sensitive request bodies.

Return success only after the transaction commits. On timeout, clients query
the known resource or retry with the same key. Do not retry a non-idempotent
mutation with a new key automatically. Domain locks/unique constraints enforce
consistency independently of HTTP idempotency.

## Standard error representation

Use [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457.html) with
`type`, `title`, `status`, safe `detail` and request-path `instance`. Add stable
`code`, server `requestId` and optional `fieldErrors`; frontend decisions use
`code`, not matching human-readable messages.

```json
{
  "type": "urn:loyalty:problem:insufficient-points",
  "title": "Insufficient points",
  "status": 409,
  "detail": "You do not have enough points for this reward.",
  "instance": "/api/v1/customers/me/redemptions",
  "code": "INSUFFICIENT_POINTS",
  "requestId": "1d39f019-a033-42a4-96e2-f7ceab1479d0"
}
```

Validation errors may include `fieldErrors: [{"field":"email","code":"INVALID_FORMAT",
"message":"Enter a valid email address."}]`. Never include rejected password/QR
values, exception class names, SQL, secret configuration or stack traces. Avoid
account enumeration with generic login/registration-conflict messages.

Use `Cache-Control: no-store` for auth, QR and customer/staff/admin personal
responses. Cache public catalogue data only under an explicit documented policy.
TLS and authenticated resource checks follow [OWASP REST Security guidance](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html).

# Loyalty backend

Java 21, Spring Boot 4.1.1 and Maven. Issues #8–#11 establish a modular monolith
with PostgreSQL and Flyway. Issues #12–#15 add identity persistence, customer
registration, server-side authentication sessions and explicit role enforcement. Other product APIs remain
unimplemented.
[P1 architecture](../docs/architecture/README.md) remains the source of truth.

## Local development

Install JDK 21 and Docker with Compose. Set `JAVA_HOME` to the JDK home.
From the repository root:

```sh
cd backend
cp .env.example .env
# Edit .env if needed; these credentials are for local development only.
docker compose up -d --wait
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

Compose reads `.env` automatically; Spring Boot does not. Export its variables
as above before starting Java. Keep `.env` out of Git. The Maven wrapper pins
Maven 3.10.0; Windows users can use `mvnw.cmd` and set equivalent environment
variables in their shell.

The default `dev` profile binds HTTP to `127.0.0.1:8080`. PostgreSQL 17 binds
only to `127.0.0.1:5432`, has a health check, and stores data in the Compose
project's `postgres_data` volume. `DB_PORT` changes the host database port;
`PORT` changes the HTTP port. There is no embedded database fallback.
If port 5432 is already occupied, set `DB_PORT=55432` in `.env` before starting
Compose, and export that same value when starting the application.

## Database configuration

| Variable | Development | Production |
| --- | --- | --- |
| `DB_URL` | Optional JDBC URL override | Required `jdbc:postgresql://host:port/database` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `5432` / `loyalty_dev` | Use `DB_URL` |
| `DB_USERNAME` | `loyalty_dev` | Required, no default |
| `DB_PASSWORD` | `local_dev_only` | Required, no default |
| `DB_POOL_SIZE` | 10 | 10, tune for deployment connection limits |
| `DB_SSL_MODE` | Dev connections disable TLS | `verify-full` by default |
| `DB_MIGRATION_URL` / `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | Not used | Optional separate migration connection; supply all three together |

Activate production using `SPRING_PROFILES_ACTIVE=prod`. Supply credentials via
deployment environment/secret storage, never source control or Vite variables.
Missing/blank settings and non-PostgreSQL URLs fail before datasource creation.
Unreachable databases, bad credentials, TLS errors and migration errors fail
startup. Connection establishment is bounded; no application starts without its
database. Production TLS requires a trusted server certificate and a hostname
matching it; configure the PostgreSQL client's trust store/root certificate for
your hosting provider. Do not disable TLS for deployed databases.

Provision the production database before application startup. Where supported,
use a migration account that owns the `loyalty` schema and can create objects,
and a separate least-privilege runtime account with `CONNECT` and schema `USAGE`.
Grant only the domain table/sequence privileges required by later features, and
configure default privileges for objects created by the migration owner. The
local Compose account intentionally serves both purposes for development.
Do not point migration and runtime URLs at different databases.

```sh
./mvnw -B -ntp clean verify
# With production settings already supplied by the deployment environment:
java -jar target/loyalty-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## Schema ownership and migrations

Flyway runs automatically at startup **before JPA**. The existing Boot Flyway
starter and `flyway-database-postgresql` runtime module provide PostgreSQL
support. All profiles use:

- Dedicated `loyalty` schema, created by Flyway, with its schema history there.
- `classpath:db/migration`, checksum validation, and no automatic baselining.
- Flyway clean disabled, SQL initialization disabled.
- Hibernate `ddl-auto=validate`, with no automatic creation/update of tables.
- JPA open-in-view disabled and JDBC timestamps in UTC.

`V1__initialize_persistence.sql` documents the schema and revokes public CREATE
on it. It creates no domain tables. V1 adds only Flyway's schema history; V2 adds
the identity model described below. Add future immutable migrations as
`V3__description.sql`, etc.;
never edit a migration that has already run. A nonempty unmanaged `loyalty`
schema fails migration rather than being silently adopted.

## Stop or reset local PostgreSQL

```sh
# Preserve data:
docker compose down
```

Changing `POSTGRES_*` settings does not update credentials in an initialized
volume. To change those settings, alter the local database deliberately or reset
it. The following permanently deletes **this Compose project's local data**.
Use it only for disposable development data; back up anything you need first,
and never run it against production:

```sh
docker compose down --volumes
docker compose up -d --wait
# Start the application again to recreate the schema via Flyway.
```

## Checks

```sh
./mvnw -B -ntp clean verify
```

Docker must be running. Testcontainers creates an isolated PostgreSQL 17 instance
on a random port, independent of your Compose database, and cleans it up when
the test JVM ends. Tests never reset the development database. The suite checks
both Spring profiles, PostgreSQL connectivity, Flyway version/checksum validation,
JPA identity mapping/schema validation, empty-database migration, repeat migration,
refusal of unmanaged schema/clean, and production configuration failures.
Existing closed security and validation checks remain in place. `verify` compiles,
tests and packages a runnable JAR.

## Structure and scope

Root package: `com.amiraharcs.loyalty`. Domain boundaries remain lightweight
`package-info.java` files: `auth`, `customer`, `loyalty`, `visit`, `store`,
`reward`, `qr`, `game`, `leaderboard`, `competition`, `admin`, `audit`, `common`.
`common/config` contains the foundation security policy and production database
settings guard. Identity entities/repositories live under `auth/model`,
`auth/repository`, `customer/model` and `customer/repository`. Other domain
boundaries remain package placeholders. Registration DTOs/controllers, validation and services live under `auth/api`,
`auth/validation`, `auth/service`, with password/session configuration in
`auth/config` and authentication infrastructure in `auth/security`. Shared
rate limiting lives in `common/security`. The frontend is unchanged.

Safe health reads, valid API CORS preflights, GET `/api/v1/auth/csrf`, and
CSRF-protected POST `/api/v1/auth/registrations` and `/api/v1/auth/sessions`,
and DELETE `/api/v1/auth/sessions/current` are public. GET `/api/v1/auth/me`
requires an active authenticated session. Customer, staff and admin namespaces
require their independent explicit grants, as described below. Unmatched routes
remain denied (anonymous callers receive `401`; authenticated callers and CSRF
failures receive `403`). No generated user, HTML login, HTTP Basic, or role
hierarchy is added. Resource-specific permissions belong to later features.

## Identity persistence (Issue #12)

`V2__create_identity_model.sql` adds only `loyalty.users`, `roles`, `user_roles`
and `customer_profiles`. V1 is unchanged. Hibernate remains in `validate` mode.
Flyway seeds the three role codes, never users or default credentials.

- `User`: generated UUID, canonical email, supplied password hash, account status,
  UTC created/updated instants. Email is stripped and lowercased with `Locale.ROOT`.
  Future services must use `User.canonicalizeEmail` before exact repository lookups.
  Registration supplies a hash from the approved encoder described below;
  other callers must also supply an encoded hash, never a plaintext password.
- `Role`: string code primary key, restricted to CUSTOMER/STAFF/ADMIN. A lazy
  user-owned many-to-many association maps explicit grants through `user_roles`.
  Its `(user_id, role_code)` primary key prevents duplicate grants. There is no
  hierarchy, implicit customer role or SYSTEM role. Role lookup is read-only.
- `CustomerProfile`: generated UUID and one required, unique user reference;
  display name, leaderboard alias and a required privacy boolean. No first/last
  name, profile metadata, loyalty state or game state is invented. Profile is
  owned through the identity boundary's customer package, separate from credentials.

The architecture specifies activation/deactivation, without a LOCKED lifecycle;
this model therefore uses ACTIVE/DISABLED only. Issue #14 blocks sessions when
the current database status is DISABLED. Account-management workflows and
last-admin protection belong to future authorized services. A profile's CUSTOMER eligibility
is supplied by RegistrationService's fixed CUSTOMER assignment. Other role/profile
workflows remain deferred; no trigger ties profile existence to a role.

Storage bounds are implementation decisions: email 254 characters, encoded hash
512, role/status code 16, display/alias 100. Required strings cannot be blank.
Email has a database unique constraint plus canonical-form check, so case variants
cannot coexist. Status/role check constraints reject unsupported values. User
update time cannot precede creation time. Timestamps are supplied explicitly by
callers using a server clock; the model does not read the system clock implicitly.
Profile timestamps are omitted because the agreed catalogue does not specify them.
The privacy boolean has no automatic default; a later workflow must choose it.

All foreign keys restrict deletion/key updates; no ORM cascading or orphan removal
is enabled. SQL foreign keys protect users/roles referenced by profiles or grants;
removing a profile does not remove its identity. Repositories expose only save,
ID lookup and needed identity/profile lookups; no hard-delete API is offered.
Roles are migration-owned, so their repository exposes only lookup by code.
Historical identity deactivation follows the architecture rather than cascade
removal. The unique/primary-key indexes cover email, profile owner and user grants;
`ix_user_roles_role_code` supports reverse role-reference checks. Lazy associations
must be read inside a transaction; entities must never become API response DTOs.

No generated entity `toString`, equality or hash-code includes credentials.
`passwordHash` is also ignored by Jackson as defense in depth. PostgreSQL JDBC
server error DETAIL is disabled because failing-row diagnostics may echo a hash;
do not enable SQL bind-value DEBUG/TRACE logging for real credential data.

`IdentityPersistenceTests` runs against disposable PostgreSQL: repository round
trips, role/profile relationships, statuses/timestamps, case-variant duplicates,
required fields, FK/key/deletion constraints, non-cascading persistence/removal,
and hash safety. The migration tests verify all migrations from an empty database,
repeat migration and checksum protection. Both application profiles validate
all three entity mappings at startup. Issue #12 seeds no users or credentials. Registration is added by Issue #13;
login is added by Issue #14.

## Customer registration and password security (Issue #13)

Public endpoint: **POST `/api/v1/auth/registrations`**, protected by CSRF. Only
`email`, `password` and `displayName` are accepted; unknown fields such as roles,
status, IDs or balances fail with `400 MALFORMED_REQUEST`. The request DTO's
password is write-only and its `toString` is redacted. Registration never signs
in, creates an authentication session or issues an authentication token.

```json
{"email":"member@example.org","password":"a long unique private passphrase","displayName":"Member"}
```

Success: `201`, `Cache-Control: no-store`, and only
`{"message":"Registration completed."}`. The architecture gives no more specific
registration response shape; this minimal DTO intentionally exposes no ID,
email, hash, entity or role internals. There is no `Location` because registration
does not expose a retrievable user resource. Invalid input uses the shared
`400 VALIDATION_ERROR` contract with safe field errors.

Email is stripped/lowercased via the existing canonicalizer before validation
and persistence. The service validates direct calls too. Names are required and
limited to 100 characters. Passwords contain **15–128 Unicode code points**;
spaces and valid Unicode are preserved exactly, with no trimming, normalization,
truncation or composition rules. Empty/whitespace-only and malformed Unicode
passwords fail, as do entries in a small local common-password starter blocklist.
This is not a complete compromised-password corpus. Extend it through a vetted
offline dataset if needed; no raw password is sent to an external service.

`PasswordEncoder` is Spring's versioned `DelegatingPasswordEncoder`, with
`{argon2id-v1}` encodings: Argon2id, **19 MiB memory, two iterations, parallelism
one**, 16-byte random salts and 32-byte output. Bouncy Castle `bcprov-jdk18on`
1.86 supplies the implementation. BCrypt is not used because the architecture
explicitly specifies Argon2id. Issue #14 reuses the same encoder for matching
and conditional hash upgrades during login. Benchmark and tune on the
actual deployment hardware; the password test prints a local five-operation
encode/verify benchmark without credentials.

Hashing happens before opening a database transaction. All persistence runs in
one `TransactionTemplate`: existing CUSTOMER catalogue lookup, ACTIVE user,
explicit CUSTOMER grant and customer profile. A shared injected UTC `Clock`
supplies timestamps. The profile starts private with generic alias `Member`;
registration accepts no alias/privacy settings and never derives an alias from
email. Future profile settings can change this through authorized services.
No arbitrary role is created and no staff/admin permission can be requested.

The database unique email constraint is the final duplicate/race protection;
there is no existence-only precheck. Only structured SQL state `23505` and named
constraint `uq_users_canonical_email` map to generic **409 `CONFLICT`**, after
transaction rollback. Other persistence failures remain safe internal errors.
Passwords and hashes never appear in responses or service logs; PostgreSQL's
failing-row diagnostic detail remains disabled. Do not enable SQL bind-value or
raw HTTP body DEBUG/TRACE logging for credential traffic.

### CSRF bootstrap

GET **`/api/v1/auth/csrf`** returns `{headerName, token}` with `no-store`. Retain
its masked token in memory, send it as `X-CSRF-TOKEN`, and include cookies in the
registration request (`credentials: 'include'` for cross-origin development).
Use the same hostname for Vite and the backend locally (both `localhost`, or both
`127.0.0.1`) so SameSite=Lax cookies remain usable across their different ports.
Spring's `CookieCsrfTokenRepository` plus `XorCsrfTokenRequestAttributeHandler`
provide deferred tokens and BREACH masking. The cookie is HttpOnly, SameSite=Lax,
Path=/, with no Domain: `loyalty-csrf` locally and Secure `__Host-loyalty-csrf`
in production. It is solely a CSRF cookie, not a login/session token. A missing
or invalid token returns `403 FORBIDDEN`; CSRF is not disabled or exempted for
registration. The request cache is disabled to avoid anonymous authentication
sessions. The Issue #14 session endpoints use the same CSRF mechanism.

### Registration attempt limit and staged delivery

Registration allows **ten attempts/hour/IP** in a sliding window, after CSRF
validation and before DTO processing/hash work. The eleventh returns
`429 RATE_LIMITED` with `Retry-After`. It uses the servlet's remote address and
does not parse caller-supplied forwarding headers. The in-memory map expires
old entries and caps tracked addresses at 10,000, failing closed at capacity.
This is a per-instance limiter for the modular monolith: restarts reset it;
a multi-instance deployment needs a shared/gateway budget. Configure trusted
proxy address handling deliberately before deployment. Issue #14 extends the
same sliding-window infrastructure for login, as documented below.

**Loyalty account creation is deliberately deferred to P4.** The full architecture
requires registration to initialize loyalty/streak/game state eventually, but
those models do not exist yet and the Issue #13 instructions explicitly prohibit
inventing them. This phase commits identity + profile only. P4 must extend the
same atomic boundary and provision existing registered profiles before relying
on those aggregates. No loyalty tables or new Flyway migration are added here;
V1/V2 remain unchanged. Issue #13 records this scope deferral explicitly.

Tests cover registration API/service validation, case-variant duplicates,
concurrent uniqueness, CUSTOMER/ACTIVE/profile defaults, hashes/salts/Unicode,
rollback after real user/grant SQL inserts, safe errors/logs, privilege-field
rejection, real CSRF cookie round trips, production cookie attributes, unrelated
route protection and the registration rate limit.

```sh
./mvnw -B -ntp -Dtest=RegistrationTests,RegistrationCsrfTests,ProductionCsrfTests,PasswordSecurityTests,RegistrationRateLimiterTests test
./mvnw -B -ntp clean verify
```

## Login and authenticated sessions (Issue #14)

| Method | Endpoint | Behaviour |
| --- | --- | --- |
| POST | `/api/v1/auth/sessions` | CSRF-protected email/password login; `201` current-user DTO and `Location: /api/v1/auth/me`. |
| GET | `/api/v1/auth/me` | `200` current-user DTO, or JSON `401 UNAUTHENTICATED`. |
| DELETE | `/api/v1/auth/sessions/current` | CSRF-protected, idempotent `204`; invalidate the database session and expire both cookies. |

Login accepts only `{email, password}`. Email uses registration's exact strip +
`Locale.ROOT` lowercase canonicalizer. Password bytes/Unicode/spaces are preserved;
the new-registration blocklist is not reapplied to existing accounts. Required,
bounded fields are validated; malformed/invalid input uses the shared `400`
contract. A wrong password, absent user, disabled user or exceeded login budget
returns the same generic RFC 9457 `401 UNAUTHENTICATED`, without redirects or
account-status details. Absent users still perform an Argon2id comparison against
a random dummy hash. Successful authentication conditionally upgrades a stored
hash when the existing encoder requests it; the update cannot overwrite a
concurrent password change or reactivate a disabled account.

The login and current-user DTO is `{userId, displayName, roles}`. `userId` is an
opaque UUID and `roles` contains only explicit current database grants; ADMIN
or STAFF never implicitly gains CUSTOMER. `displayName` comes from the customer
profile and is `null` for a non-customer identity without a profile. Email, hash,
password, session ID, timestamps and entities are never returned. Responses use
`Cache-Control: no-store`.

Spring Session JDBC persists opaque sessions in PostgreSQL using
**`V3__create_server_sessions.sql`**. V1/V2 remain unchanged. The tables are
`loyalty.spring_session` and `loyalty.spring_session_attributes`; indexes support
session ID, expiry and principal lookup. Attribute deletion cascades only from
these transient sessions, never domain history. Spring's built-in PostgreSQL
query customizer handles concurrent attribute inserts. Flyway owns the schema;
`spring.session.jdbc.initialize-schema=never` and Hibernate `validate` remain
mandatory. Session rows contain Spring Security's UUID-only principal, explicit
authorities and server-controlled expiry/password-version metadata, never raw
passwords, encoded password hashes or email. Attributes use Spring Session's
standard JDBC serialization: treat the session database as trusted sensitive
server storage, not a client-editable data source.

Production cookie: **`__Host-loyalty-session`**, Secure, HttpOnly, SameSite=Lax,
Path=/, no Domain, opaque random value. Local HTTP development uses
**`loyalty-session`** with Secure disabled; all other attributes are identical.
No JWT, bearer token, browser localStorage/sessionStorage token, remember-me,
password-reset or verification flow is introduced. Production requires HTTPS;
prefer the architecture's same-origin reverse proxy. Cross-origin local calls
must include credentials and use matching hostnames.

Spring Session enforces **30 minutes idle**. A separate authentication timestamp
uses the injected UTC server `Clock`; a filter rejects/invalidate sessions at
**12 hours absolute**, even when their idle timeout is continually refreshed.
Missing/future timestamps fail closed. Login invokes Spring's session fixation
strategy and explicitly saves the SecurityContext; pre-authenticated and
already-authenticated session IDs rotate. Every authenticated request reloads
account status, roles and a one-way password-hash version from PostgreSQL.
Disabled/deleted accounts and changed passwords lose access and their current
session is removed. Changed explicit grants rotate the session ID and refresh
Spring Security authorities without extending the original absolute lifetime.
Other stored sessions are rejected on their next use; account-management
workflows are deferred. No new permission-protected business route is opened.

Obtain CSRF through GET `/api/v1/auth/csrf`, keep the masked token in memory,
and send it with cookies in `X-CSRF-TOKEN` on registration, login and logout.
**Refresh CSRF after successful login/logout and after a role-change rotation.**
The old CSRF cookie is cleared; bootstrap creates a fresh token without creating
an anonymous authentication session. Missing/invalid CSRF is always `403`, even
for idempotent anonymous logout. Logout deletes the server row/attributes,
clears authentication and expires the auth cookie. Reusing its old ID returns
`401`; a repeated logout with freshly bootstrapped CSRF remains `204`.

Login allows **10 failed attempts / 15 minutes / account-IP pair**. An attempt
reserves a slot before hashing; successful verification releases only that
attempt, preserving earlier failures in the same rolling window. The independent **50 attempts / 15 minutes / IP** budget counts
all CSRF-valid login requests, including successes and invalid DTOs, to limit
credential spraying. Set `LOGIN_IP_ATTEMPT_LIMIT` (1–1000) to tune that second
budget; the architecture does not prescribe its numeric value. Both budgets
expire automatically; no permanent account lockout exists. The IP filter returns
the generic `401` with `Retry-After` when its budget is exhausted. Account keys
are one-way digests instead of raw email. Registration and login share the same
bounded, thread-safe sliding-window implementation (maximum 10,000 active keys
per budget, fail closed at capacity). These limits are **per application instance**
and reset on restart; multiple instances need a shared/gateway limiter before
scaling.

The servlet remote address is authoritative. `server.forward-headers-strategy`
is explicitly `none`, so public `X-Forwarded-For`/`Forwarded` cannot change it.
Behind a reverse proxy this budgets the proxy address until an operator configures
forwarding support together with a strict trusted-proxy allowlist. Do not simply
enable arbitrary forwarded headers. Session/Security DEBUG/TRACE and Hibernate
bind-value logging are disabled by default to keep cookie/hash secrets out of logs;
never enable raw request/body or SQL bind logging in deployments with real users.

PostgreSQL integration tests exercise real CSRF cookies and the complete security
chain: session rows, safe DTOs/logs, generic failures, fixation and re-login,
logout/old-ID rejection, both timeouts, account/password revocation, fresh grants,
production/dev cookie attributes, hash upgrades, throttling, forwarding spoofing,
and unrelated route denial. Rate-limit unit tests cover expiry, independent
budgets, configuration bounds and concurrent reservations.

```sh
./mvnw -B -ntp -Dtest=AuthenticatedSessionTests,ProductionSessionTests,LoginRateLimiterTests test
./mvnw -B -ntp clean verify
```

## Explicit role authorisation (Issue #15)

Roles are **independent explicit grants**, not a hierarchy. ADMIN alone cannot
scan as STAFF or use CUSTOMER gameplay/loyalty APIs; STAFF alone cannot use
CUSTOMER or ADMIN actions. Multiple-role users receive exactly the permissions
of their granted roles. No SYSTEM user role, new role-management endpoint or
business API is introduced.

| Route namespace | Required authority |
| --- | --- |
| `/api/v1/customers/**` | `ROLE_CUSTOMER` |
| `/api/v1/staff/**` | `ROLE_STAFF` |
| `/api/v1/admin/**` | `ROLE_ADMIN` |

These boundaries apply to all HTTP methods after the existing explicit
public/auth/health rules. CSRF remains required for unsafe requests, including
future role-protected mutations. Valid CORS preflight is not permission to run
the corresponding operation. All unmatched namespaces still use `denyAll()`;
no authenticated/permitAll catch-all exists. Missing handlers inside a matched
namespace require the role first, then return the safe `404 RESOURCE_NOT_FOUND`
contract. Shared catalogue/leaderboard namespaces remain denied until their
later issues define action-specific rules.

`RoleAuthorities` centralizes authority names, enum-to-authority conversion, and
compile-time `REQUIRES_CUSTOMER`, `REQUIRES_STAFF`, `REQUIRES_ADMIN` and
`REQUIRES_AUTHENTICATION` expressions. Both login and the per-request database
refresh use this mapping. Use `hasAuthority` through these constants instead of
reimplementing ROLE_ prefix rules or adding a role hierarchy.

`MethodSecurityConfiguration` enables Spring's `@EnableMethodSecurity`.
Sensitive application-service entry points must also declare
`@PreAuthorize(RoleAuthorities.REQUIRES_STAFF)` (or the appropriate constant),
even if their controller URL already requires that role. Controllers may add
method checks as an extra layer; the implemented current-user method requires
authentication independently of its URL rule. Enabling method security does not
protect unannotated methods automatically. The identity lookup and credential
services remain internal collaborators needed during unauthenticated login;
they do not publish HTTP routes.

Call secured methods through **injected Spring beans**. Proxy-based method
checks also cover direct calls to those beans from other components; manually
constructing an object, private/final methods or self-invocation inside the same
bean bypasses the proxy. Keep sensitive entry points public and non-final and
cross the bean boundary instead of relying on an internal call to run security.
See [Spring method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html).

Current database account state and grants remain authoritative on every
session-authenticated HTTP request. Removed roles take effect before URL/method
authorisation and rotate the session ID; new grants become usable without
re-login. Disabled accounts invalidate their session on next use and receive
`401`. A missing/expired session receives JSON `401 UNAUTHENTICATED`; an active
caller lacking a required grant receives `403 FORBIDDEN`, using the existing
RFC 9457 contract without HTML redirects or internal details. CSRF failures
remain `403`. Method annotations consume that refreshed security context; they
are not a substitute for resource ownership, active store assignment, business
conditions or transactional checks. Those checks must be implemented with their
future domain services, not guessed during P3.

`RoleAuthorizationTests` uses real Argon2id login, CSRF cookies and PostgreSQL
sessions with test-only controller/service beans. It verifies the independent
role matrix at URL and directly invoked method levels, combined/no grants,
role additions/removals, disabled accounts, method denial behind an allowed
URL, safe errors, unmatched paths, CSRF and auth/logout continuity.
`RoleAuthoritiesTests` verifies one authority per explicit grant and no implicit
permissions. No database migration, frontend change or P4 business feature is
needed for this issue.

```sh
./mvnw -B -ntp -Dtest=RoleAuthorizationTests,RoleAuthoritiesTests test
./mvnw -B -ntp clean verify
```

## CORS and frontend connectivity

Spring Security integrates one `UrlBasedCorsConfigurationSource`; controllers
must not add individual `@CrossOrigin` annotations. The policy applies only to
`/api/v1/**`. It permits credentials for explicit origins, without accepting
wildcards or origin patterns. CORS does not authenticate callers or authorize
business actions; a valid preflight succeeds while the actual protected API
request still receives `401`/`403`. CSRF remains enabled.

Development defaults match Vite: `http://localhost:5173` and
`http://127.0.0.1:5173`. Set `CORS_ALLOWED_ORIGINS` to override them, for example
when Vite selects another port. Use exact comma-separated HTTP(S) origins with
no path, trailing slash, query, fragment, credentials or wildcard. Invalid
entries fail startup without echoing their values. An empty list grants no
cross-origin permissions.

Production has **no default origins**. Supply `CORS_ALLOWED_ORIGINS` explicitly
through the deployment environment when cross-origin browser access is needed.
Missing/empty configuration rejects cross-origin API requests while same-origin
requests remain usable and protected. Do not copy the local `.env` into a
production deployment. The architecture still prefers the same-origin reverse
proxy for React and `/api/v1`; this issue does not configure hosting, change
cookie policy or introduce cross-site authentication.

Allowed methods: `GET`, `HEAD`, `POST`, `PATCH`, `DELETE`, `OPTIONS`, matching the
P1 API contract. `PUT` is excluded. Allowed request headers: `Accept`,
`Content-Type`, `X-CSRF-TOKEN`, `Idempotency-Key`. There is no bearer scheme or
client-supplied request ID allowance. Exposed response headers: `X-Request-ID`,
`Location`, `Retry-After`. Preflight cache duration is 600 seconds. Unapproved
origins, methods and headers receive Spring's generic CORS rejection; no API
permissions are granted. Cross-origin requests outside `/api/v1/**`, including
Actuator requests, are explicitly rejected.

```sh
curl -i -X OPTIONS http://127.0.0.1:8080/api/v1/not-implemented \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Content-Type,X-CSRF-TOKEN,Idempotency-Key'
```

## Health checks and Actuator policy

Only the `health` endpoint is enabled for read access and exposed over HTTP.
All other endpoint access defaults to `none`; JMX exposure and Actuator discovery
are disabled. Security permits **GET/HEAD only** at these exact paths:

| Path | Contents | Database outage after startup |
| --- | --- | --- |
| `/actuator/health` | Aggregate health, including built-in PostgreSQL datasource check | `503`, `DOWN` |
| `/actuator/health/readiness` | Spring readiness state + PostgreSQL datasource check | `503`, `DOWN` |
| `/actuator/health/liveness` | Spring liveness state only | `200`, `UP` while application is alive |

Healthy checks return `200`, `UP`. Public output never includes components or
health details, even for an authenticated principal: no database name/URL,
credentials, hostnames or exception details. The aggregate response may list
available group names (`liveness`, `readiness`); probe responses contain status.
Component health remains available inside the application through Spring Boot's
built-in `DataSourceHealthIndicator`, not a public `/actuator/health/db` route.
All other Actuator paths (including `/actuator`, `/env`, `/metrics`, `/beans`,
`/configprops` and `/heapdump`) remain denied even for an authenticated caller.

The probes use supported Spring Boot health groups on the same HTTP port as the
application. No extra management port, `/livez`/`/readyz`, metrics endpoint or
custom health controller is introduced. Use readiness to remove a failing
instance from traffic; use liveness for restart decisions. A database outage
must not cause restart loops. Database checks can wait up to the datasource
connection timeout (10 seconds by default); choose monitoring timeouts
accordingly. If the database is invalid/unavailable **at startup**, Flyway still
fails startup as established in Issue #9. After startup, DB health failures
produce `DOWN`; liveness remains independent.

```sh
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/actuator/health/readiness
curl -i http://127.0.0.1:8080/actuator/health/liveness
```

Monitoring clients should call these endpoints without an `Origin` header.
No frontend-facing health UI or authentication workflow is added.

The tests cover development/production CORS, unsafe origin configuration,
preflights, protected APIs, health secrecy and sensitive endpoint restrictions.
A dedicated disposable PostgreSQL container is stopped during the availability
test: health/readiness become `DOWN` while liveness remains `UP`. It never stops
the shared test database or a local/production service.

## Shared API errors and request validation

`common/api` implements the [agreed error contract](../docs/architecture/api-conventions.md#standard-error-representation).
Errors use `application/problem+json` and `Cache-Control: no-store`:

```json
{
  "type": "urn:loyalty:problem:validation-error",
  "title": "Validation error",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/v1/example",
  "code": "VALIDATION_ERROR",
  "requestId": "1d39f019-a033-42a4-96e2-f7ceab1479d0",
  "fieldErrors": [{"field": "email", "code": "INVALID_FORMAT", "message": "Enter a valid value."}]
}
```

The example illustrates the contract; no example/product endpoint is registered.
`fieldErrors` is omitted when empty. `instance` contains the path without query
parameters. The architecture does not require a timestamp, so none is added.
A filter generates a UUID, replaces any incoming `X-Request-ID`, returns it on
success and error responses, and scopes it to logging MDC for the request.
Unexpected failures log the ID and exception type, without exception messages,
SQL, rejected values or stack traces. Keep framework request/body DEBUG/TRACE
logging disabled when handling real secrets or personal information.

| HTTP | Stable code | Trigger |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | DTO constraints, missing parameters, invalid parameter types/ranges |
| 400 | `MALFORMED_REQUEST` | Missing/malformed JSON, invalid body types, unknown JSON fields |
| 401 | `UNAUTHENTICATED` | Security entry point or authentication exception |
| 403 | `FORBIDDEN` | CSRF, permission denial or access-denied exception |
| 404 | `RESOURCE_NOT_FOUND` | Missing route/resource or `ResourceNotFoundException` |
| 409 | `CONFLICT` | Explicit `ConflictException` for a valid request conflicting with state |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` | MVC protocol failures |
| 413 / 429 / 503 | `PAYLOAD_TOO_LARGE` / `RATE_LIMITED` / `SERVICE_UNAVAILABLE` | Framework status exceptions; no limiter/dependency policy added |
| 500 | `INTERNAL_ERROR` | Unexpected exception or invalid server return value |

Future controllers should bind request DTOs using `@Valid @RequestBody`, apply
constraints such as `@NotBlank`, `@Email`, `@Size`, `@NotNull` and `@Positive`, and
use MVC's native parameter validation (constraints directly on parameters;
avoid class-level `@Validated` on controllers). Jackson rejects unknown fields.
Use boxed required numeric fields with `@NotNull` to distinguish missing values.
Field messages come from a safe shared mapping, not interpolated validator
messages or rejected values; custom constraints receive `INVALID_VALUE`.

Modules can throw `new ResourceNotFoundException()` or `new ConflictException()`.
These intentionally accept no caller-supplied message. Authentication/access-denied
exceptions are sanitized too. Never turn
arbitrary database exceptions into business conflicts: unexpected persistence
failures remain generic `500` errors. Add explicit safe domain codes only with
the later feature's documented contract. Preserve `Allow`/`Retry-After` headers
when provided by MVC.

`ApiExceptionHandler` extends Spring MVC's `ResponseEntityExceptionHandler`.
Security errors use the same DTO via `ApiErrorWriter` because they occur before
MVC advice. Boot's fallback error settings also disable exception/message/trace
exposure. MVC and security tests use controllers available only on the test
classpath; production controllers implement only registration and the
authentication session lifecycle.

```sh
./mvnw -B -ntp -Dtest=ApiErrorTests test
./mvnw -B -ntp clean verify
```

## References

- [Spring Security authentication persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)
- [Spring Session JDBC configuration](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
- [Spring Security CORS integration](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [Spring Boot Actuator health](https://docs.spring.io/spring-boot/api/rest/actuator/health.html)
- [Spring Boot database initialization](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html)
- [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/)
- [Architecture domain model](../docs/architecture/domain-model.md)
- [Architecture security and testing](../docs/architecture/security-and-testing.md)

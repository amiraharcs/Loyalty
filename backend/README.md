# Loyalty backend

Java 21, Spring Boot 4.1.1 and Maven. Issues #8–#11 establish a modular monolith
with PostgreSQL and Flyway. Issues #12–#15 add identity persistence, customer
registration, server-side authentication sessions and explicit role enforcement.
Issue #16 adds store persistence and staff-store scope services.
Issues #17–#20 add the immutable ledger, atomic visit awards and admin corrections.
Issue #21 adds signed temporary QR identity references. Issue #22 connects staff
QR resolution to secure visit confirmation. Issue #23 hardens scan history and abuse controls.
Other product APIs remain unimplemented.
[P1 architecture](../docs/architecture/README.md) remains the source of truth.

## Local development

Install JDK 21 and Docker with Compose. Set `JAVA_HOME` to the JDK home.
From the repository root:

```sh
cd backend
cp .env.example .env
# Set QR_SIGNING_KEY_BASE64 in .env using a fresh `openssl rand -base64 32`.
# Other credentials here are for local development only.
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
`V8__description.sql`, etc.;
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
`auth/repository`, `customer/model` and `customer/repository`. Store entities,
repositories and scope services live
under `store/model`, `store/repository` and `store/service`. Loyalty models,
repositories and internal services live under `loyalty/model`,
`loyalty/repository` and `loyalty/service`. Visit persistence and internal
confirmation live under `visit/model`, `visit/repository` and `visit/service`.
Other domain boundaries remain
package placeholders. Registration DTOs/controllers, validation and services live under `auth/api`,
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
all implemented entity mappings at startup. Issue #12 seeds no users or credentials. Registration is added by Issue #13;
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

**Issue #17 completes the deferred loyalty-account initialization.** Registration
now creates identity, CUSTOMER role, profile and exactly one empty LoyaltyAccount
inside its original transaction. V5 also provisions empty accounts for existing
profiles. There are no starter points. Streak and GameLifeState remain deferred
to their owning issues; registration must extend the same atomic boundary when
those aggregates exist.

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

## Stores and staff-store scope (Issue #16)

`V4__create_stores_and_staff_assignments.sql` adds **`loyalty.stores`** and
**`loyalty.staff_store`**. V1/V2/V3 remain unchanged. Flyway owns the migration;
Hibernate still validates rather than creating or altering tables. V4 upgrades
an existing V3 database without modifying identity/session data or seeding users,
stores, assignments or customer activity.

- `Store`: generated UUID, required name/address, ACTIVE/INACTIVE status, and UTC
  created/updated instants. Name is bounded to 200 characters, address to 500;
  both are plain strings because no structured postal model is specified.
  These bounds and status values are implementation choices consistent with
  the architecture. No opening hours, configuration, payment data or speculative
  metadata is added. `setStatus` changes availability while preserving the ID
  and creation timestamp; backwards timestamps are rejected.
- `StaffStore`: composite `(staffUserId, storeId)` primary key, active boolean,
  required original `assignedBy` User, original `assignedAt`, and `updatedAt`.
  Lazy references link an existing User and Store without ORM cascades. The
  original assignment actor/time are immutable through the model. Deactivation
  and explicit reactivation update the same retained row and its update time;
  future management/audit workflows must record each changing actor/reason.
  `Persistable` lifecycle callbacks distinguish new composite-key entities,
  preventing a new object from silently merging/reactivating an existing pair.

All three assignment foreign keys (staff user, store, assigning user) use
**ON DELETE RESTRICT / ON UPDATE RESTRICT**, including inactive assignments.
The pair primary key enforces uniqueness and supports assignment lookup.
A partial staff/store index covers active assignments; additional store/assigner
indexes support restrictive reference checks. Stores have a status/name/ID
index for active catalogue lookup/order. SQL checks enforce status, required
nonblank text and timestamp ordering. No repository exposes a delete method:
normal removal of access means deactivation, preserving historical references.

`StoreRepository` exposes save, ID lookup, ID+status lookup and status-filtered
name/ID ordering. `StaffStoreRepository` exposes save, natural-key lookup,
active-assignment store lookup and active-assignment store listing filtered by
store status. The latter queries constrain assignment and store state together.
Repositories are persistence collaborators, not authorization entry points;
other modules should call the services instead of bypassing them.

`StoreService.requireActive(storeId)` provides a safe `{id, name, address}`
`StoreSummary`, or generic `404 RESOURCE_NOT_FOUND` for an absent/inactive store.
`activeStores()` returns the same projection in stable name/ID order. Both
require an authenticated Spring security context. They validate store
availability, **not staff permission**.

For scanning, visit confirmation, reward fulfilment or staff customer service,
call **`StaffStoreScopeService.requireAssignedStore(storeId)`** through an
injected Spring bean. It derives the actor from the existing `SessionPrincipal`;
there is no caller-supplied staff ID or effective role. It requires all of:

1. Explicit STAFF authority through method-level `@PreAuthorize`.
2. A currently ACTIVE database identity still explicitly granted STAFF, using
   the existing identity service rather than a separate identity implementation.
3. An active assignment for that actor and target store, with an ACTIVE store.

`assignedActiveStores()` applies the same role/account checks before returning
only the caller's currently active scope. CUSTOMER or ADMIN with an assignment
row still receives `403 FORBIDDEN`; ADMIN+STAFF succeeds because STAFF was
explicitly granted. An assignment never grants a role. Missing/disabled
identity is `401`; inactive/unassigned/missing stores share the safe `404`
contract, without assignment metadata. An authenticated HTTP request also keeps
all existing session/account/role/expiry checks and the independent STAFF URL
boundary. The helper rechecks database roles even during a direct bean call.

Assignments are read from PostgreSQL for each operation, not cached in the
session. Assignment deactivation or store deactivation blocks the next use,
while preserving both records; reactivation makes an eligible assignment usable
again. Revoking a store assignment does not sign the user out or remove STAFF
at other stores. Account disablement and role revocation remain separate
identity controls.

These helpers establish the scope boundary, not a reusable authorization token
or a guarantee that a future mutation cannot race with deactivation. Future
business services must call the helper **inside their operation transaction**
and apply the operation's resource ownership, store/assignment locking and
business conditions before committing. No visit, QR, reward, points-award, streak, game or leaderboard workflow is
implemented by the store scope helpers. Issue #17 adds the ledger foundation below.

**No first store is seeded:** the architecture provides no real production shop
name/address. Disposable test fixtures supply stores instead; an authorized
future management/operator workflow must provision real data. No credentials,
staff/admin users or demo activity are seeded. **No StaffProfile is added:**
staff identity basics are represented by User + explicit roles + StaffStore;
the architecture defines no distinct staff-only profile fields.

No public store catalogue/management CRUD, assignment management API, staff
lookup endpoint or staff dashboard is opened. Existing deny-by-default routes
remain in place; later feature issues must explicitly expose their endpoints.

Tests cover mappings, composite keys, constrained/ordered queries, lifecycle
metadata, required fields/status/times, all restrictive FKs, no cascade deletes
or persistence of unsaved parents, current assigned scope, independent roles,
direct method checks, safe errors, actor spoofing, assignment/store revocation,
disabled accounts and database role revocation. Migration tests verify empty
V4 setup and V3-to-V4 upgrade without invented data. Both profiles validate
Store and StaffStore mappings.

```sh
./mvnw -B -ntp -Dtest=StorePersistenceTests,StaffStoreAuthorizationTests,StoreModelTests,MigrationTests test
./mvnw -B -ntp clean verify
```

## Loyalty account and immutable point ledger (Issue #17)

`V5__create_loyalty_ledger.sql` adds `loyalty_accounts` and
`point_transactions`. V1–V4 are unchanged. Empty databases migrate through V5;
existing V4 customer profiles receive one empty account each. Flyway remains
the schema owner and Hibernate validates both development and production models.

A `LoyaltyAccount` has a generated UUID, a unique restrictive CustomerProfile
relationship, a BIGINT version and UTC creation time. It has **no balance field**
and no cascading associations. The balance is always `SUM(signed_amount)` for
that account; an empty ledger returns zero. An unknown account is not an empty
account and raises the shared resource-not-found exception.

A `PointTransaction` has a generated UUID, account, signed BIGINT amount,
`EARN` / `REDEMPTION` / `REFUND` / `ADJUSTMENT` type, UTC creation time,
restrictive actor relationship (required for EARN/ADJUSTMENT), optional reason
and a visit reference required only for EARN (added by V7). EARN/REFUND are
positive, REDEMPTION negative, ADJUSTMENT either sign. Amounts cannot be zero.
An adjustment requires an actor and a nonblank reason (maximum 1,000 characters).
This persistence foundation does not authorize or implement an adjustment flow.

Amounts and totals stay within ±9,007,199,254,740,991 for exact browser integers.
`PointValues` uses long/BigInteger arithmetic, never floating-point or decimal
point values. PostgreSQL widens `SUM(BIGINT)` internally without rounding;
the read repository returns integer text and the service checks BigInteger bounds
before projecting a long. The insert trigger updates/locks the account version and checks the
exact total before commit, including for raw SQL and concurrent inserts. Its
BIGINT temporary total is safe because the previous total and new amount are
already bounded, so their sum fits BIGINT. No decimal amounts or balance columns
are stored. `checkedTotal` is reusable for future pre-write validation.

History is append-only at two layers: Hibernate `@Immutable`, fields without
setters and a persist-only repository fragment; PostgreSQL rejects UPDATE,
DELETE and TRUNCATE, including cascaded truncation. There is no repository merge,
update or delete operation. Corrections must append compensating records.
Restrictive foreign keys prevent deleting accounts/customers or attributed actors
with retained history. Trusted database owners still control schema/trigger
administration; the runtime role must not own schema objects or disable triggers.

The history index is `(account_id, created_at DESC, id DESC)`; actor references
also have a partial index. Internal history projections expose only entry ID,
safe signed amount, type and time, with pages of 1–100 records. No customer-facing
read or mutation endpoint is added. Module callers must apply caller identity,
role, customer ownership and workflow authorization before using these helpers;
a customer ID passed to an internal service is not an authorization decision.

`lockForMutation(customerId)` requires an existing write transaction and uses
`PESSIMISTIC_WRITE`, retaining the lock through the caller's commit/rollback.
Future mutation services must lock **before** reading and validating balance,
then append in that same transaction. The database insert guard also serializes
inserts as a final range-integrity check. Every successful append advances the
database version, so stale REPEATABLE READ/SERIALIZABLE writers fail instead of
committing totals calculated from an old snapshot. The append repository refreshes
a managed account after the insert. The version field also supports explicit
force-increment locking. Future services must deliberately handle transaction
retries; do not use version alone as a substitute for the documented account lock.

Issue #19 implements qualifying-visit earning, as documented below. Redemption
and refund workflows remain deferred; Issue #20 adds admin corrections. Authorized workflows
must enforce nonnegative balances, sufficient points, source eligibility,
idempotency and store scope while holding the account lock. V7 completes Visit
source linkage and the earned-points snapshot. Redemption/reversal columns wait
for their owning workflows, with real FKs, source/type and same-customer checks,
unique sources and unique reversal rules in later migrations.

Registration calls the loyalty module inside its original transaction: user,
role, profile and empty account either all commit or all roll back. Duplicate
registrations create no extra account. There are no demo or starter transactions.
This completes the loyalty-account portion deferred in Issue #13; Streak and
GameLifeState remain deferred. No authentication or frontend contract changes.

Tests use real PostgreSQL for migrations/backfill, Hibernate mapping/validation,
unique ownership, restrictive history FKs, SQL/ORM immutability, exact BIGINT sums,
browser bounds, type/sign/adjustment constraints, deterministic paged ordering,
version increments, caller-owned locks and concurrent aggregate range enforcement.
Registration tests cover empty accounts, duplicates and rollback after an account
INSERT has actually been flushed. Disposable test databases own fixture cleanup;
do not try to delete or truncate ledger records to reset production history.

## Immutable visit registration foundation (Issue #18)

`V6__create_visits.sql` adds `loyalty.visits`; V1–V5 are unchanged. A Visit has
a generated UUID, CustomerProfile, LoyaltyAccount, Store and confirming User
references, a `businessDate` and a UTC `confirmedAt`. There is no redundant
creation timestamp: confirmation is the historical event. The earned-points
snapshot and Visit → PointTransaction source link are now implemented by Issue #19
in V7. Confirmation time and the actual configured award are immutable snapshots.

A unique `(customer_id, business_date)` constraint permits one qualifying visit
per customer per London date **across all stores**. Foreign keys are restrictive,
with no cascading associations. A composite account/customer FK also prevents
combining valid IDs belonging to different customers. V6 adds the corresponding
unique account key without changing account ownership, versions or point history.
Indexes support `(customer_id, confirmed_at DESC, id DESC)` history, store/staff
confirmation history, and business-date lookups for daily operational queries.
There are no seed visits or point transactions.

`VisitService.confirmResolvedCustomer(customerId, storeId)` is an **internal
foundation operation**, not an HTTP contract. It requires an authenticated
explicit STAFF grant and an existing caller-owned write transaction. It reuses
`StaffStoreScopeService` for current active staff/store assignment checks and the
server-derived staff ID, checks ACTIVE customer identity with an explicit CUSTOMER
grant through the existing authentication identity service, and resolves/locks
the customer's loyalty account. Self-confirmation is forbidden even for a user
with both CUSTOMER and STAFF roles. Missing/out-of-scope/inactive resources use
the existing safe not-found outcome; ADMIN alone never implies STAFF.

The account's `PESSIMISTIC_WRITE` lock stays held through the caller's commit or
rollback. After waiting, the service checks customer eligibility and scoped store
access, then captures the shared injected Clock once. The business date is derived
from that instant using the IANA `Europe/London` rules, independent of the Clock's
zone, browser date/time/timezone or database session timezone. Confirmation time
is truncated to PostgreSQL microsecond precision before deriving/persisting its
date, preventing nanosecond rounding across midnight. PostgreSQL independently
checks that the persisted date matches the London confirmation date. The fall DST
repeated hour cannot qualify twice; summer midnight occurs before UTC midnight.

The service's existence check gives a safe duplicate outcome;
`uq_visits_customer_business_date` remains the final authority for racing writers.
Only SQLSTATE 23505 for that named constraint is translated to
`VisitAlreadyRecordedException`. The shared Problem JSON handler maps it to
**409 `VISIT_ALREADY_RECORDED`** without exposing SQL, customer, store or staff
information. A failed INSERT aborts the caller transaction: do not catch the
conflict and try to continue writing in that same transaction.

Visits are immutable at both layers: Hibernate `@Immutable`, no setters and a
persist-only repository fragment; PostgreSQL rejects UPDATE, DELETE and TRUNCATE,
including cascaded truncation. Repository history uses confirmation time DESC,
ID DESC, with safe internal projections containing visit ID, store ID, business
date, confirmation time and awarded-points snapshot. Pages are bounded to 1–100 entries. Future history
HTTP wrappers must derive/authorize customer ownership; an internal UUID argument
is not proof of permission. No public history endpoint is added.

Production `POST /api/v1/staff/visits` now uses the trusted ScanResolution
workflow documented in Issue #22 below. It accepts only a resolution ID and
idempotency header, and composes this internal operation with source consumption,
current-state checks and transactional events/audit. It never accepts arbitrary
customer/store IDs, amounts or dates. Issue #18 originally deferred this route
until trusted resolution existed; Issue #19 added its atomic earning operation.
Streaks, rewards, game and leaderboard remain outside the visit foundation.

## Atomic qualifying-visit awards (Issue #19)

The server-owned `loyalty.visits.points-per-visit` setting defaults to **100**.
Set `POINTS_PER_VISIT` in the application environment to override it; `.env.example`
documents the variable, but Compose's `.env` does not automatically export values
to a Java process. The immutable `VisitAwardRule` rejects zero, negative,
non-integer and browser-unsafe settings at startup. Supported awards are
1–9,007,199,254,740,991. The internal confirmation method still takes only trusted
resolved customer/store IDs, never an amount, date, timezone or confirming actor
from a browser. Configuration changes apply to new operations after restart.

`V7__link_visit_earn_transactions.sql` adds:

- Immutable, positive BIGINT `visits.earned_points`, without a fabricated default.
- Nullable `point_transactions.visit_id`: required for EARN, forbidden for other
  transaction types; EARN also requires the confirming actor.
- A restrictive composite source FK that matches Visit ID **and** account,
  awarded amount and confirming actor. Independent valid IDs cannot be combined
  into an earning against a different account or snapshot.
- Unique `uq_point_transactions_visit_earn`, which also indexes source lookup.
- A deferred `visit_requires_earn` constraint trigger: the Visit INSERT precedes
  its EARN INSERT, but commit is rejected if the matching EARN is missing.

V1–V6 are untouched. Existing immutable history/safe-total/version guards remain.
The earlier foundation had no public visit/award endpoint. V7 explicitly requires
no legacy unawarded Visit records or unlinked EARN rows; it refuses migration if
those exist, rather than inventing awards or rewriting/deleting immutable history.
Existing non-EARN ledger history is preserved. Any unexpected legacy records need
an explicit, reviewed reconciliation migration based on their actual provenance;
do not delete them or disable history guards to force this migration through.
The ordinary clean/preproduction V6 database upgrades without seeding activity.

`VisitService.confirmResolvedCustomer` preserves existing staff/store/customer
eligibility checks, self-confirmation denial, caller-owned write transaction and
`PESSIMISTIC_WRITE` account lock. The lock is taken before balance checks/writes;
the Issue #22 workflow follows the architecture's consistent lock order.
After deriving the London date and checking daily uniqueness, it reads the ledger
balance and checks the configured addition against safe-integer bounds **before**
inserting the Visit. The Visit snapshots the configured award. The loyalty module
then creates `PointTransaction.forVisit(visit)` so account, amount, actor and time
come from that persisted immutable Visit, flushes the EARN and reads the updated
balance from `SUM(signed_amount)`. No cached balance or balance setter is added.
The existing insert guard advances/refreshes account version and independently
protects exact totals, including concurrent raw SQL and stricter isolation levels.

The internal result contains only `visitId`, `confirmedAt`, `businessDate`,
`earnedPoints` and ledger-derived `balance`. Visit history retains each historical
award snapshot; changed configuration cannot alter an old award or ledger entry.
One customer/date across stores, one EARN/Visit, account serialization and the
single transaction prevent duplicate awarding. Repeated confirmation returns
409 `VISIT_ALREADY_RECORDED`. A duplicate earning source maps to the existing safe
409 `CONFLICT`; other database failures do not become false duplicate outcomes.
Unsafe resulting totals also fail as a safe conflict before any new Visit write.

Any failure between Visit and EARN, after the EARN flush/version update, or before
the caller's commit rolls back both records and the version change. The deferred
constraint also blocks an accidental caller that bypasses the service and tries
to commit a Visit alone. Do not switch this constraint to IMMEDIATE before the
Visit/EARN pair is inserted. Neither entity exposes normal updates/deletes;
corrections cannot rewrite existing visits or points.

**Public `POST /api/v1/staff/visits` remains absent.** Atomic awards now exist,
but a production request must still consume an unexpired, staff/store-bound
ScanResolution and apply idempotency/current-state safeguards. That trusted QR or
authorized manual-resolution flow belongs to P5. No arbitrary customerId-based
endpoint is added. Streak, rewards/refunds, admin adjustments, game and leaderboard
workflows remain deferred; Issue #20 is not implemented.

Real PostgreSQL tests cover complete commits, missing-EARN commit rejection,
source FK/ownership/amount/actor consistency, unique source/type/sign constraints,
configured awards and unchanged old snapshots, safe boundaries, concurrent same
staff/different staff/different stores, retry, three rollback boundaries and
unchanged registration/session/security behavior. Eligibility rejections assert
that neither Visit nor ledger records were written. Migrations validate both
clean V7 and V6 upgrades, preserve compatible history, and refuse incompatible
legacy data without altering it. Both profiles retain Hibernate validation.

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
classpath; production controllers implement registration, the authentication session lifecycle
ADMIN points adjustments, customer QR issuance and the staff scan/visit flow.

```sh
./mvnw -B -ntp -Dtest=ApiErrorTests test
./mvnw -B -ntp clean verify
```

## Admin points adjustments (Issue #20)

`POST /api/v1/admin/customers/{id}/points-adjustments` is the first production
loyalty mutation endpoint. `{id}` is a CustomerProfile UUID, not a User or
LoyaltyAccount UUID. Only active users with an explicit ADMIN grant may call it;
ADMIN does not inherit STAFF or CUSTOMER. Security protects the route and service,
and the identity module rechecks current database grants after lock waits.
The target must have a loyalty account and an active CUSTOMER identity. Missing,
disabled, noncustomer or unprovisioned targets return the same safe 404.

Obtain CSRF using `/api/v1/auth/csrf` after signing in. Send `X-CSRF-TOKEN` and a
UUID `Idempotency-Key` with every adjustment. The only body fields are:

```json
{"signedAmount": -100, "reason": "Correct duplicate historical award"}
```

The amount is a nonzero integer within ±9,007,199,254,740,991. JSON decimals and
numeric strings are rejected rather than coerced. Required reasons are stripped
of surrounding whitespace, validated as nonblank and limited to 1,000 Java
characters after normalization. Avoid secrets and personal data in free-text
reasons. Unknown fields, including actor, balance, type and timestamps, are rejected.
The server derives the actor from the session and the timestamp from its clock.

A successful creation (or exact retry) returns 201 with `Cache-Control: no-store`:

```json
{"transactionId":"0b5e4381-2dba-4d53-89c0-c3b96a40a225","signedAmount":-100,
 "reason":"Correct duplicate historical award","createdAt":"2026-10-12T12:00:00Z","newBalance":0}
```

There is no Location header because no transaction-by-ID read endpoint exists yet.
Inside one transaction, the service serializes the actor/operation/key using a
PostgreSQL transaction advisory lock, then locks the LoyaltyAccount using the
existing PESSIMISTIC_WRITE mutation lock. The ledger sum remains the only balance
source. Positive adjustments add points; negative adjustments remove points.
A total below zero returns `409 INSUFFICIENT_POINTS`; an unsupported total returns
`409 CONFLICT`, with no writes. Invalid requests return 400, unauthenticated
requests 401, and missing permission or CSRF 403 through the existing safe RFC
9457 contract. Insufficient-points attempts produce a safe correlated security
log outside durable business history; the log contains no reason/body/key.

Every accepted correction is a new immutable ADJUSTMENT entry with its admin,
reason and server time, and no Visit source. There is no balance setter and no
update/delete API. V5/V7 already enforce actor, reason, nonzero safe amount and
source constraints; those migrations and EARN invariants are unchanged.

`V8__support_point_adjustments.sql` introduces a minimal generic durable
`audit_logs` table and append-only service because the architecture requires
successful privileged mutations and audits to commit together. Events include
actorUserId, USER actorType, action, customer target, timestamp, server requestId
and safe JSON metadata (transaction ID and signed amount). The reason remains
in the ledger rather than being duplicated into audit metadata. PostgreSQL guards
reject audit updates, deletion and truncation; actor/store references are restrictive.
No audit read API, dashboard or general P9 functionality is implemented.

V8 also adds generic infrastructure `idempotency_records`. Results are scoped
by actor/operation/key and compared against a SHA-256 hash of the canonical
normalized customer/amount/reason payload. Changed payloads or targets return
`409 IDEMPOTENCY_KEY_REUSED`; exact retries return the original response snapshot,
not a recalculated later balance. Current actor and target eligibility is checked
before replay. Ledger, audit and retry result commit atomically; any failure rolls
all three back. Keys/results are retained indefinitely for now, with a minimum
24-hour expiry marker and index for a future explicit retention job. No automatic
cleanup is introduced in this issue. Lock order is idempotency first, account
second; future operations using both must preserve that order.

The generic audit foundation leaves wider event policies, retention and scoped
read projections to P9. Failed-attempt review currently uses safe application
security logs, not a durable failed-audit workflow. Initial admin provisioning
remains an operator responsibility; no default admin credentials or role-management
endpoint was introduced. P5 and Issue #21 remain untouched.

Focused acceptance tests use actual PostgreSQL-backed cookie sessions and CSRF,
including authorization, validation, exact retries, rollback, immutable guards,
V7 migration preservation and concurrent deductions/retries:

```sh
./mvnw -B -ntp -Dtest=AdminPointsAdjustmentTests,MigrationTests test
./mvnw -B -ntp clean verify
```

## Temporary customer identity QR (Issue #21)

`POST /api/v1/customers/me/qr-tokens` requires an active authenticated CUSTOMER,
CSRF and a provisioned customer profile/loyalty account. STAFF-only and ADMIN-only
accounts are denied; there is no role hierarchy. Identity is derived from the
server session and current grants are rechecked after account locking. The request
must have **no body and no query/form parameters**, including no `{}`. It accepts
no identity, amount, time or signing fields. Unlike adjustment/visit operations,
QR issuance does not require an Idempotency-Key: each successful call replaces
the previous token. A network retry can supersede the first response; display
only the newest successfully received token.

The 201 response contains only `token`, `expiresAt` and `serverTime`, with
`Cache-Control: no-store`. No Location is returned because no token-read endpoint
exists. The QR is an identity reference, with no coupon, reward or payment authority.
Issuance and validation create no visits or points entries. Frontend QR rendering remains unchanged; staff scanning is documented below.

Nimbus JOSE + JWT 10.10 signs JWS using **HS256 only**. The header contains `alg`,
`kid` and a dedicated `typ=loyalty-qr+jwt`; the payload contains exactly `iss`,
`aud`, `iat`, `exp` and a cryptographically random 256-bit Base64URL `jti`. There
is no email, display name, profile/user ID, role, status or balance in the token.
Timestamps are UTC whole seconds and `exp = iat + 60 seconds`. Expiry is exclusive,
with no grace window; future issuance times and noninteger/incorrect lifetimes
are rejected using the injected server clock. `serverTime` is the captured issuance
instant rounded down to whole seconds to match the signed NumericDates.

### Signing environment and rotation

All profiles fail startup on missing/invalid QR signing configuration; there is
no shared development secret or automatically generated production fallback.
Keep these settings server-side, outside source control and Vite:

| Variable | Required value |
| --- | --- |
| `QR_ISSUER` | Fixed deployment issuer, consistent across all instances |
| `QR_AUDIENCE` | Fixed dedicated QR identity audience |
| `QR_ACTIVE_KEY_ID` | Active ID, 1–64 ASCII letters/digits or `.`, `_`, `-` |
| `QR_SIGNING_KEY_BASE64` | Standard Base64 encoding of a cryptographically random 32–128 byte key |
| `QR_VERIFICATION_KEYS` | Optional JSON object mapping up to ten **old** key IDs to Base64 keys |

Generate a fresh key locally with `openssl rand -base64 32` and paste it into the
ignored `backend/.env`, then export that file as shown above. Production keys
come from secret storage. Examples contain no usable signing material. A length
check cannot prove entropy: use the generator, not a human password. Configuration
errors discard parser causes so startup logs cannot echo secret values.

For rotation, deploy the old active ID/key into the verification ring, set a new
active ID/key, and keep issuer/audience stable. New tokens use only the active key;
unexpired tokens signed by configured old keys still verify against their stored
key ID. Retain an old verification key for at least 60 seconds after the last
instance stops issuing with it, allowing for rollout timing. Removing a key
immediately invalidates tokens using it. Reject an active ID repeated in the old
ring, unknown IDs, `none`, HS384/HS512/RS256 substitutions and extra JOSE headers.
No remote key discovery or KMS is introduced. Coordinate keys and clocks across
instances; changing issuer/audience also invalidates outstanding tokens.

### Persistence, replacement and internal validation

`V9__create_qr_identity_tokens.sql` adds QrIdentityToken with `jti` as its primary
key, restrictive CustomerProfile FK, immutable issuance/expiry/key metadata,
status and optional revokedAt. The full encoded JWS is never persisted. A partial
unique index permits only one ACTIVE row per customer; issuance serializes on the
existing LoyaltyAccount PESSIMISTIC_WRITE lock, revokes prior ACTIVE rows, inserts
the replacement and signs within one transaction. Signing/persistence failure
rolls back both changes. This does not modify the ledger/account balance.

ACTIVE means current, not necessarily unexpired: validity also requires server
time before expiresAt. Expiry is derived instead of requiring a background status
job. Replacement revokes even an already expired ACTIVE row. REVOKED is terminal;
CONSUMED is set by the staff confirmation workflow and is rejected by QR validation. Database checks enforce a 60-second lifetime, whole-second
issuance, valid metadata/status/revocation and terminal lifecycle transitions.
Indexes support current-customer lookup, customer history, jti and expiry queries.
No automatic token deletion/retention job is added.

`QrTokenValidationService.validate` is internal only. It bounds input, requires
the fixed algorithm/type/key, verifies the signature **before reading claims or
looking up any customer**, then checks issuer, exact audience, minimal claims,
server time, and matching active database metadata/current CUSTOMER state.
It returns only the validated reference, customer UUID and expiry to internal
services. Sanitized internal failure reasons distinguish invalid, expired,
revoked/consumed and inactive-customer outcomes; no crypto/parser cause or encoded
token is retained in an exception. The Issue #22 workflow maps safe scan errors,
applies STAFF/store scope and binds/rechecks resolutions under transaction locks.
Validation here is a point-in-time check, not an authorization/confirmation grant.

Issuance uses the existing bounded thread-safe sliding-window limiter:
**30/minute/customer**, plus **120/minute/IP** based on the socket remote address,
with 429 `RATE_LIMITED`, `Retry-After` and no-store problem JSON. Customer limits
also apply to direct service calls. Budgets are per process and reset on restart,
matching existing authentication infrastructure; multi-instance rollout needs a
shared limiter or gateway enforcement to provide a cluster-wide budget. No
forwarded header from an untrusted caller changes the IP budget.

Never log, cache, put in URLs or persist the encoded QR, even in diagnostics or
proxy response-body logs. The response DTO redacts its `toString`; parsing/signing
errors are sanitized and framework body/bind TRACE logging remains disabled in
normal operation. Use HTTPS and synchronized server clocks in production.

```sh
./mvnw -B -ntp -Dtest=QrTokenCodecTests,QrSigningConfigurationTests,QrIssuanceRateLimiterTests,QrIdentityTokenTests,MigrationTests test
./mvnw -B -ntp clean verify
```

## Staff QR scanning and visit confirmation (Issue #22)

Both endpoints require an active STAFF session, current explicit STAFF grant and
CSRF. ADMIN alone is insufficient. Store assignment/store status are read from
the database on each operation and rechecked after lock waits. Requests reject
unknown fields; acting staff is always derived from the principal.

### Resolve a QR

`POST /api/v1/staff/scans` accepts only:

```json
{"token":"<encoded identity QR>","storeId":"<assigned-store UUID>"}
```

A 201 response with `Cache-Control: no-store` contains `scanResolutionId`,
`expiresAt`, `displayName` and ledger-derived `balance`. It contains no email,
customer/user ID, credentials, game/history data or invented streak. The scanner
must be assigned to the active store. Staff cannot scan/confirm their own customer
profile even when they hold both roles.

The existing Issue #21 validator verifies the signature before trusting claims
or customer lookup, and checks algorithm/key/issuer/audience/time/current customer.
After acquiring the account/token locks the workflow refreshes and revalidates
state to reject replacement or expiry during a lock wait. A ScanResolution binds
the exact token/customer/staff/store and expires **120 seconds** after server
creation time. Scanning creates no Visit/EARN, consumes no QR and changes no points.
Repeated scans can create separate resolutions; only confirmation consumes the source.

`V10__create_scan_resolutions.sql` creates the resolutions table and append-only
`qr_scan_events` in one migration. Restrictive FKs include a composite token/customer
binding; bindings and lifetime cannot change, and consumption can happen once,
before the exclusive deadline. Parent token lifecycle supplies revocation: a
replacement QR invalidates every unused resolution for the old token without a
second revocation flag or bulk child update. Indexes support staff/store/expiry,
token references and safe event queries. No cleanup job or event read API is added.

The existing in-memory sliding-window infrastructure limits scanning attempts to
**60/minute/staff**, plus **240/minute/socket IP**, with 429 and `Retry-After`.
This counts invalid attempts too. Limits are per process/reset on restart; a
multi-instance deployment needs shared/gateway enforcement. Untrusted forwarded
headers cannot bypass the IP budget. The customer QR issuance limits remain unchanged.

### Confirm the resolved visit

`POST /api/v1/staff/visits` requires a UUID `Idempotency-Key` and accepts only:

```json
{"scanResolutionId":"<owned-resolution UUID>"}
```

No customer/store, amount, business date or actor is accepted in the body. The
201 no-store response contains `visitId`, `confirmedAt`, `businessDate`,
`earnedPoints` and `updatedBalance`, all server-derived. No Location is returned
because a visit-by-ID read endpoint does not exist.

Confirmation authorizes the resolution owner and current store assignment, checks
customer eligibility, then serializes using this lock order:
**idempotency key → LoyaltyAccount → QR token → ScanResolution**. The account lock
is shared with issuance, adjustments and existing visit awards; token/resolution
entities are refreshed after locking so cached pre-lock lifecycle values cannot
permit replay. No remote call occurs under these locks.

A naturally expired QR still supports a resolution created while it was valid,
until that resolution's own two-minute deadline. Explicit revocation/replacement
and consumption invalidate outstanding sources. Confirmation does not re-run the
original QR expiry check or require retaining/reposting the raw token.

One transaction creates the immutable Visit and its matching EARN using the
existing VisitService, consumes resolution and QR, and appends the successful scan
event, generic audit record and idempotency result. Any failure rolls everything
back. One qualifying visit per customer/Europe-London date still applies across
stores. Distinct resolutions racing for the same source cannot double-award;
same-key concurrent calls share the original result.

Idempotency uses the existing actor/operation/key scope and a hash of the resolution
UUID, never the QR. Same key/body returns the original 201 snapshot, including after
resolution expiry/consumption, without new business/event writes. A changed body
returns `409 IDEMPOTENCY_KEY_REUSED`. Current identity, ownership, assignment,
store and customer eligibility are checked before replaying a stored result.
Existing minimum 24-hour/indefinite-until-cleanup retention remains unchanged.

| HTTP/code | Meaning |
| --- | --- |
| 400 `VALIDATION_ERROR` / `MALFORMED_REQUEST` | Missing/unknown/invalid DTO fields or idempotency header |
| 401 `UNAUTHENTICATED` | Missing/expired/disabled session |
| 403 `FORBIDDEN` | Wrong role, self-service or CSRF denial |
| 404 `RESOURCE_NOT_FOUND` | Missing/foreign resolution, inactive/unassigned store or ineligible customer |
| 409 `QR_INVALID` | Malformed, forged, unknown-key/unknown-record or invalid-claims QR; no crypto distinction leaked |
| 409 `QR_EXPIRED` / `QR_REVOKED` | Expired QR at scan, or explicitly unavailable source |
| 409 `SCAN_RESOLUTION_EXPIRED` / `SCAN_RESOLUTION_CONSUMED` | Expired or already used resolution |
| 409 `VISIT_ALREADY_RECORDED` | Customer already qualified today, including another resolution winning the race |
| 429 `RATE_LIMITED` | Scanning budget exhausted; Retry-After supplied |

### Events, privacy and verification

Scan events are JDBC append-only records with safe token jti (nullable on invalid
input), staff/store references where safely known, server time, outcome/reason code
and server requestId. Successful resolution/confirmation events commit in the
business transaction; confirmation also uses the existing generic AuditService.
Rejected domain attempts are persisted in a separate transaction **after rollback**,
so duplicate/replay/expired/scope failure events survive without partial awards.
Malformed DTOs and pre-controller authentication/CSRF denials remain handled by
shared security/MVC infrastructure. If the database is unavailable, rejected-event
persistence falls back to a safe correlated server log without masking the original
response. Foreign-resolution failures retain no other staff's token/store context.

The raw encoded QR is never persisted or put in event/audit/error metadata. Its
request DTO has a redacted toString and a write-only token field. Keep application,
framework, JDBC bind, proxy and APM request-body tracing disabled/redacted for real
QR and credentials. Never put the token in a URL or request ID. Existing problem
responses expose safe static details, never parser/crypto/SQL exception text.

Tests exercise real PostgreSQL cookie sessions/CSRF, crypto validation reuse,
120-second/natural-expiry rules, cross-staff/store scope, immutable constraints,
rollback after event/audit/retry writes and coordinated confirmation races.

```sh
./mvnw -B -ntp -Dtest=StaffQrScanTests,StaffQrConfirmationTests,StaffQrPersistenceTests,StaffScanRateLimiterTests,MigrationTests test
./mvnw -B -ntp clean verify
```

Manual lookup and ScanResolution front-end integration remain out of scope
for Issue #22. Later issue sections below describe history controls and streaks. Issue #21's internal validator remains non-public.

## QR history and abuse controls (Issue #23)

Issue #23 extends the Issue #22 history/writers and limiter infrastructure. It
adds no new product endpoint and does not duplicate QR validation, scan resolution,
visit awarding, audit or idempotency. Existing legitimate scan/confirm semantics
remain, including the distinction between natural QR expiry and explicit revocation.

`qr_scan_events` remains the append-only operational source of truth. Its typed
`ScanEventOutcome` values are **RESOLUTION_CREATED**, **VISIT_CONFIRMED** and
**REJECTED**, retaining the established database names. Rejection writers accept
only the shared `ApiErrorCode` enum: QR_INVALID (malformed/forged/unknown token or
key, grouped safely), QR_EXPIRED, QR_REVOKED, RESOURCE_NOT_FOUND (missing/wrong-staff
resolution or store scope), FORBIDDEN, UNAUTHENTICATED, SCAN_RESOLUTION_EXPIRED,
SCAN_RESOLUTION_CONSUMED, VISIT_ALREADY_RECORDED, IDEMPOTENCY_KEY_REUSED,
RATE_LIMITED, CONFLICT or INTERNAL_ERROR as applicable. No arbitrary exception
message or free-text request reason becomes history. Internal read projections
retain reason strings for compatibility with immutable historical/future codes.

The `QrScanEvent` projection contains only event ID, optional trusted token jti,
server-derived staff/store IDs, server time, category/reason, server requestId
and optional resolution/visit references. Raw QR, email, passwords, cookies,
CSRF, signing material, IP addresses and request bodies are not persisted/logged
by this history subsystem. A rejected scan stores a store ID only after current
assignment and active-store validation, not merely because that UUID exists.
Owned-resolution failures can retain their previously validated token/store and
resolution reference; a wrong staff member's attempt retains none of the victim's
context. Untrusted correlation headers are still replaced by RequestIdFilter.

`V11__harden_qr_scan_history.sql` adds nullable, restrictive resolution/visit FKs
for operational correlation and three indexes: staff/time/id, store/time/id and
outcome/time/id. Existing token/time and requestId indexes remain useful. The old
staff/store/time index cannot order staff-only history by time because store_id
intervenes; the new staff-only index serves that real query. V1–V10 are unchanged.
V10 rows keep their original event IDs, timestamps, outcomes and reasons; new
references remain null on legacy rows rather than inventing links or updating
immutable history. The existing PostgreSQL UPDATE/DELETE/TRUNCATE guards remain.

`ScanEventRepository` supplies **internal-only**, exact-anchor queries by staff,
store, token jti, outcome or requestId. Every query requires an inclusive-from /
exclusive-to window of at most 31 days and 1–100 rows, ordered by occurredAt DESC,
id DESC. It has no mutation methods or HTTP route. It returns the bounded recent
slice, not a new public pagination contract. Future admin browsing must apply
its own authorization and cursor contract; none is added here.

Successful history, generic confirmation audit, Visit/EARN, source consumption
and idempotency still commit together. Rejected domain events use the existing
REQUIRES_NEW writer **after** the business transaction has ended. Store validation
runs before opening that writer transaction, so a denied scope lookup cannot mark
it rollback-only. Event persistence failures fall back to a safe correlated log
without recursive retries or replacing the original RFC 9457 failure. Malformed
DTOs and pre-controller authentication/CSRF denials remain the shared MVC/security
boundary; the QR operational history covers authorized, well-formed staff-domain
attempts. Failed ledger insertion leaves no misleading success event or partial
Visit/EARN, while a safe independent rejection survives.

Scanning keeps the existing **60 attempts/minute/staff** and **240/minute/socket
IP** budgets. The IP reservation now runs inside the recorded attempt boundary,
so IP 429s are persisted too, using the authenticated actor and validated store
context. Raw forwarded headers do not change the socket address.

Confirmation now has an independent, temporary failed-attempt budget to bound
consumed-resolution/unknown-resolution replay spam and history growth:

| Environment setting | Default | Range |
| --- | --- | --- |
| `QR_CONFIRM_FAILURE_LIMIT` | 60 failures/minute/staff | 1–1000 |
| `QR_CONFIRM_IP_FAILURE_LIMIT` | 240 failures/minute/socket IP | 1–1000 |

These reuse SlidingWindowRateLimiter, its bounded in-memory key map and injected
Clock. In-flight operations reserve a slot; failed confirmations retain it until
expiry. Successful confirmations and exact idempotent retries release their own
reservations. IP rejection also releases the new staff reservation. A saturated
budget returns 429 RATE_LIMITED, Retry-After and no-store problem JSON, with a
safe history record and no counters in the response. Restrictions expire at the
minute boundary; there is no persistent account/token lockout. New configuration
is documented in `.env.example`; restart after changing it.

No per-token lockout or single-resolution rule was added: multiple legitimate
staff resolutions may exist for one current QR. Existing account/token/resolution
locks, daily visit uniqueness and source consumption still permit only one award.
The existing QR issuance limits are unchanged. Rate limits remain per process and
reset on restart; multi-instance deployment needs shared/gateway enforcement,
which is intentionally outside this issue. Database-backed rejection history is
not a substitute for upstream request-volume controls or a retention policy.

Verification covers safe event categories/ownership/correlation, scope omission,
internal query bounds, privacy, ledger/event/audit rollback, concurrent replay,
staff/IP 429 recording, limiter reset and successful retry reservation release.
Existing natural-expiry/revocation, crypto, auth and append-only tests remain.

```sh
./mvnw -B -ntp -Dtest=QrScanHistoryTests,QrAbuseProtectionTests,ConfirmationFailureRateLimiterTests,StaffQrConfirmationTests,MigrationTests test
./mvnw -B -ntp clean verify
```

No scan-history browsing API/UI, manual lookup, distributed limiter, retention
job or streak calculation is included in Issue #23; streaks follow below.

## Visit streaks (Issue #24)

A streak counts consecutive qualifying **Europe/London business dates**, not
elapsed 24-hour periods. Only confirmed immutable Visits count. The first date
starts at 1; the immediately following date increments; a gap resets the stored
current count to 1 on the next Visit. Repeating a date does not increment (the
Visit uniqueness rule normally rejects that confirmation). Longest count and
last qualifying date remain historical. Calendar-date arithmetic handles both
BST/GMT transitions and London midnight; the injected server Clock is authoritative.

`loyalty.streaks` is a maintained projection with a shared customer-profile primary
key, current/longest counts, last qualifying date, and optimistic version. V12
creates it with restrictive ownership and state/count constraints. Registration
creates an empty row in the same transaction as User, CUSTOMER grant, profile and
account. V12 backfills every existing profile from its real Visit date islands:
the latest run supplies current count/date, the longest run supplies longest count,
and customers without Visits get an empty row. Backfill uses date order, never
insertion order or the deployment date; Flyway records it once and validates it on
subsequent startup. Existing Visit/EARN history remains untouched.

Confirmation updates Visit, matching EARN and Streak within the existing transaction,
under the same customer/account pessimistic lock, before QR/resolution consumption
and successful audit/event/idempotency writes commit. No new lock ordering is added.
A projection or later workflow failure rolls everything back. Optimistic versioning
also detects conflicting projection writes. Missing state fails closed instead of
inventing history. Normal runtime only confirms current server-date Visits; an
older date than the last projected date is rejected with `409 CONFLICT`. Historical
imports/corrections would need a separately designed projection rebuild and are not
supported by this endpoint. Never insert historical Visits behind the projection.

`GET /api/v1/customers/me/streak` requires an active, explicit CUSTOMER grant and
resolves ownership from the authenticated session. STAFF/ADMIN alone cannot read it.
No customer/date query parameters or mutation routes are accepted. The response is
private (`Cache-Control: no-store`), contains no identifiers or persistence version:

```json
{
  "currentStreak": 3,
  "longestStreak": 7,
  "lastQualifyingDate": "2026-10-14",
  "serverDate": "2026-10-15"
}
```

An empty state returns zero counts and a null last date. Today/yesterday retain the
stored current count for display; an older last date displays zero. Reads never
change stored count, longest, date or version. A future stored date also displays
zero defensively. The next qualifying Visit after a missed day resets current to 1
while preserving longest. There is no scheduler resetting customer history.

Streak prize entitlement (including 10/20/30-day benefits), redemption, milestone
awarding, admin correction and Issue #25 remain deferred.

Verification uses real PostgreSQL for V11→V12 historical/empty backfill, repeat
migration validation, state constraints, Hibernate mapping, atomic registration and
confirmation failures, same-date races, cookie-session ownership/roles and safe
responses. Unit and service tests cover gaps, longest retention, pure stale reads,
out-of-order rejection, London midnight and both DST transitions.

```sh
./mvnw -B -ntp -Dtest=StreakModelTests,StreakVisitTests,StreakApiTests,VisitConcurrencyTests,VisitServiceTests,MigrationTests test
./mvnw -B -ntp clean verify
```

## Reward catalogue (Issue #25)

Reward is mutable catalogue data, not a redemption or an entitlement. V13 creates
`loyalty.rewards`: UUID identity, bounded name (200) and description (2000), exact
BIGINT point cost, active flag, nullable availability bounds, created/updated UTC
timestamps, and an optimistic version. The architecture explicitly permits
nonnegative costs: **0 through 9,007,199,254,740,991** are supported; floating-point
and quoted numeric JSON values are rejected. Zero cost grants no benefit by itself.
Name is required and stripped; description is required but may be empty. Null
characters are rejected before PostgreSQL. Normal operations deactivate retained
catalogue rows; there is no repository delete operation or HTTP DELETE endpoint.
Future redemptions must use restrictive reward references and snapshot benefit/cost
at creation so catalogue edits never rewrite their history.

V13 seeds exactly the documented production default: **Free coffee, 3,000 points**
(`e83bb76e-b4bf-470b-a3cc-5fc67495f162`), active with no availability bounds. It does
not seed demo rewards, points, prizes or redemptions. The migration owns this initial
configuration; subsequent Flyway startup does not overwrite ADMIN changes.

Availability is `active && (from == null || now >= from) && (until == null || now < until)`.
The start is inclusive, the end exclusive; null is unbounded. When both bounds are
provided the end must be strictly later. Bounds are UTC instants (years 0001–9999),
with at most PostgreSQL microsecond precision. Server Clock determines availability;
client date/time, timezone and filters cannot change it. Active and window state are
independent. Expired and scheduled definitions remain visible to ADMIN management.

| Method and path | Permission / result |
| --- | --- |
| `GET /api/v1/rewards` | Explicit CUSTOMER **or STAFF**; only active, currently available rewards. ADMIN alone does not inherit this permission. |
| `GET /api/v1/admin/rewards` | ADMIN; all definitions including inactive, scheduled and expired ones. |
| `GET /api/v1/admin/rewards/{id}` | ADMIN lookup of a retained definition, or 404. |
| `POST /api/v1/admin/rewards` | ADMIN + CSRF; create, 201 with a retrievable relative Location. |
| `PATCH /api/v1/admin/rewards/{id}` | ADMIN + CSRF; allowlisted partial update, 200. |

List responses follow the architecture's cursor contract: `items`, `nextCursor`
(null at end), `hasNext`. Only `size` (default 20, range 1–100) and `cursor` are
accepted. Order is **pointCost ASC, name ASC, id ASC**, using database string/UUID
ordering. The bounded opaque cursor contains that tuple and listing scope; it can
never switch an ordinary reader into admin scope. Availability is recalculated on
every page, so paging a changing catalogue is not a snapshot. All responses use
`Cache-Control: no-store`; no shared/public caching policy is introduced.

The reader DTO contains only `id`, `name`, `description`, `pointCost`, `availableFrom`
and `availableUntil`. Admin DTOs additionally expose active and created/updated times,
never persistence version or audit metadata. Creation requires name, description,
pointCost and active; availability fields are optional. PATCH accepts only these six
editable fields. Omitted properties are preserved; explicit null clears either
availability bound, while null name/description/cost/active is invalid. Empty patches,
unknown fields and invalid resulting windows return 400. The merged full state is
validated before mutation. Example:

```json
{"active": false, "availableUntil": null}
```

Security checks both route permissions and fresh active-account/role grants in
RewardService. Every privileged mutation appends `REWARD_CREATED`, `REWARD_UPDATED`
or (active→inactive) `REWARD_DEACTIVATED` in the **same transaction** as the definition
change. Metadata contains only allowlisted changed field names, never request text.
An audit failure rolls the reward change back; rejected operations leave no success
audit. Optimistic version checks reject overlapping saves with `409 CONFLICT`.
This protects concurrent transactions; stale sequential browser edits are not an
ETag/If-Match workflow in this phase. Refresh before editing and after any conflict.
Catalogue mutations do not require Idempotency-Key under the architecture contract;
do not automatically retry POST creation after an ambiguous network failure.

Issue #25 covers catalogue management only; its operations never mutate the point
ledger or streaks. Issue #26 POINTS redemption follows below. Fulfilment, refunds
and prize entitlement remain deferred.

```sh
./mvnw -B -ntp -Dtest=RewardModelTests,RewardCatalogueTests,RewardAdminTests,RewardConcurrencyTests,MigrationTests test
./mvnw -B -ntp clean verify
```

## Transactional points reward redemption (Issue #26)

`POST /api/v1/customers/me/redemptions` requires an active, explicit CUSTOMER grant,
a valid session, CSRF and a UUID `Idempotency-Key`. STAFF/ADMIN alone cannot redeem;
combined-role accounts need an explicit CUSTOMER grant. Ownership and actor always
come from the authenticated principal. The allowlisted body contains only:

```json
{"rewardId":"e83bb76e-b4bf-470b-a3cc-5fc67495f162","storeId":"<active-store-uuid>"}
```

The response is `201 Created`, `Cache-Control: no-store`, with a same-origin relative
Location at `/api/v1/customers/me/redemptions/{redemptionId}`. Its DTO contains
redemptionId, rewardId, rewardName, pointCost, PENDING status, storeId, requestedAt,
expiresAt and newBalance. No balance setter, price/amount override, customer/actor,
source, status or expiry input is accepted. No QR scan or physical-presence check
is required for this customer operation.

A small `GET /api/v1/customers/me/redemptions/{id}` supports owned resource recovery
and makes Location retrievable. It returns safe stored benefit/cost/source/status
and time snapshots; it exposes no customer/account IDs, version, session or audit
internals. Missing and another customer's IDs both return 404. It does not present
the creation balance as a live balance. Issue #26 originally deferred collection history/fulfilment; Issue #27 below adds
those operations. Cancellation, expiry processing and refunds remain deferred.

V14 creates `loyalty.reward_redemptions`: UUID identity, customer/account/store/reward
references, source POINTS, name/description/cost snapshots, PENDING status, requested
and expiry instants, optimistic version and nullable future fulfilment fields. Status
vocabulary also reserves FULFILLED/CANCELLED/EXPIRED for later lifecycle work; there
are no methods/endpoints implementing those transitions. CustomerPrize and PRIZE
sources remain deferred until real entitlement references exist.

The request instant comes from the injected server Clock after acquiring all locks,
truncated to PostgreSQL microseconds. Expiry is **24 UTC hours (86,400 seconds)**
after request time, including across London DST changes. This is a duration, unlike
visit/streak business-date arithmetic. Expiry is retained evidence; reads do not
change PENDING into EXPIRED. Issue #27 fulfilment below enforces this deadline; no expiry job is added. Inactive, future, expired and missing rewards, or inactive/missing
stores, return safe 404 responses. Availability start is inclusive and end exclusive.

Catalogue definitions permit zero cost, but this **POINTS** workflow requires a
positive cost in `1..9,007,199,254,740,991`, because every successful request must have
a matching negative ledger entry. A zero-cost definition returns 409 CONFLICT; it is
not a free-entitlement or prize-redemption shortcut. The seeded 3,000-point coffee
is redeemable when the customer has sufficient earned/adjusted points.

One transaction performs these operations:

1. Resolve current customer/profile and take the existing actor/operation/key retry
   lock. Hash only canonical reward/store IDs; no credentials/session data is stored.
2. Lock the customer's LoyaltyAccount and recheck active account/role grants after
   waiting. The account lock remains first among business aggregates.
3. On an exact committed retry, return the original 201 body/Location and creation
   balance without another debit/audit, even if catalogue/store state later changed.
   Removed permissions or inactive identity cannot retrieve the stored result.
4. For a new operation, take shared reward then store configuration locks, recheck
   active state/availability against server time, and calculate the exact ledger sum.
   Shared locks keep catalogue/store edits from changing the accepted snapshot while
   this transaction commits. Admin edits acquire no account lock in reverse order.
5. Reject insufficient points with `409 INSUFFICIENT_POINTS`. Validate the resulting
   nonnegative JavaScript-safe total, append the PENDING request, and append its
   negative REDEMPTION transaction through LoyaltyAccountService.
6. Append `REWARD_REDEEMED` audit and the retry result in that same transaction. Audit
   metadata contains only safe customer/reward/store IDs and point cost. Return only
   after commit. Any failure rolls request, deduction, account version, success audit
   and retry result back; the key remains usable for a corrected retry.

Keys are scoped to authenticated actor and `CUSTOMER_REWARD_REDEMPTION`. A changed
payload with the same key returns `409 IDEMPOTENCY_KEY_REUSED`; different keys are
separate redemptions only when points suffice. Existing retention is
at least 24 hours; no key cleanup job is introduced here. The stored response's
newBalance is the balance at original creation, not recalculated on replay.

V14 adds `point_transactions.redemption_id`. Database constraints and triggers enforce
one negative deduction per source, correct account/customer ownership, exact snapshot
cost, customer actor and request timestamp. A deferred constraint requires the
matching deduction before a POINTS redemption can commit; conversely a deduction
requires its real request. The raw-SQL deduction guard also locks the account and
rejects a negative resulting total. Existing safe-total/version and immutable ledger,
EARN and ADJUSTMENT guards remain intact. Snapshots/identity/request/expiry cannot be
rewritten; requests cannot be deleted/truncated and references are restrictive.
Later catalogue edits/deactivation never change historical name/description/price.

The migration seeds no redemptions or deductions. Before Issue #26 there was no
production REDEMPTION writer. If a database nevertheless contains legacy unlinked
REDEMPTION rows, V14 fails explicitly rather than inventing requests or editing
immutable history; investigate and plan a reviewed data migration before deployment.
V1–V13 remain unchanged. No fake future prize/fulfilment records are backfilled.

Tests cover real PostgreSQL source pairing, ownership/FKs, immutability, safe amount
bounds, exact availability/expiry, HTTP session/CSRF/role isolation, snapshot retention,
exact retry and payload conflict, failures before/after deduction and audit/retry
writes, and concurrent attempts on the same/different rewards. Lock-wait tests prove
availability and permission revocation are rechecked after real database blocking.

```sh
./mvnw -B -ntp -Dtest=RedemptionApiTests,RedemptionIdempotencyTests,RedemptionRollbackTests,RedemptionConcurrencyTests,RedemptionPersistenceTests,RedemptionLockingTests,MigrationTests test
./mvnw -B -ntp clean verify
```

Issue #26 scope excluded staff fulfilment, now described below. Cancellation, expiry
jobs, refunds, prize redemption, streak prizes and frontend integration remain deferred.

## Redemption history and staff fulfilment (Issue #27)

`GET /api/v1/customers/me/redemptions` requires an active explicit CUSTOMER grant.
Ownership is resolved from the session; customerId/status/time/sort filters are not
accepted. The response contains `items`, `nextCursor` (null at end) and `hasNext`.
Only size (default 20, range 1–100) and cursor are accepted. Keyset ordering is
**requestedAt DESC, id DESC**, using PostgreSQL UUID byte order for timestamp ties.
The bounded opaque cursor is bound to the server-resolved customer. Forging a
position cannot expand query ownership. Each page re-reads current status; new rows
may appear before the current position without repeating older rows.

History and the preserved owned `GET /api/v1/customers/me/redemptions/{id}` return
retained name/description/cost snapshots, IDs of the reward/store, source, status,
requestedAt, expiresAt and nullable fulfilledAt. Existing `rewardName` and `pointCost`
field names remain snapshot values. They never expose another customer's records,
customer/account IDs, persistence version or staff/security internals. Catalogue edits
and deactivation do not rewrite these values. All responses use no-store caching.

`POST /api/v1/staff/redemptions/{id}/fulfilments` requires STAFF, CSRF and a UUID
Idempotency-Key. Send no body (an empty JSON object is also supported). Unknown body
fields and query parameters are rejected; callers cannot choose actor/store/status,
price, balance, refund or timestamp. ADMIN alone has no staff permission; an explicit
STAFF grant is required. The response is 200 with redemptionId, FULFILLED status,
storeId and the original fulfilledAt; the internal fulfilledBy ID is not exposed.

The service checks current active STAFF and customer grants, the redemption's active
store and the staff member's active assignment to **that** store. Unassigned/out-of-
scope/missing resources return the same safe 404. Staff cannot fulfil their own
customer redemption, including combined CUSTOMER/STAFF/ADMIN accounts (403). Disabled
accounts and removed grants cannot fulfil or replay stored results.

Retry infrastructure takes its existing actor/operation/key advisory lock first.
Business locks then follow **customer LoyaltyAccount → RewardRedemption**, with the
initial nonlocking lookup returning only target IDs to avoid caching stale state.
Permissions, assignment, active store/customer and self-service rules are checked
again after waiting. A PENDING row can transition to FULFILLED only before its
exclusive expiry (`serverNow < expiresAt`) and not before requestedAt. Time comes
from the injected server Clock at microsecond precision, after the locks.

Fulfilment changes only status, fulfilledBy (authenticated staff), fulfilledAt and
optimistic version. It creates **no PointTransaction** and leaves account balance,
original deduction, benefit/cost snapshots, owner/store and request/expiry times intact.
The state change, `REDEMPTION_FULFILLED` audit and retry result commit together. Audit
metadata contains only safe resource IDs, with server actor/time/request correlation;
it stores no request bodies, emails or session data. Failures after audit/retry writes
roll everything back to PENDING with null actor/time and no success audit/result.

The operation key scope is `STAFF_REDEMPTION_FULFILMENT`. Same actor/key/redemption
returns the original result; changing the path ID with a used key returns 409
IDEMPOTENCY_KEY_REUSED. A new key or another currently assigned staff member repeating
an already FULFILLED redemption gets its existing result without another transition
or audit. Successful natural no-ops can retain their own retry result. Current scope
is always rechecked, even after fulfilment/expiry or on an exact replay. Fulfilled
actor/time never change. Concurrency serializes competing callers to one transition
and one success audit, with the same result returned to valid subsequent callers.

**Expiry/refund decision:** expired PENDING requests return 409 CONFLICT without
changing state or issuing a refund. The status remains PENDING with a past expiresAt,
and the original points deduction remains reserved. No automatic recovery/refund is
provided by this issue; cancellation/refund/expiry processing require their later
atomic lifecycle implementation. CANCELLED/EXPIRED states also return 409. Fulfilled
rows remain fulfilled after expiry; already-completed retries do not re-apply the
PENDING expiry check or charge points.

V14 already supplies fields, fulfilment consistency/FKs and immutable source/deletion
guards. `V15__support_redemption_fulfilment.sql` adds the missing database guard against
reversing FULFILLED/CANCELLED/EXPIRED or changing terminal fulfilment actor/time. It
changes no stored history and adds no business records. V1–V14 remain untouched.

Tests cover owned/tied-timestamp cursor pages, source snapshots, permissions and body
spoofing, exact expiry, staff self-service, roles/store/assignment/customer eligibility,
real lock waits and revocation, exact/natural retries, same/different-key/staff races,
audit/retry rollback, FK/evidence consistency and database terminal-state guards.

```sh
./mvnw -B -ntp -Dtest=RedemptionHistoryTests,FulfilmentApiTests,FulfilmentTransactionTests,FulfilmentIntegrityTests,FulfilmentLockingTests,MigrationTests test
./mvnw -B -ntp clean verify
```

Customer cancellation, refund entries, background expiry, prize-backed redemptions,
staff search dashboards, admin redemption editing and Issue #28 remain deferred.

## References

- [Spring Security authentication persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)
- [Spring Session JDBC configuration](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
- [Spring Security CORS integration](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [Spring Boot Actuator health](https://docs.spring.io/spring-boot/api/rest/actuator/health.html)
- [Spring Boot database initialization](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html)
- [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/)
- [Architecture domain model](../docs/architecture/domain-model.md)
- [Architecture security and testing](../docs/architecture/security-and-testing.md)

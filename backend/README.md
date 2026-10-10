# Loyalty backend

Java 21, Spring Boot 4.1.1 and Maven. Issues #8–#11 establish a modular monolith
with PostgreSQL and Flyway. Issue #12 adds identity persistence only; no
registration, login or other product APIs are implemented.
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
boundaries remain package placeholders. No product controllers or business
services are introduced. The frontend is unchanged.

Only safe health reads and valid API CORS preflights are public. All other routes
remain denied (anonymous callers receive `401`; authenticated callers and CSRF
failures receive `403`). No generated user, form login or HTTP Basic is added.
Authentication and domain features belong to later issues.

## Identity persistence (Issue #12)

`V2__create_identity_model.sql` adds only `loyalty.users`, `roles`, `user_roles`
and `customer_profiles`. V1 is unchanged. Hibernate remains in `validate` mode.
Flyway seeds the three role codes, never users or default credentials.

- `User`: generated UUID, canonical email, supplied password hash, account status,
  UTC created/updated instants. Email is stripped and lowercased with `Locale.ROOT`.
  Future services must use `User.canonicalizeEmail` before exact repository lookups.
  Password hashing/verification is not implemented; callers must eventually supply
  a hash from the approved encoder, never a plaintext password.
- `Role`: string code primary key, restricted to CUSTOMER/STAFF/ADMIN. A lazy
  user-owned many-to-many association maps explicit grants through `user_roles`.
  Its `(user_id, role_code)` primary key prevents duplicate grants. There is no
  hierarchy, implicit customer role or SYSTEM role. Role lookup is read-only.
- `CustomerProfile`: generated UUID and one required, unique user reference;
  display name, leaderboard alias and a required privacy boolean. No first/last
  name, profile metadata, loyalty state or game state is invented. Profile is
  owned through the identity boundary's customer package, separate from credentials.

The architecture specifies activation/deactivation, without a LOCKED lifecycle;
this model therefore uses ACTIVE/DISABLED only. Session invalidation and last-admin
protection belong to future authorized services. A profile's CUSTOMER eligibility
must be checked by that future service; this issue adds no registration/role-grant
workflow or database trigger tying profile existence to a role.

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
and hash safety. The migration tests verify V1+V2 from an empty database,
repeat migration and checksum protection. Both application profiles validate
all three entity mappings at startup. There are no auth endpoints or accounts
seeded by this issue.

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
exceptions are sanitized too; auth workflows remain unimplemented. Never turn
arbitrary database exceptions into business conflicts: unexpected persistence
failures remain generic `500` errors. Add explicit safe domain codes only with
the later feature's documented contract. Preserve `Allow`/`Retry-After` headers
when provided by MVC.

`ApiExceptionHandler` extends Spring MVC's `ResponseEntityExceptionHandler`.
Security errors use the same DTO via `ApiErrorWriter` because they occur before
MVC advice. Boot's fallback error settings also disable exception/message/trace
exposure. MVC and security tests use controllers available only on the test
classpath; the packaged application has no product controllers.

```sh
./mvnw -B -ntp -Dtest=ApiErrorTests test
./mvnw -B -ntp clean verify
```

## References

- [Spring Security CORS integration](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [Spring Boot Actuator health](https://docs.spring.io/spring-boot/api/rest/actuator/health.html)
- [Spring Boot database initialization](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html)
- [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/)
- [Architecture domain model](../docs/architecture/domain-model.md)
- [Architecture security and testing](../docs/architecture/security-and-testing.md)

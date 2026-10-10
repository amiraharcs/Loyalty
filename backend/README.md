# Loyalty backend

Java 21, Spring Boot 4.1.1 and Maven. Issues #8–#9 establish a modular monolith
with PostgreSQL and Flyway. No business APIs or domain tables are implemented.
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
on it. It creates no domain tables. The only foundation table is Flyway's own
schema history. Add future immutable migrations as `V2__description.sql`, etc.;
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
JPA startup without domain entities, empty-database migration, repeat migration,
refusal of unmanaged schema/clean, and production configuration failures.
Existing closed security and validation checks remain in place. `verify` compiles,
tests and packages a runnable JAR.

## Structure and scope

Root package: `com.amiraharcs.loyalty`. Domain boundaries remain lightweight
`package-info.java` files: `auth`, `customer`, `loyalty`, `visit`, `store`,
`reward`, `qr`, `game`, `leaderboard`, `competition`, `admin`, `audit`, `common`.
`common/config` contains the foundation security policy and production database
settings guard. No controllers, entities, repositories or business services
are introduced. The frontend is unchanged.

All HTTP requests remain denied; no generated user, form login or HTTP Basic is
added. Actuator endpoints remain disabled. Error handling, CORS/health policy,
authentication and domain features belong to later issues.

## References

- [Spring Boot database initialization](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html)
- [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/)
- [Architecture domain model](../docs/architecture/domain-model.md)
- [Architecture security and testing](../docs/architecture/security-and-testing.md)

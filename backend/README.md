# Loyalty backend foundation

Java 21 + Spring Boot 4.1.1, built with Maven as one executable application.
This is the foundation for [Issue #8](https://github.com/amiraharcs/Loyalty/issues/8),
not an implementation of the product APIs.

The [P1 architecture documents](../docs/architecture/README.md) remain the source
of truth for domain ownership, business rules, API conventions and security.
The existing React frontend is a separate project and is unchanged here.

## Requirements and setup

- Install a JDK **21** and point `JAVA_HOME` to its JDK home, not its `bin` folder.
- Use the checked-in Maven Wrapper; it pins Maven **3.10.0**. A globally installed
  Maven is not required. The first run downloads Maven and dependencies.
- No database, Docker, environment secrets or demo accounts are needed for this issue.

Check your Java installation:

```sh
java -version
```

On macOS with an installed Java 21 JDK:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

From the repository root:

```sh
cd backend
./mvnw -version
./mvnw -B -ntp clean verify
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd` in place of `./mvnw`. The wrapper download and normal
Maven cache live in the user's Maven directory, outside the source tree.

## Configuration and profiles

| File | Responsibility |
| --- | --- |
| `src/main/resources/application.yaml` | Application name, default profile, `PORT`, and explicit foundation-only auto-configuration exclusions. |
| `src/main/resources/application-dev.yaml` | Local loopback binding and normal INFO logging. |
| `src/main/resources/application-prod.yaml` | Production profile, INFO logging and graceful shutdown with a 20-second deadline. |

`dev` is the default. The local server binds to `127.0.0.1:8080`. Set `PORT` to
change the port; do not commit machine-specific values or secrets.

```sh
PORT=8081 ./mvnw spring-boot:run
```

Select production explicitly rather than relying on the development default:

```sh
./mvnw -B -ntp clean verify
java -jar target/loyalty-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

`SPRING_PROFILES_ACTIVE=prod` is also supported. Production uses the server's
normal bind address rather than the dev loopback restriction. These configuration
files establish a profile structure; they are not a complete production deployment.

## Foundation behaviour and scope

The application starts an embedded servlet server but defines **no business
controllers, entities, repositories, services or API routes**.

Spring Security has a minimal closed policy: every request is denied. Form login,
HTTP Basic and logout handling are disabled, the generated default user is
suppressed, and CSRF protection remains enabled. A request to `/`, `/login` or an
unimplemented API path receives `403`; this is expected, not a startup failure.
There are no credentials to try. Real authentication/session and role checks
belong to P3, following the architecture docs.

Persistence libraries are prepared, but these auto-configurations are excluded
in **both profiles** until Issue #9:

- `DataSourceAutoConfiguration`
- `HibernateJpaAutoConfiguration`
- `FlywayAutoConfiguration`

No PostgreSQL connection, schema, migration, embedded database or fake datasource
is created. Issue #9 must remove these three exclusions and add environment-based
database configuration, Flyway migrations and real PostgreSQL migration tests.
The generated-user exclusion is separate and should be revisited with P3, not
removed as part of database setup.

Actuator is present as a dependency but its endpoint access is disabled. CORS,
health exposure, database health and operational access policy belong to Issue #11.
The foundation does not implement the API error contract from Issue #10.

No business features, payment processing or stored-value functionality are added.

## Package structure

The root application package is `com.amiraharcs.loyalty`, so Spring's component
scan covers all of its future modules. Each empty boundary is represented by a
small `package-info.java`, not dummy controllers, services or entities.

```text
com.amiraharcs.loyalty
├── LoyaltyBackendApplication
├── auth
├── customer
├── loyalty
├── visit
├── store
├── reward
├── qr
├── game
├── leaderboard
├── competition
├── admin
├── audit
└── common
    └── config
        └── FoundationSecurityConfiguration
```

These packages refine the logical P1 modules without changing their ownership:

| P1 boundary | Java packages |
| --- | --- |
| Identity | `auth`, `customer` |
| Stores | `store` |
| Loyalty | `loyalty`, `visit` |
| Rewards | `reward` |
| QR | `qr` |
| Game | `game` |
| Competition | `leaderboard`, `competition` |
| Audit | `audit` |
| Administrative coordination / technical infrastructure | `admin`, `common` |

`admin` will orchestrate authorised domain services; it must not become a second
owner of their entities. Keep domain rules out of `common`. Introduce internal
subpackages only when real types require them. Package boundaries are currently
documented conventions; no Spring Modulith or automatic boundary-enforcement
library is introduced in this issue.

## Dependencies

Spring Boot's parent manages compatible dependency and plugin versions:

- `spring-boot-starter-webmvc`: servlet/MVC infrastructure and embedded Tomcat.
- `spring-boot-starter-data-jpa`: JPA infrastructure prepared for Issue #9.
- `spring-boot-starter-validation`: Jakarta Bean Validation.
- `spring-boot-starter-security`: the closed foundation filter chain.
- PostgreSQL JDBC driver: runtime dependency for the future database connection.
- `spring-boot-starter-flyway` and `flyway-database-postgresql`: migration
  infrastructure and PostgreSQL support, currently inactive.
- `spring-boot-starter-actuator`: operational infrastructure, currently unexposed.
- Test-scope Spring Boot Test, Web MVC Test and Security Test starters: JUnit,
  assertions, application-context tests, MockMvc and CSRF request support.

There is no session JDBC, OAuth/JWT, OpenAPI, Lombok, in-memory database, business
library or test database container added ahead of its implementation issue.

## Checks

```sh
./mvnw -B -ntp clean verify
```

The test suite exercises both the default development profile and explicit
production profile. It verifies context/validation startup, absence of datasource,
JPA factory and Flyway beans, no generated user, a closed HTTP surface even with
a valid CSRF token, no login redirect/Basic challenge, and profile-specific
settings. It does not test business rules that have not been implemented.

`verify` compiles Java 21 bytecode, runs the tests and packages a runnable JAR.
Generated files and IDE/environment files are ignored by `backend/.gitignore`.

## References

- [Spring Boot 4.1.1 system requirements](https://docs.spring.io/spring-boot/4.1/system-requirements.html)
- [Spring Initializr](https://start.spring.io/): source of the standard Maven Wrapper scripts.
- [P1 domain boundaries](../docs/architecture/domain-model.md)
- [P1 security and testing plan](../docs/architecture/security-and-testing.md)

# P1 architecture decisions

These documents define the production v1 design for Loyalty. They complete the
P1 Architecture issues; they do not claim that the backend is implemented.

The repository currently contains a React frontend demo. Its browser storage,
demo login, simulated scan, fixed QR and fixture leaderboard are not trusted
production services. The existing live demo remains unchanged by this phase.

The planned system is a modular Spring Boot application backed by PostgreSQL,
with Flyway migrations and the existing React frontend. Start with one backend
deployment and one database; separate modules by business responsibility rather
than deploying microservices. Backend delivery belongs to P2 and later phases.

| Issue | Decision document |
| --- | --- |
| [#3 Actors and authorisation](https://github.com/amiraharcs/Loyalty/issues/3) | [Roles and permissions](roles-and-permissions.md) |
| [#4 Use cases and business rules](https://github.com/amiraharcs/Loyalty/issues/4) | [Business rules](business-rules.md) |
| [#5 Domain model](https://github.com/amiraharcs/Loyalty/issues/5) | [Domain model and ER diagrams](domain-model.md) |
| [#6 REST API conventions](https://github.com/amiraharcs/Loyalty/issues/6) | [API conventions](api-conventions.md) |

## Reading and implementation conventions

- Treat the decisions as a shared implementation baseline. Changes to business
  rules, security boundaries or API contracts must update the relevant document.
- Values marked as v1 defaults are concrete initial choices, configurable only
  by trusted server configuration or explicitly authorised admin actions.
- Use an injected server clock and store instants in UTC. Business dates and
  weekly periods follow `Europe/London`, including daylight-saving changes.
- No payment processing, stored money, mobile-wallet integration or real-time
  multiplayer is included in this architecture.
- Keep the prototype's frontend presentation while replacing demo state with
  authorised server responses during the integration issues.

The frontend implementation guide remains in [frontend/README.md](../../frontend/README.md).

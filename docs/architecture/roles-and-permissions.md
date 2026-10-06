# Actors, roles and authorisation

Completes [issue #3](https://github.com/amiraharcs/Loyalty/issues/3).
Status: production v1 design; enforcement is not present in the frontend demo.

## Actors

| Actor | Responsibility |
| --- | --- |
| Visitor | Register a customer account, sign in, and view public product information. |
| `CUSTOMER` | Manage their own profile, collect points, display a temporary identity QR, redeem eligible rewards and play GRND Jump. |
| `STAFF` | Serve customers at explicitly assigned stores: resolve a QR, confirm a visit and fulfil a redemption. |
| `ADMIN` | Manage accounts, staff-store assignments, stores, reward/prize configuration, controlled corrections and operational audit records. |
| System | Calculate derived values, expire game sessions, recharge lives and assign weekly prizes through internal jobs. |

Public registration always creates an active `CUSTOMER`; the request cannot
choose roles, stores, starting points or account status. Staff and admin roles
are assigned through admin-only operations. Bootstrap the first administrator
using a documented operator procedure, never public registration or committed
default credentials.

Roles are explicit grants, not a hierarchy. `ADMIN` does not automatically gain
customer gameplay or staff scan permissions. A user may hold multiple roles;
each action still requires its applicable role and resource scope. Deactivating
an account invalidates its sessions and prevents every authenticated operation.

## Authorisation matrix

"Own" means identity is taken from the authenticated principal, not a request
body. "Assigned" means an active staff-store association exists for that store.
"Internal" means an application service/job with no public caller-controlled
system role. A dash means denied unless another explicitly held role permits it.

| Action | Visitor | CUSTOMER | STAFF | ADMIN | System |
| --- | --- | --- | --- | --- | --- |
| Register / sign in | Yes | Yes | Yes | Yes | — |
| Read/update own basic profile; sign out | — | Own | Own | Own | — |
| Read balance, transactions, visits, streak and rewards | — | Own | Service summary only | Support lookup, audited | Internal calculation |
| Issue temporary loyalty QR | — | Own | — | — | — |
| Resolve QR or look up a customer for service | — | — | Assigned | — | Validate token internally |
| Confirm a qualifying visit | — | — | Assigned | — | Apply rules atomically |
| Create a points/prize redemption | — | Own | — | — | Apply eligibility rules |
| Inspect/fulfil a pending redemption | — | Own status only | Assigned | — | Persist state transition |
| Start game, submit score, view lives/personal best | — | Own | — | — | Validate session and recharge |
| Read weekly/all-time ranking and own rank | — | Yes, limited DTO | — | Operational summary | Compute ranking |
| Manage stores and staff-store assignments | — | — | — | Yes | — |
| Create/deactivate staff; change roles/account status | — | — | — | Yes, audited | — |
| Manage reward and prize catalogue | — | Read active catalogue | Read active catalogue | Yes | — |
| Adjust points with a mandatory reason | — | — | — | Yes, audited | Append ledger entry |
| Correct prize assignments | — | — | — | Audited, constrained | Scheduled assignment |
| Read operational reports and audit logs | — | — | — | Yes, filtered | Append events only |
| Settle weekly prizes / expire stale sessions | — | — | — | — | Internal, idempotent |

## Customer boundaries

Customer paths use `/customers/me` for account data. Where a resource has an ID,
the service additionally verifies that its customer owner matches the principal.
A customer cannot read another customer's email, transactions, QR, reward
history, game sessions or game-life state, even by guessing a valid resource ID.

Leaderboard responses contain an opaque entry identifier, public display alias,
rank and score. They never expose email, roles, account status or private profile
data. A private display preference uses a generic alias while retaining rank.

## Staff versus administrator boundaries

- Staff are restricted to active assigned stores. Requesting a different store
  ID does not expand access. Removing an assignment takes effect on the next
  operation, including for already authenticated staff.
- A staff customer lookup is for service only. Return display name, membership
  state, balance/streak summary and relevant redemption status, not credential,
  session, game-history or unnecessary contact data.
- Staff cannot manually set points, roles, prize winners or reward prices, and
  cannot edit or delete confirmed visits or ledger entries.
- Admins may change configuration and append corrections, but cannot rewrite
  the immutable ledger, invent a completed visit, alter submitted scores or
  erase audit events. A support lookup or correction records its actor/reason.
- Protect the last active administrator from removal/deactivation. Admins
  cannot bypass this safeguard by editing their own account or role mapping.
- A staff member confirming their own customer visit or redemption is denied;
  another assigned staff member must perform the service action.

## Enforcement model

Enforce the boundary twice: Spring Security limits route access, and application
services check ownership, store assignment, active account status and business
conditions. Controllers never grant permission merely because a button was
hidden in React. Unknown routes/actions default to denial.

Resource ownership predicates must also constrain database queries. For an
unauthorised resource outside a caller's permitted scope, return `404` to avoid
revealing its existence. A known action denied by role returns `403`; missing or
expired authentication returns `401`. Log the internal distinction safely.

The internal scheduler uses application-service methods, not a public endpoint
accepting `role=SYSTEM`. Database credentials also remain server-side.

## Acceptance checks for implementation

- Test each allowed matrix action and at least one denied role for that action.
- Repeat customer operations with another customer's IDs: no disclosure/change.
- Repeat staff operations against another store and after assignment removal.
- Verify registration ignores/rejects injected roles and privilege fields.
- Verify account deactivation, last-admin protection and self-service restrictions.
- Verify support/admin operations produce an audit record without secrets.

This approach follows OWASP's guidance to deny by default and validate resource
access for every request: [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html).

# Core use cases and business rules

Completes [issue #4](https://github.com/amiraharcs/Loyalty/issues/4).
Status: chosen production v1 defaults, not implemented backend behaviour.
Role boundaries are defined in [roles and permissions](roles-and-permissions.md).

## Actors and core journeys

| Actor | Journey | Successful result |
| --- | --- | --- |
| Customer | Register, sign in, update own basic profile | Active customer account and empty loyalty account; no demo points seeded. |
| Customer | View Home / Card | Authoritative balance, next reward, current streak and short-lived identity QR. |
| Customer + staff | Present QR, resolve identity, confirm shop visit | One qualifying visit, one earned-points entry and recalculated streak. |
| Customer + staff | Select reward, redeem, have it fulfilled | Points/prize reserved atomically, then one staff-confirmed fulfilment. |
| Customer | Start GRND Jump and submit result | One life consumed; one validated score attached to that session. |
| Customer | View rankings and earned prizes | Valid weekly/all-time ranking, own position and unexpired prize entitlements. |
| Staff | Look up customer, confirm visit, fulfil redemption | Only assigned-store operations and only service-required customer data. |
| Admin | Manage stores/staff/catalogue, inspect activity, correct points | Explicit permission checks, immutable correction records and audit trail. |
| System | Recharge lives, expire sessions, settle completed weeks | Repeatable calculation and idempotent prize assignment. |

No QR action transfers money or authorises payment. Apple/Google Wallet remains
outside v1. The frontend's simulated scan is a demo action, not a production API.

## Clock and configuration

Persist instants in UTC and derive loyalty dates/weekly boundaries in
`Europe/London` using its time-zone rules, not a fixed UTC offset. A server clock
is injected into services so midnight and daylight-saving cases can be tested.
Do not accept the browser's clock, date, timezone, score period or balance as
authoritative.

| Setting | v1 default |
| --- | --- |
| Points per qualifying daily visit | 100 integer points |
| Daily qualification | One visit per customer per business date, across all stores |
| First points reward | Free coffee at 3,000 points |
| Streak entitlement | One free coffee per completed block of 10 consecutive qualifying days |
| QR validity | 60 seconds; a new issued token invalidates the previous live token |
| Scan resolution validity | Two minutes for that staff member and store |
| Game lives | Three maximum; each used life returns after two hours |
| Active game session | One per customer; expires after 15 minutes |
| Pending redemption | Expires after 24 hours if unfulfilled |
| Weekly period | Monday 00:00 inclusive to next Monday 00:00 exclusive |
| Weekly settlement | Monday 00:15, after the maximum game-session grace period |
| Prize entitlement validity | Seven days after assignment |

Configuration changes apply to new operations. Store awarded points, reward
cost, prize benefit and expiry snapshots so later configuration edits cannot
rewrite historical transactions or an already granted entitlement.

## Visits and points

Staff first resolve a valid QR or use an authorised customer lookup; resolution
alone creates no visit and awards no points. On confirmation, verify active
customer, active assigned store/staff, unexpired resolution and business date.

Create the confirmed Visit, its positive PointTransaction, updated streak and
any new streak entitlement in one database transaction. Require a unique
`(customer_id, business_date)` qualifying visit and unique earned transaction
per visit. Concurrent scans or retries cannot award points twice. A repeat
request with the same idempotency key returns the original result; a different
confirmation for an already qualified date returns `409 VISIT_ALREADY_RECORDED`.

Record nonqualifying/rejected attempts as scan history, not additional earning
visits. The confirmation timestamp determines the qualification date: a QR
resolved before midnight and confirmed after midnight belongs to the new day.

The immutable point ledger is the source of truth. The balance is the sum of
signed integer transactions; never set it directly from a client or profile
edit. Points do not expire in v1. Game scores do not convert into loyalty points.

Admin corrections append `ADJUSTMENT` transactions with a nonempty reason and
actor. A negative adjustment cannot make the balance negative. Preserve the
original record; correct errors using explicit compensating entries, never
deleting or editing ledger history. Refuse unsupported corrections that would
require debt accounting, and record the attempted operation for review.

## Rewards and redemption

The active catalogue defines name, description, integer point cost and optional
availability times. Public catalogue responses expose only available rewards;
availability is checked again inside the redemption transaction. A reward is
unavailable at or after its exclusive expiry time.

For a points redemption, lock the customer's loyalty account, verify sufficient
balance, snapshot the reward cost, create a `PENDING` RewardRedemption and its
negative `REDEMPTION` transaction atomically. Concurrent redemptions cannot
overspend. Retries reuse the same idempotency key and return the same record.

For an earned prize, lock its CustomerPrize, verify ownership, benefit and expiry,
and reserve it through a redemption. No points debit is created for a zero-cost
prize. Two redemptions cannot reserve the same entitlement.

Only staff assigned to the redeeming store may fulfil a pending redemption.
Record store, staff and server fulfilment time; repeated fulfilment returns the
existing result. Customers cannot self-fulfil; staff cannot fulfil their own
customer redemption.

State transitions are `PENDING -> FULFILLED` or `PENDING -> CANCELLED/EXPIRED`.
Terminal states never change. A pending customer cancellation or system expiry
creates at most one compensating `REFUND` entry for points, or releases its prize
reservation if the prize has not expired. A fulfilled benefit is not refundable
in v1. An expired entitlement is never made spendable by releasing a reservation.

## Streaks

Count qualifying business dates, not elapsed 24-hour intervals:

1. First visit starts at one.
2. Visit on the immediately following date increments the streak.
3. A date gap resets it to one on the next visit.
4. Repeats on the same date do not increment or award additional points.
5. With no visit today, show the streak if the last visit was yesterday; show
   zero if it was earlier. Read-time expiry does not erase visit history.

Maintain longest streak and last qualifying date. At counts 10, 20, 30 and so on,
create a free-coffee CustomerPrize tied to that qualifying Visit. A unique
`(customer_id, trigger_visit_id, prize_id)` prevents duplicate awards. The
frontend may present progress within the current ten-day block while the server
retains the complete consecutive-day count. Missing a day resets current
progress but does not revoke an already earned unexpired prize.

## Game lives, sessions and scores

Lives are server-owned per customer, not shared browser/device state. Starting a
game locks GameLifeState, expires any stale active session, verifies no live
session exists and checks available lives. It creates a GameSession and consumes
one life in the same transaction. Simultaneous starts cannot use the same life.

Each consumed life becomes available exactly two hours after its start timestamp,
independently of other consumed lives. Do not implement a daily reset or a serial
six-hour recharge for all three. Example: starts at 09:00, 09:10 and 09:20 recharge
at 11:00, 11:10 and 11:20. Responses include `serverTime` and `nextRechargeAt`.

Navigating away, disconnecting or abandoning a run does not refund its life. An
active session expires after 15 minutes; the consumed life still follows its
two-hour recharge schedule. Starting another unexpired session returns `409
GAME_SESSION_ACTIVE`; having no lives returns `409 NO_GAME_LIVES`.

Only the owner may submit an integer score >= 0 for an active unexpired session.
The first accepted submission completes it and creates its unique GameScore.
An exact retry returns that score; a different second score returns `409
SCORE_ALREADY_SUBMITTED`. Reject malformed, expired, foreign or impossible runs.

Version the game rules. For initial engine v1, use a conservative plausibility
ceiling of `floor(350 * serverElapsedSeconds) + 100`, with elapsed time bounded
by the 15-minute session lifetime. This is a screening rule to calibrate against
real runs before prizes launch, not proof that a client played honestly. Record
rejection reasons and never let client-reported duration expand the ceiling.

## Leaderboards and prizes

Weekly ranking uses each eligible customer's highest accepted score for sessions
started during that weekly period. All-time ranking uses the highest accepted
score across all periods. Produce one entry per customer, not one per run.

Sort by score descending, first server acceptance time of that best score
ascending, then customer UUID ascending. Repeating an equal score does not move
its original tie-break timestamp. Include current-user rank independently of
the requested page. Customers with no accepted score have an unranked status,
not an invented score/rank. Public aliases never include email addresses.

The initial production leaderboard has Weekly and All-time views. The current
prototype's Friends view remains a frontend fixture until real friendship and
consent workflows are designed; do not fabricate backend friends from names.

The prototype has conflicting prize copy. The chosen production rule is:

| Weekly finishing rank | Benefit |
| --- | --- |
| 1 | One free coffee |
| 2 | One 50% discount on a coffee |
| 3 | One 25% discount on a coffee |
| 4 | One 10% discount on a coffee |

This follows its displayed leaderboard reward table; the game copy promising
free coffee to all top-three players must be corrected during integration.
Discounts/free coffees are shop benefits, never cash or money held by the app.

Settle the completed period at Monday 00:15 so valid sessions started just before
midnight can finish. Rank only accepted scores received before that cutoff. Store
the settlement result and assignment snapshots, uniquely keyed by period and
rank/customer as applicable. Job retries cannot issue duplicate prizes. Subsequent
catalogue edits cannot change existing assignments. Admin corrections append an
audited superseding assignment and cannot silently revoke fulfilled benefits.

Customers who also hold STAFF or ADMIN roles are excluded from prize-eligible
weekly rankings to avoid staff awarding themselves benefits. They may keep
personal gameplay records if they explicitly hold CUSTOMER too.

## Boundary examples to test

| Scenario | Expected result |
| --- | --- |
| Two staff confirm the same customer/date concurrently | One visit and 100 points; second request cannot duplicate either. |
| Balance 3,000; concurrent 3,000-point redemptions | Exactly one pending reward; balance never below zero. |
| Ten consecutive visits including a DST transition | Ten dates qualify and one streak prize is awarded. |
| Two starts with one remaining life | One session succeeds; the other is rejected. |
| Refresh, device change or forged browser clock | Server-owned balance, lives and periods are unchanged. |
| Same session submits two different scores | Only the first accepted score affects rankings. |
| Week settlement runs twice | Same assignments; no extra CustomerPrize records. |
| Prize expires while its redemption is being fulfilled | Transaction/lock decides one valid transition; no expired benefit is spent. |

## Current demo differences

The current frontend starts with 2,450 points and a seven-day streak, accepts a
demo sign-in, stores state locally, displays a fixed QR, and uses fake opponents.
It has no trusted visits, reward redemption, real friendship, weekly settlement
or backend permissions. These production decisions must not be inferred from
browser storage or described as already enforced.

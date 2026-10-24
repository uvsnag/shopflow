# ShopFlow Architecture

The full design lives in [ShopFlow_Project_Plan.md](../ShopFlow_Project_Plan.md).
This file is the short version for someone reading the code. Diagrams are in
[diagrams/](diagrams/) (Mermaid sources, rendered inline by GitHub).

- 7 Spring Boot services behind a Spring Cloud Gateway. One PostgreSQL
  database per service; no service touches another's database (ADR 0002).
- Service-to-service communication is Kafka events (choreography saga,
  ADR 0003). The one synchronous call in the main flow: order-service asks
  product-service for name/price when an order is placed (BR-05).
- Every event-producing service uses a **transactional outbox** (`outbox`
  table + `OutboxPublisher` in `libs/common`, ADR 0004). Every consumer is
  idempotent via the `processed_events` table (`IdempotencyGuard`).
- Order saga: `PENDING → STOCK_RESERVED → PAID → SHIPPED → DELIVERED`, with
  `CANCELLED` / `REFUNDED` exits. Each step has a compensating action
  (reserve↔release, charge↔refund, ADR 0005).
- JWT (RS256) is signed by auth-service and verified at the gateway against
  `/.well-known/jwks.json` (ADR 0006). The gateway strips any client-sent
  `X-User-Id` / `X-User-Role` and injects verified values; services re-check
  roles with `@PreAuthorize`.
- Data a service needs from another service arrives as events and is kept
  as a local copy: payment-service's `order_snapshots` (owner, amount, stock
  reserved?, ADR 0011) and notification-service's `user_contacts` (e-mail
  from `UserRegistered`).

## Reliability model

- **Atomic consume**: every Kafka listener is `@Transactional`. The
  `processed_events` insert, the business change, and any outbox event
  commit or roll back together. A handler crash therefore rolls back the
  dedup mark too, and the redelivery is processed — never skipped as a
  false duplicate.
- **Retries + DLT**: transient failures are retried with exponential backoff
  (0.5 s … 8 s, 6 attempts, ~15 s); poison messages (unparseable JSON,
  non-numeric ids) skip the retries. Either way a message that cannot be
  handled goes to `<topic>.DLT` (`KafkaErrorHandlingConfig` in libs/common)
  so it never blocks the partition.
- **Outbox publisher**: one replica per service publishes at a time
  (`pg_try_advisory_xact_lock`), so scaling a service out neither duplicates
  events nor lets two replicas interleave one order's events. Rows are
  marked sent only after the broker acks (at-least-once, absorbed by
  `processed_events`); the producer is idempotent with ≤ 5 in-flight
  requests, so a retried batch cannot overtake a later one. Sent outbox rows
  (7 d) and old `processed_events` marks (14 d, longer than Kafka's 7-day
  retention) are deleted by housekeeping jobs.
- **Row locks, not version conflicts**: every write path locks what it
  changes with `SELECT ... FOR UPDATE` before reading it — the order row in
  order-service, the payment row in payment-service, the reservation lines
  and then the stock rows (in product id order, so no deadlocks) in
  inventory-service. Saga events from two topics, the timeout jobs, the
  customer and the admin therefore run one after another on fresh data
  instead of failing on `@Version` and burning Kafka retries; a hot product
  no longer pushes orders to the DLT. `@Version` stays as a safety net and
  surfaces to clients as a 409, never a 500. Concurrent "buy the last item":
  the second order waits for the stock row lock, sees the first one's
  reservation and gets a clean `StockReservationFailed`.
- **Saga events are applied tolerantly**: events for one order come from
  different topics, so they can arrive after order-service already moved on
  (e.g. `StockReserved` after the customer cancelled). Such stale events are
  logged and ignored rather than thrown — retrying can never make them valid.
  A `PaymentSucceeded` that overtakes `StockReserved` pays the order (payment
  is only accepted once stock is reserved). Commands from people (ship,
  cancel) stay strict and answer 409.
- **Placing an order**: product prices are fetched before the transaction
  opens (no DB connection held during the HTTP call); the transaction then
  locks the cart, re-checks the Idempotency-Key and that the cart still
  matches what was priced. Two checkouts of one cart cannot both succeed.
- **Double pay**: settled by the DB — the payment row (unique
  `(user_id, idempotency_key)` AND unique `order_id`) is committed BEFORE
  the provider is charged.
- **Idempotency keys are insert-first, never check-then-insert**: a
  `SELECT` before the `INSERT` is only a fast path, because two requests can
  both miss it. The unique constraint is the arbiter: the loser of the race
  catches the violation *outside* its transaction, re-reads the winner's row
  and returns it, so a retried `POST /orders` or `POST /payments` gets the
  same response, not a 409. Keys are scoped per user, so one user can never
  replay or read another user's order/payment, and a key reused with a
  different order is rejected. `processed_events` uses one
  `INSERT ... ON CONFLICT DO NOTHING` (0 rows = duplicate) inside the
  listener's transaction: no race window and no aborted transaction.
- **Pay only what is reserved**: payment-service accepts a payment only
  when its order snapshot says the order belongs to the caller and stock is
  `RESERVED`; the amount is the snapshot's, never the request's.
- **Late payment**: if the bank's webhook lands after the 15-minute timeout
  (or the customer) already cancelled the order, order-service keeps the
  order `CANCELLED`, writes a history row and re-emits `OrderCancelled`;
  payment-service refunds, inventory has nothing left to release. Money
  never stays with us for a cancelled order.
- **Refunds are a queued command**: the decision to refund inserts a
  `PENDING` refund row in the caller's transaction (atomic with the Kafka
  dedup mark); `RefundRetryJob` (every 5 s) calls the bank outside any
  transaction and settles under the payment row lock, only if the refund is
  still `PENDING`. Failures back off (10 s, 20 s, … ≤ 10 min) up to 5
  attempts, then `FAILED` for a human. A partial unique index allows one
  `PENDING`/`SUCCEEDED` refund per payment, so a payment is never refunded
  twice. `PaymentRefunded` is only emitted on success.
- **Admin refund returns stock**: `PaymentRefunded` for a `PAID` order moves
  it to `REFUNDED` and emits `OrderCancelled`, which is how inventory puts
  the sold items back. Refunds of shipped/delivered orders are only noted in
  the order history (the goods are with the customer).
- **Refund returns stock**: a `CONFIRMED` reservation (paid, then refunded)
  goes back to `available`; a `RESERVED` one moves reserved → available.
- **Provider resilience** (NFR-11): 3 s timeout, 3 attempts with
  exponential backoff, circuit breaker (Resilience4j); the fake provider
  also retries its webhook with backoff, like a real bank. Retrying a
  money-moving call is safe because every charge / refund carries an
  `Idempotency-Key` (`payment-<id>`, `refund-<id>`) the provider de-duplicates
  on. If our charge call gives up but the bank did charge, the late success
  webhook flips the payment from `FAILED` to `SUCCEEDED`; order-service sees
  a cancelled order and the late-payment path refunds it.

## Observability

- **Traces** (NFR-18): Micrometer Tracing (Brave) in every service and the
  gateway, exported to Zipkin. Kafka producer/consumer observation is on,
  so one trace covers `POST /orders` → `OrderCreated` → inventory →
  `StockReserved` → order-service.
- **Logs** (NFR-19): with the `json-logs` profile (docker-compose and AWS)
  every service writes ECS JSON lines carrying `trace.id` / `span.id`.
  Without it (IDE runs) logs are plain text but still show
  `[app,traceId,spanId]`.
- **Metrics** (NFR-20): Prometheus scrapes `/actuator/prometheus`; the
  provisioned Grafana dashboard shows request rate, 5xx rate, mean + p95
  latency, services up, Kafka throughput, orders per status
  (`shopflow_orders{status}` from `OrderMetrics`), the provider circuit
  breaker state and DLT traffic.

## Known edge cases (accepted for the demo, documented on purpose)

- The inventory reservation TTL (30 min) is a safety net only and is kept
  longer than the 15-minute payment timeout, so a payment at minute 14:59
  still finds its stock. If order-service's timeout job were down for more
  than 15 extra minutes, a paid order could find its reservation released;
  inventory logs it and does not take the stock again (that could oversell).
- A payment can be accepted for an order that is cancelled a moment later
  (snapshot check, then cancel); the late-payment path refunds it.
- Access tokens cannot be revoked before their 15-minute expiry (ADR 0006).
- Order snapshot in payment-service is eventually consistent: paying in
  the ~1 s before `OrderCreated`/`StockReserved` arrive returns a 409 and
  the client retries (the checkout page waits for `STOCK_RESERVED`).
- The seeded admin user has no `UserRegistered` event, so its e-mails go to
  a placeholder address.

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
- **Retries + DLT**: a message that still fails after 3 attempts goes to
  `<topic>.DLT` (`KafkaErrorHandlingConfig` in libs/common) so it never
  blocks the partition.
- **Races**: concurrent "buy the last item" is settled by `stock.version`
  (optimistic locking) — the losing transaction rolls back completely and
  the redelivery sees fresh stock. Concurrent double-pay is settled by the
  DB: the payment row (unique `(user_id, idempotency_key)` AND unique
  `order_id`) is committed BEFORE the provider is charged. Version
  conflicts surface to clients as a 409, never a 500.
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
- **Refund retry**: a refund whose provider call fails stays `PENDING` and
  `RefundRetryJob` retries it every minute, up to 5 attempts, then marks it
  `FAILED` for a human. `PaymentRefunded` is only emitted on success, so
  stock is returned exactly once.
- **Refund returns stock**: a `CONFIRMED` reservation (paid, then refunded)
  goes back to `available`; a `RESERVED` one moves reserved → available.
- **Provider resilience** (NFR-11): 3 s timeout, 3 attempts with
  exponential backoff, circuit breaker (Resilience4j); the fake provider
  also retries its webhook with backoff, like a real bank.

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

- The order-side timeout job and the inventory-side reservation TTL are two
  independent 15-minute clocks; they can drift by one poll interval.
- Access tokens cannot be revoked before their 15-minute expiry (ADR 0006).
- Order snapshot in payment-service is eventually consistent: paying in
  the ~1 s before `OrderCreated`/`StockReserved` arrive returns a 409 and
  the client retries (the checkout page waits for `STOCK_RESERVED`).
- The seeded admin user has no `UserRegistered` event, so its e-mails go to
  a placeholder address.

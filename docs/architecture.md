# ShopFlow Architecture

The full design lives in [ShopFlow_Project_Plan.md](../ShopFlow_Project_Plan.md).
This file is the short version for someone reading the code.

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

## Reliability model (the part interviews ask about)

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
  DB: the payment row (unique `idempotency_key` AND unique `order_id`) is
  committed BEFORE the provider is charged.
- **Refund returns stock**: a `CONFIRMED` reservation (paid, then refunded)
  goes back to `available`; a `RESERVED` one moves reserved → available.

## Known edge cases (accepted for the demo, documented on purpose)

- If the provider webhook arrives *after* the 15-minute timeout cancelled
  the order, the late `PaymentSucceeded` cannot transition a `CANCELLED`
  order and lands in `payment-events.DLT`. The payment stays `SUCCEEDED`
  with no refund until an operator replays the DLT. A real system would
  void pending payments on `OrderCancelled`.
- The order-side timeout job and the inventory-side reservation TTL are two
  independent 15-minute clocks; they can drift by one poll interval.
- Access tokens cannot be revoked before their 15-minute expiry (ADR 0006).

Diagrams: see the mermaid blocks in the plan, export to `docs/diagrams/` later.

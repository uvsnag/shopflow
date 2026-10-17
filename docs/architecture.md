# ShopFlow Architecture

The full design lives in [ShopFlow_Project_Plan.md](../ShopFlow_Project_Plan.md).
This file is the short version for someone reading the code.

- 7 Spring Boot services behind a Spring Cloud Gateway. One PostgreSQL
  database per service; no service touches another's database.
- Service-to-service communication is Kafka events (choreography saga).
  The one synchronous call in the main flow: order-service asks
  product-service for name/price when an order is placed (BR-05).
- Every event-producing service uses a **transactional outbox** (`outbox`
  table + `OutboxPublisher` in `libs/common`). Every consumer is idempotent
  via the `processed_events` table (`IdempotencyGuard`).
- Order saga: `PENDING → STOCK_RESERVED → PAID → SHIPPED → DELIVERED`, with
  `CANCELLED` / `REFUNDED` exits. Each step has a compensating action
  (reserve↔release, charge↔refund).
- JWT (RS256) is signed by auth-service and verified at the gateway against
  `/.well-known/jwks.json`. The gateway forwards `X-User-Id` / `X-User-Role`;
  services re-check roles with `@PreAuthorize`.

Diagrams: see the mermaid blocks in the plan, export to `docs/diagrams/` later.

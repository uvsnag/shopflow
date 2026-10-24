# Requirement → implementation → test

Traceability for the IDs in [ShopFlow_Project_Plan.md](../ShopFlow_Project_Plan.md).
"Unit/API" tests run with `./mvnw verify` anywhere; "IT" tests need Docker (Testcontainers).

## Functional requirements

| ID | Where | Verified by |
| --- | --- | --- |
| FR-01 sign up | `auth-service` `AuthService.register`, `POST /auth/register` | `AuthServiceTest`, `AuthApiTest` |
| FR-02 login / 15-min access + 7-day refresh | `AuthService.login`, `JwtService` | `AuthServiceTest`, `JwtServiceTest` |
| FR-03 logout revokes refresh token | `AuthService.logout` | `AuthServiceTest`, `AuthApiTest` |
| FR-04 product list, search, category, paging | `product-service` `ProductService.list` | `ProductServiceTest`, `ProductSearchIT` |
| FR-05 product detail + stock left | `GET /products/{id}`, `GET /inventory/{id}`; frontend `products/[id]` | `ProductApiSecurityTest`, `InventoryApiSecurityTest` |
| FR-06 cart | `order-service` `CartService`, `/cart/items/{productId}` | `OrderApiSecurityTest` |
| FR-07 place order | `OrderService.placeOrder` | `OrderServiceTest`, `OrderFlowIT` |
| FR-08 pay (fake provider) | `payment-service` `PaymentService.createPayment`, webhook | `PaymentServiceTest`, `PaymentApiTest` |
| FR-09 own orders + status | `OrderController`, `OrderService.getOwn` | `OrderApiSecurityTest` |
| FR-10 cancel, refund if paid | `OrderService.customerCancel` → `OrderCancelled` → `PaymentService.refundByOrderId` | `OrderServiceTest`, `PaymentServiceTest` |
| FR-11 notifications (e-mail + in-app) | `notification-service` listeners + `NotificationService` | `NotificationEventListenerTest`, `NotificationServiceTest` |
| FR-12 admin products | `AdminProductController`, soft delete | `ProductApiSecurityTest`, `ProductServiceTest` |
| FR-13 add stock | `InventoryController.addStock` | `InventoryApiSecurityTest`, `InventoryServiceTest` |
| FR-14 admin orders by status | `AdminOrderController.list` | `OrderApiSecurityTest` |
| FR-15 ship / deliver | `AdminOrderController`, `OrderStatus` machine | `OrderStatusTest`, `OrderServiceTest` |
| FR-16 admin refund | `PaymentController.refund` | `PaymentApiTest`, `PaymentServiceTest` |

## Business rules

| ID | Where | Verified by |
| --- | --- | --- |
| BR-01 only with enough stock | `InventoryService.reserveForOrder` (stock rows locked FOR UPDATE, check pass, then mutate) | `InventoryServiceTest`, `ConcurrentReservationIT` |
| BR-02 reserve / release | `InventoryService`, `ExpiredReservationJob` | `InventoryServiceTest`, `StockTest` |
| BR-03 15-min auto cancel | `OrderTimeoutJob` | `OrderTimeoutJobTest` |
| BR-04 never charged twice | unique `payments.order_id` + `idempotency_key`, row committed before charge | `PaymentServiceTest` |
| BR-05 price frozen at order time | `OrderItem` copies name/price; amount from `order_snapshots` (ADR 0011) | `OrderTest`, `PaymentServiceTest` |
| BR-06 customers see own orders only | `OrderService.getOwn`, `customerCancel`; payment snapshot owner check | `OrderApiSecurityTest`, `PaymentServiceTest` |

## Non-functional requirements

| ID | Where | Verified by |
| --- | --- | --- |
| NFR-01 BCrypt | `SecurityConfig.passwordEncoder` | `AuthServiceTest` |
| NFR-02 RS256 + JWKS | `JwtService`, `JwksController`, gateway `JwtAuthFilter` | `JwtServiceTest`, `JwtAuthFilterTest` |
| NFR-03 gateway headers + `@PreAuthorize` | `JwtAuthFilter` (strips spoofed headers), `GatewayHeaderAuthFilter` | `JwtAuthFilterTest`, `GatewayHeaderAuthFilterTest`, `*ApiSecurityTest` |
| NFR-04 hashed + rotated refresh tokens | `AuthService.refresh` | `AuthServiceTest` |
| NFR-05 login rate limit 5/min/IP | gateway `RequestRateLimiter` + Redis | `ApiGatewayRoutesTest` (route has the filter) |
| NFR-06 HMAC webhook | `WebhookController` | `PaymentApiTest` |
| NFR-07 secrets from env / Secrets Manager | `JWT_PRIVATE_KEY_PEM`, `WEBHOOK_SECRET`, Terraform `secrets.tf` | `JwtServiceTest` (PEM path) |
| NFR-08 transactional outbox | `OutboxService`, `OutboxPublisher` | `OutboxServiceTest`, `OutboxPublisherTest`, `OrderFlowIT` |
| NFR-09 idempotent consumers | `IdempotencyGuard` + `@Transactional` listeners | `IdempotencyGuardTest`, `OrderSagaListenerTest`, `OrderFlowIT` |
| NFR-10 idempotent APIs | `Idempotency-Key` on orders and payments | `OrderServiceTest`, `PaymentServiceTest` |
| NFR-11 timeout / retry / circuit breaker | `FakeProviderClient` + Resilience4j config | `PaymentServiceTest` (failure path) |
| NFR-12 timeout + expired reservations job | `OrderTimeoutJob`, `ExpiredReservationJob`, `RefundRetryJob` | `OrderTimeoutJobTest`, `PaymentServiceTest` |
| NFR-13 notification-service down ≠ outage | Kafka consumer group; mail failure swallowed | `NotificationServiceTest` |
| NFR-14 product list cached in Redis | `@Cacheable` / `@CacheEvict` in `ProductService` | manual (see Grafana latency panel) |
| NFR-15 place-order < 500 ms | sync work is one product lookup; rest is async | k6 threshold in `load-test/order-flow.js` |
| NFR-16 indexes | `V1__init.sql` of each service | `*IT` run the migrations |
| NFR-17 k6 100 users, no oversell / double charge | `load-test/order-flow.js` | run against a stack |
| NFR-18 trace id across services and Kafka | Micrometer Tracing + Zipkin, Kafka observation in `shopflow-defaults.yml` | manual (Zipkin UI) |
| NFR-19 JSON logs with trace id | `json-logs` profile → ECS structured logging | manual (`docker compose logs`) |
| NFR-20 Grafana dashboard | `infra/grafana/dashboards/shopflow.json`, `OrderMetrics` | manual (Grafana) |

## Must-have test cases (plan: Testing)

| Case | Test |
| --- | --- |
| Two customers buy the last item, only one succeeds | `ConcurrentReservationIT` |
| Same Kafka event delivered twice, processed once | `OrderSagaListenerTest`, `OrderFlowIT` |
| "Pay" twice with the same Idempotency-Key, one payment | `PaymentServiceTest.sameKeyReplaysTheSamePaymentWithoutChargingAgain`, k6 |
| Provider times out → retries → order cancelled, stock released | `PaymentServiceTest.providerOutageMarksPaymentFailedAndEmitsEvent` + `InventoryEventListenerTest` (release on cancel); full chain needs the running stack |
| notification-service stopped during an order, email arrives after restart | manual: `docker compose stop notification-service`, order, `start` — Kafka keeps the events |
| Unpaid order cancelled after 15 minutes | `OrderTimeoutJobTest` (set `shopflow.payment-timeout=1m` to watch it live) |

## Not done / next

- Nothing here has run against a real AWS account yet (`terraform apply`, first deploy).
- Spring Cloud Contract tests (plan: optional) are not written.
- Playwright/REST Assured end-to-end through the gateway is not written; the k6 script doubles as a smoke test of the full flow.

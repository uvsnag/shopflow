# ShopFlow

A small online shop built as 7 Spring Boot microservices to demonstrate
**Microservices, System Design, Spring Security + JWT, Kafka, Docker, CI/CD, and AWS**.

> Full requirements and design: [ShopFlow_Project_Plan.md](ShopFlow_Project_Plan.md)

## Architecture (short version)

- Browser → `api-gateway` (JWT check, rate limit) → services over REST.
- Services talk to each other through **Kafka events** (choreography saga) with a
  **transactional outbox** and **idempotent consumers**.
- One PostgreSQL database per service. No cross-service DB access.
- Stock is reserved before payment; every step has a compensating action.

| Service | Port | Database |
| --- | --- | --- |
| api-gateway | 8080 | — |
| auth-service | 8081 | auth_db |
| product-service | 8082 | product_db |
| inventory-service | 8083 | inventory_db |
| order-service | 8084 | order_db |
| payment-service | 8085 | payment_db |
| notification-service | 8086 | notification_db |
| fake-payment-provider | 9090 | — |

## Run locally

```bash
docker compose up --build
```

Then:

- Frontend: http://localhost:3000
- API gateway: http://localhost:8080/api/v1
- MailHog (fake email inbox): http://localhost:8025
- Zipkin: http://localhost:9411
- Grafana: http://localhost:3001

## Build without Docker

```bash
mvn clean verify          # all services + tests
cd frontend && npm install && npm run dev
```

## Repository layout

See the plan document; in short: `libs/common` (event envelope, outbox,
security helpers), `services/*` (one Spring Boot app each),
`tools/fake-payment-provider`, `frontend` (Next.js), `infra`, `load-test`,
`docs/adr`.

## Status

Rough scaffold generated from the project plan. TODOs are marked in code.
# shopflow

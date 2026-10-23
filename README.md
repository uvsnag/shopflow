# ShopFlow

A small online shop built as 7 Spring Boot microservices to demonstrate
**Microservices, System Design, Spring Security + JWT, Kafka, Docker, CI/CD, and AWS**.

> Full requirements and design: [ShopFlow_Project_Plan.md](ShopFlow_Project_Plan.md) ·
> Short architecture tour: [docs/architecture.md](docs/architecture.md) ·
> Decisions: [docs/adr](docs/adr) · Requirement → code → test map: [docs/status.md](docs/status.md)

## Architecture (short version)

- Browser → `api-gateway` (JWT check, rate limit) → services over REST.
- Services talk to each other through **Kafka events** (choreography saga) with a
  **transactional outbox** and **idempotent consumers**.
- One PostgreSQL database per service. No cross-service DB access; data a
  service needs from another arrives as events and is kept as a local copy.
- Stock is reserved before payment; every step has a compensating action
  (release, refund with retry). The amount charged comes from the order, never
  from the client.
- Traces (Zipkin), JSON logs with trace ids, Prometheus metrics and a
  provisioned Grafana dashboard.

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

- Frontend: http://localhost:3000 (register, browse, cart, checkout, orders, notifications; admin pages for orders/products/stock)
- API gateway: http://localhost:8080/api/v1
- MailHog (fake email inbox): http://localhost:8025
- Zipkin (traces): http://localhost:9411
- Prometheus: http://localhost:9091 · Grafana: http://localhost:3001 (admin/admin, "ShopFlow Overview" is provisioned)

Seeded admin login: `admin@shopflow.local` / `admin1234` (dev only).

The browser calls the gateway from another origin, so the gateway answers CORS for
`CORS_ALLOWED_ORIGINS` (comma-separated; default `http://localhost:3000`, the
production compose file sets `http://<DEMO_HOST>:3000`). If you serve the frontend
from a different host or port, add it there.

## Build and test without Docker

```bash
./mvnw verify                 # compile + unit/API tests for every module (JDK 21; Maven downloads itself)
cd frontend && npm ci && npm run build
```

Integration tests (`*IT`, Testcontainers with real PostgreSQL + Kafka) run in
the same command when Docker is available and skip themselves otherwise. They
cover the plan's must-have cases: two customers buying the last item, the same
Kafka event delivered twice, Flyway + outbox + saga reply end to end.
Coverage reports: `<module>/target/site/jacoco/index.html`.

Load test (NFR-17): `k6 run load-test/order-flow.js` against a running stack.

## CI/CD and AWS

- `.github/workflows/ci.yml` — on every PR: path-filtered backend / frontend /
  provider jobs; on `main`: builds all 9 images tagged with the commit SHA and
  pushes them to Amazon ECR using GitHub OIDC (no stored AWS keys). Image
  pushing is gated on the repository variable `AWS_DEPLOY_ENABLED`.
- `.github/workflows/deploy.yml` — after a green CI on `main`: deploys to the
  EC2 host over AWS SSM (`docker compose pull && up -d` with
  `docker-compose.prod.yml`), then smoke-tests the gateway and every
  service's `/actuator/health`.
- `infra/aws` — Terraform for plan option A (EC2 + docker compose, RDS
  PostgreSQL, ECR, OIDC role, Secrets Manager, budget alert). Runbook in
  [infra/README.md](infra/README.md). Moving to ECS Fargate is the planned
  next step (ADR 0010).

## Repository layout

`libs/common` (event envelope, outbox, idempotency, error format, security
helper, shared observability defaults), `services/*` (one Spring Boot app
each), `tools/fake-payment-provider` (Node), `frontend` (Next.js), `infra`
(Grafana, Prometheus, Postgres init, Terraform), `load-test` (k6), `docs`
(architecture, ADRs, diagrams, status).

## Status

Phases 1–5 of the roadmap are implemented and compile on Java 21 / Spring
Boot 3.5; unit and API tests pass (`./mvnw verify`), the frontend builds.
Phase 6 (CI/CD + AWS) and phase 7 (observability, k6, ADRs, diagrams) are
implemented as code and configuration but have not yet been exercised
against a real AWS account or a Docker host from this machine — the first
`docker compose up` and the first `terraform apply` are the next steps.
Known, deliberate edge cases are listed in
[docs/architecture.md](docs/architecture.md).

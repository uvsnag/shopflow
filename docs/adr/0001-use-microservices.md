# ADR 0001: Split into 7 services by business area

Status: Accepted · Date: 2026-10-16

## Context

ShopFlow is a CV project meant to prove microservices and system design
skills. A monolith would be simpler but would not prove the skill.

## Options

1. Monolith with modular packages.
2. 2–3 coarse services (shop, payments).
3. 7 services, one per business area (auth, product, inventory, order,
   payment, notification, plus a gateway).

## Decision

Option 3: one service per business area, each with its own database.

## Consequences

- (+) Independent deploy and scale; each service's data model stays small.
- (+) Forces the real distributed-system problems (saga, outbox, idempotency)
  that the project exists to demonstrate.
- (−) More moving parts: 7 builds, 7 databases, Kafka, more YAML.
- (−) Local dev needs Docker Compose; nothing runs "by itself".

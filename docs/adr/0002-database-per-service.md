# ADR 0002: One database per service

Status: Accepted · Date: 2026-10-17

## Context

Services need data. Sharing one database is easy but couples every service
to every table; schema changes become cross-team events and the
"microservices" are really a distributed monolith.

## Options

1. One shared PostgreSQL schema for everything.
2. One PostgreSQL *instance*, but a separate database per service, and no
   service may touch another's database.
3. A separate PostgreSQL instance per service.

## Decision

Option 2: one Postgres container locally (one RDS instance in the cloud),
with `auth_db`, `product_db`, `inventory_db`, `order_db`, `payment_db`,
`notification_db`. Access to another service's data only via its API or
events. When data is needed at a point in time, it is copied (order_items
stores product name and price at order time, BR-05).

## Consequences

- (+) Loose coupling: each service owns and migrates its own schema (Flyway).
- (+) Cheap: one database server, many logical databases.
- (−) No cross-service joins or foreign keys; consistency is eventual and
  must be designed (saga, outbox — ADR 0003/0004).
- (−) Some data is duplicated by design and can go stale (product name on an
  old order is a feature, not a bug).

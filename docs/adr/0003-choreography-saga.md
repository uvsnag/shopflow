# ADR 0003: Choreography saga with Kafka (no central orchestrator)

Status: Accepted · Date: 2026-10-17

## Context

Placing an order spans three services (order, inventory, payment) without a
shared transaction. Something must coordinate: reserve stock, take payment,
and undo the right steps when one fails.

## Options

1. Two-phase commit / distributed transactions.
2. Orchestration saga: one coordinator (order-service or a dedicated
   orchestrator) calls each step and tracks state.
3. Choreography saga: each service reacts to events and publishes its own
   (OrderCreated → StockReserved → PaymentSucceeded → …).

## Decision

Option 3. The flow is only ~4 steps, and each service already owns a clear
piece: order-service owns order state, inventory owns reserve/release,
payment owns charge/refund. Every forward step has a compensating step
(reserve↔release, charge↔refund).

## Consequences

- (+) No single coordinator to build, deploy, or crash; services stay
  independent.
- (+) Adding a listener (notification-service) requires no change anywhere
  else.
- (−) The flow is implicit: nobody can read one file and see the whole saga.
  Mitigated by the sequence diagram in the plan and the status history table.
- (−) With many more steps this becomes hard to follow; if the flow grows,
  revisit with an orchestrator (new ADR).

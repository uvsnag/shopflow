# ADR 0011: payment-service learns the order amount from events, not from the client

Status: Accepted · Date: 2026-10-17

## Context

`POST /payments` must charge the right amount for the right order, for the
right customer, and only once stock is reserved. The first scaffold trusted
the `amount` field sent by the browser, which let anyone pay 1 VND for any
order — a hole no reviewer of a "System Design" project would forgive.

payment-service owns payments; order-service owns orders and the price
frozen at order time (BR-05). Something has to bridge the two.

## Options

1. **Trust the client** (status quo). Zero coupling, zero security.
2. **Synchronous call** from payment-service to order-service
   (`GET /orders/{id}`) on every pay request. Simple, but the pay path now
   depends on order-service being up, adds a network hop inside a user
   request, and is the kind of chatty REST coupling the plan avoids
   ("direct calls only when you need an answer right now").
3. **Local snapshot from events.** payment-service already consumes
   `order-events`. It keeps an `order_snapshots` table (order id, user id,
   amount, status) built from `OrderCreated`, `StockReserved` and
   `OrderCancelled`, and reads it when a payment is requested. The plan's
   data rule ("when one service needs data from another, it saves a copy
   of only what it needs") already blesses this pattern.

## Decision

Option 3. `POST /payments` takes only `{orderId}`. payment-service accepts
the payment only when its snapshot says the order belongs to the caller,
stock is `RESERVED`, and the order is not cancelled; the amount charged is
the snapshot's. If the snapshot is missing or not yet reserved the client
gets a 409 and retries (the checkout page already waits for
`STOCK_RESERVED` before showing "Pay").

Because `order-events` and `inventory-events` are different topics,
`StockReserved` may in theory overtake `OrderCreated`; the snapshot is
therefore upserted from either event and is "payable" only when both
have arrived.

## Consequences

- (+) The amount can never be forged; BR-04/BR-05 hold without a
  synchronous dependency on order-service.
- (+) Paying is impossible before stock is reserved or after cancellation,
  which removes a whole class of saga races.
- (−) A second copy of order data that is eventually consistent; a payment
  attempt in the ~1 s before the events land gets a 409 instead of 202.
- (−) One more table and two more event handlers in payment-service.

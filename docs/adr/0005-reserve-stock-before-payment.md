# ADR 0005: Reserve stock before payment

Status: Accepted · Date: 2026-10-17

## Context

Payment and stock cannot be checked in one transaction (different services).
Someone can pay for an item that just sold out, or an item can be held by
someone who never pays.

## Options

1. Charge first, then try to allocate stock; refund if allocation fails.
2. Reserve stock first (15-minute hold), then charge; release the hold if
   payment fails or times out.
3. Oversell and apologize (no reservation at all).

## Decision

Option 2. `stock.available` moves to `stock.reserved` when the order is
placed (BR-02); payment success confirms the reservation, failure/timeout
releases it. A 15-minute TTL (BR-03) plus two safety-net jobs (order timeout
job, expired-reservation job) guarantee holds never leak.

## Consequences

- (+) A paid order always has its items; no "sorry, refund" mails.
- (+) Overselling is impossible: the stock rows are locked
  (`SELECT ... FOR UPDATE`, in product id order) while the reservation is
  checked and written; `stock.version` remains as a safety net.
- (−) Stock can be held for up to 15 minutes by someone who never pays.
- (−) Two timers (order side, inventory side). The inventory TTL (30 min) is
  kept longer than the payment timeout (15 min) so it only catches lost
  events and never takes stock from an order paid at the last second
  (documented in architecture.md).

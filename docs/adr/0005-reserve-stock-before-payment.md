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
- (+) Overselling is impossible: reservation is guarded by optimistic
  locking (`stock.version`).
- (−) Stock can be held for up to 15 minutes by someone who never pays.
- (−) Two timers (order side, inventory side) must agree; drift between them
  is the main known edge case (documented in architecture.md).

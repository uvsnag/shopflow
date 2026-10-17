# ADR 0007: Cart lives in order-service

Status: Accepted · Date: 2026-10-16

## Context

The cart's only purpose is to build an order. A separate cart-service would
be one more deployable with its own database.

## Options

1. Separate cart-service.
2. Cart inside order-service.
3. Cart only in the frontend (localStorage).

## Decision

Option 2: the cart tables (`carts`, `cart_items`) live in order_db and the
cart API is part of order-service.

## Consequences

- (+) Placing an order reads the cart in the same transaction — no network
  call, no eventual consistency between cart and order.
- (+) One fewer service to run and deploy.
- (−) Less separation; if carts ever need independent scale, this must be
  split out (write a new ADR then).

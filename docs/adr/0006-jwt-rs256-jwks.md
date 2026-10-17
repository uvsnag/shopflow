# ADR 0006: JWT with RS256 + JWKS, verified at the gateway

Status: Accepted · Date: 2026-10-17

## Context

Every request must be authenticated, but calling auth-service on each
request would make it a single point of failure and a bottleneck.

## Options

1. Opaque tokens: every service calls auth-service to introspect.
2. JWT with a shared HMAC secret (HS256) copied to every service.
3. JWT signed RS256 by auth-service; the gateway verifies against the
   public key from `/.well-known/jwks.json` and forwards identity headers.

## Decision

Option 3. Only auth-service holds the private key (from env / Secrets
Manager, NFR-07). The gateway verifies the signature, strips any
client-sent `X-User-Id`/`X-User-Role`, and injects verified values.
Services trust those headers (they are unreachable from outside the Docker
network) but still re-check roles with `@PreAuthorize` (defense in depth,
NFR-03).

## Consequences

- (+) No shared secret sprawl; key rotation = publish a new key in JWKS.
- (+) Auth-service is not on the hot path of every request.
- (−) An access token cannot be revoked instantly; mitigated by the 15-min
  TTL and refresh-token rotation (NFR-04).
- (−) The header-trust model is only safe while services are not directly
  reachable — a network/infra invariant that must hold in AWS too.

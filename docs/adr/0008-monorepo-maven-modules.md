# ADR 0008: Monorepo with Maven modules

Status: Accepted · Date: 2026-10-17

## Context

7 services + a shared lib + frontend + infra could live in one repo or ten.
One developer maintains all of it, and reviewers (interviewers) need to see
everything quickly.

## Options

1. One repo per service ("pure" microservices hygiene).
2. Monorepo with a Maven multi-module build.

## Decision

Option 2: one repo, parent `pom.xml`, modules under `services/` and
`libs/`. CI uses path filters so only changed services build. `libs/common`
is deliberately kept small (event envelope, outbox, idempotency, error
format, header auth) — if it grows, services couple again through it.

## Consequences

- (+) One clone, one build, one place for docs and compose; atomic changes
  across services.
- (+) A single reviewer sees the whole system.
- (−) Shared parent pom couples dependency versions (acceptable: one team).
- (−) Repo-per-service deployment independence must be simulated with path
  filters instead of being structural.

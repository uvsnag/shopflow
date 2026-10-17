# ADR 0004: Transactional outbox for events

Status: Accepted · Date: 2026-10-17

## Context

A service that saves data and then publishes to Kafka can fail in between:
data saved but event lost (saga stalls), or event sent but transaction
rolled back (ghost event). Either breaks the saga.

## Options

1. Publish to Kafka inside the business code and accept the gap.
2. Transactional outbox: write the event into an `outbox` table in the same
   DB transaction as the data; a scheduled publisher moves rows to Kafka.
3. Change data capture (Debezium) tailing the WAL.

## Decision

Option 2, implemented once in `libs/common` (`OutboxService` +
`OutboxPublisher`). Consumers deduplicate via `processed_events`
(`IdempotencyGuard`), and the consumer's handling runs in the SAME
transaction as the dedup mark, so a failed handler is retried instead of
being skipped as a duplicate.

## Consequences

- (+) Data and events commit or roll back together; at-least-once delivery
  end to end, made effectively exactly-once by idempotent consumers.
- (+) No extra infrastructure (Debezium/Connect) to run.
- (−) An extra table and a polling job per service; up to ~1s publish delay.
- (−) At-least-once means consumers MUST stay idempotent; that discipline is
  enforced by code review, not tooling.

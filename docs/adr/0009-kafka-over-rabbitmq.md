# ADR 0009: Kafka over RabbitMQ

Status: Accepted · Date: 2026-10-17

## Context

The saga needs an event backbone. The real requirements are modest (low
volume, a few topics), so both brokers would work technically.

## Options

1. RabbitMQ: lighter, simpler to run, classic work queues.
2. Apache Kafka: partitioned log, ordering per key, replay, consumer groups.

## Decision

Option 2. Deciding factors, in order: per-key ordering (all events of one
order stay in sequence on one partition — the saga depends on this), replay
for debugging and reprocessing, and Kafka being far more common in the job
posts this project targets.

## Consequences

- (+) Ordering by `orderId` key gives the saga its sequencing for free.
- (+) Events are retained and replayable; a dead consumer catches up
  (NFR-13).
- (+) KRaft mode means no ZooKeeper container anymore.
- (−) Heavier than RabbitMQ to run and configure locally and in AWS (MSK is
  not free-tier; Kafka on EC2 is the budget path).
- (−) Dead-letter handling must be built explicitly (`.DLT` topics via
  `DefaultErrorHandler`); Rabbit has DLX built in.

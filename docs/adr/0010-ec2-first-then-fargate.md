# ADR 0010: Start on EC2 + docker compose, move to ECS Fargate later

Status: Accepted · Date: 2026-10-17

## Context

The demo must run on AWS (project requirement) on a hobby budget. Options range
from "compose on a VM" to a full ECS/MSK/ElastiCache setup that costs real
money every month.

## Options

1. One EC2 instance running `docker compose up`, RDS PostgreSQL free tier.
2. ECS Fargate (one task per service), RDS, ElastiCache, MSK or Kafka on
   EC2, ALB.
3. Kubernetes (EKS).

## Decision

Option 1 now, option 2 as a planned second step (this ADR gets superseded
then — keep both, the migration is documented as an ADR). EKS is overkill
for 7 small services and triple the cost. Non-negotiables from day one:
images in ECR tagged by commit SHA, deploy via GitHub Actions with OIDC (no
stored AWS keys), an AWS Budget alert (~10 USD), and secrets in
AWS Secrets Manager.

## Consequences

- (+) Online fast, nearly free, same compose file as local dev.
- (+) The later EC2→Fargate migration demonstrates real cloud skills and
  produces a before/after story.
- (−) One EC2 box is a single point of failure and proves "Docker on a VM",
  not managed-container skills — acceptable for phase 6, fixed in phase 7+.
- (−) Compose on EC2 means Kafka/Redis run unmanaged; snapshots/backups are
  manual.

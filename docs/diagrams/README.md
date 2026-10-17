# Diagrams

Mermaid sources extracted from the plan so they can be rendered or embedded
on their own (GitHub renders ```mermaid blocks; VS Code has a Mermaid
preview; `npx @mermaid-js/mermaid-cli -i x.mmd -o x.png` makes an image).

- `system-architecture.mmd` — browser → gateway → services, Kafka, Redis, fake bank
- `place-order-sequence.mmd` — the choreography saga: order → stock → payment
- `order-state-machine.mmd` — `PENDING → STOCK_RESERVED → PAID → SHIPPED → DELIVERED` and exits

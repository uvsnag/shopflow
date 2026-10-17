CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL UNIQUE,  -- BR-04: one payment per order
    user_id         BIGINT       NOT NULL,         -- FR-11: payment events carry the user
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider_ref    VARCHAR(100),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,  -- NFR-10
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refunds (
    id         BIGSERIAL PRIMARY KEY,
    payment_id BIGINT      NOT NULL REFERENCES payments (id),
    amount     BIGINT      NOT NULL CHECK (amount > 0),
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    sent_at      TIMESTAMPTZ
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

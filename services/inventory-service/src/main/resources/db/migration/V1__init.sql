CREATE TABLE stock (
    product_id BIGINT PRIMARY KEY,
    available  INT    NOT NULL DEFAULT 0 CHECK (available >= 0),
    reserved   INT    NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version    BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reservations (
    id         BIGSERIAL PRIMARY KEY,
    order_id   VARCHAR(64) NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL CHECK (quantity > 0),
    status     VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reservations_order ON reservations (order_id);
CREATE INDEX idx_reservations_expiry ON reservations (status, expires_at);

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

-- Seed stock for the seeded products (ids 1-4 in product_db)
INSERT INTO stock (product_id, available) VALUES (1, 50), (2, 20), (3, 100), (4, 200);

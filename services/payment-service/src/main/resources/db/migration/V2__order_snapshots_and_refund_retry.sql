-- ADR 0011: payment-service keeps its own copy of the order facts it needs
-- (owner, amount, stock reserved?) built from Kafka events, so the amount to
-- charge never comes from the client (BR-04, BR-05).
CREATE TABLE order_snapshots (
    order_id   BIGINT PRIMARY KEY,
    user_id    BIGINT,
    amount     BIGINT CHECK (amount >= 0),
    status     VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Refunds are retried by a job until they succeed or give up (compensation
-- must eventually happen even when the bank is flaky).
ALTER TABLE refunds ADD COLUMN reason     VARCHAR(255);
ALTER TABLE refunds ADD COLUMN attempts   INT NOT NULL DEFAULT 0;
ALTER TABLE refunds ADD COLUMN last_error VARCHAR(500);

CREATE INDEX idx_refunds_pending ON refunds (created_at) WHERE status = 'PENDING';

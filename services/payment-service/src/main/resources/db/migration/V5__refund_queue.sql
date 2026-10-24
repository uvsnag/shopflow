-- Refunds become a queued command: the bank is called by a job outside any
-- transaction, retried with backoff at next_attempt_at.
ALTER TABLE refunds ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

DROP INDEX idx_refunds_pending;
CREATE INDEX idx_refunds_due ON refunds (next_attempt_at) WHERE status = 'PENDING';

-- Never refund one payment twice: at most one refund that is in flight or
-- done per payment. Two concurrent refund requests (customer cancel and admin
-- refund at the same moment) cannot both insert; a FAILED refund does not
-- block a new attempt.
CREATE UNIQUE INDEX uk_refunds_one_open_per_payment ON refunds (payment_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');

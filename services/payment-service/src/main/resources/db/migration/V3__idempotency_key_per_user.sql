-- NFR-10: scope the Idempotency-Key to the user (see order-service V2 for the
-- reasoning). order_id stays globally unique (BR-04: one payment per order).
ALTER TABLE payments DROP CONSTRAINT payments_idempotency_key_key;
ALTER TABLE payments ADD CONSTRAINT uk_payments_user_idempotency_key UNIQUE (user_id, idempotency_key);

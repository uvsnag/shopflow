-- NFR-10: an Idempotency-Key belongs to the client that generated it. A global
-- unique key let any user replay (and read) another user's order by guessing
-- or reusing the key; scoping the uniqueness to (user_id, idempotency_key)
-- closes that and keeps the DB as the single arbiter of concurrent duplicates.
ALTER TABLE orders DROP CONSTRAINT orders_idempotency_key_key;
ALTER TABLE orders ADD CONSTRAINT uk_orders_user_idempotency_key UNIQUE (user_id, idempotency_key);

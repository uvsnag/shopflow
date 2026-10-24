-- NFR-08 / NFR-09 housekeeping. The outbox publisher polls "oldest unsent
-- first" every second: a partial index keeps that a tiny index scan however
-- many sent rows pile up. The two other indexes serve the cleanup jobs that
-- delete sent outbox rows and old processed_events marks.
CREATE INDEX idx_outbox_unsent ON outbox (created_at) WHERE sent_at IS NULL;
CREATE INDEX idx_outbox_sent_at ON outbox (sent_at) WHERE sent_at IS NOT NULL;
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

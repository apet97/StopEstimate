-- DB-06: retention purge job on guard_events scans by created_at; index makes the
-- nightly `delete ... where created_at < :cutoff` sargable without a full scan.
CREATE INDEX IF NOT EXISTS idx_guard_events_created_at ON guard_events (created_at);

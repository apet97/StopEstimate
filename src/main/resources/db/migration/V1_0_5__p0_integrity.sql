-- P0 integrity migration: uniques for upserts, optimistic-locking columns, webhook dedup,
-- ShedLock schema, and partial index tightening.

-- Unique constraint enables ON CONFLICT upserts and prevents duplicate cutoff rows per timer.
ALTER TABLE cutoff_jobs
    ADD CONSTRAINT uk_cutoff_jobs_workspace_time_entry UNIQUE (workspace_id, time_entry_id);

-- Drop the redundant non-unique index now that the unique constraint covers lookups.
DROP INDEX IF EXISTS idx_cutoff_jobs_time_entry;

-- Optimistic-locking version columns (JPA @Version).
ALTER TABLE installations            ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE project_lock_snapshots   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE cutoff_jobs              ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Partial index: the status/enabled combination the scheduler actually scans.
DROP INDEX IF EXISTS idx_installations_status;
CREATE INDEX idx_installations_active
    ON installations (workspace_id)
    WHERE status = 'ACTIVE' AND enabled = TRUE;

-- Webhook dedup: Clockify retries the same delivery; short-circuit by (event_id, signature_hash).
CREATE TABLE webhook_events (
    event_id        VARCHAR(128) NOT NULL,
    signature_hash  VARCHAR(128) NOT NULL,
    workspace_id    VARCHAR(64)  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, signature_hash)
);
CREATE INDEX idx_webhook_events_received_at ON webhook_events(received_at);

-- ShedLock JDBC provider schema — prevents multi-instance scheduler doubles.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

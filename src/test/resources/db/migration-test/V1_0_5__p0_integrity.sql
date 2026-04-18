-- H2 Postgres-compat variant of V1_0_5. Tests continue running until Phase 7 swaps in Testcontainers.

ALTER TABLE cutoff_jobs
    ADD CONSTRAINT uk_cutoff_jobs_workspace_time_entry UNIQUE (workspace_id, time_entry_id);

DROP INDEX IF EXISTS idx_cutoff_jobs_time_entry;

ALTER TABLE installations            ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE project_lock_snapshots   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE cutoff_jobs              ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- H2's PG-mode does not support partial indexes; the full index is adequate for tests.
DROP INDEX IF EXISTS idx_installations_status;
CREATE INDEX idx_installations_active ON installations (workspace_id, status, enabled);

CREATE TABLE webhook_events (
    event_id        VARCHAR(128) NOT NULL,
    signature_hash  VARCHAR(128) NOT NULL,
    workspace_id    VARCHAR(64)  NOT NULL,
    received_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, signature_hash)
);
CREATE INDEX idx_webhook_events_received_at ON webhook_events(received_at);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

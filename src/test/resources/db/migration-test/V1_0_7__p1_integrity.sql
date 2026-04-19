-- H2 PostgreSQL-compat variant of V1_0_7.

ALTER TABLE shedlock ALTER COLUMN lock_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE shedlock ALTER COLUMN locked_at  TIMESTAMP WITH TIME ZONE;

ALTER TABLE cutoff_jobs
    ADD CONSTRAINT fk_cutoff_jobs_installation
    FOREIGN KEY (workspace_id) REFERENCES installations(workspace_id) ON DELETE CASCADE;

ALTER TABLE project_lock_snapshots
    ADD CONSTRAINT fk_project_lock_snapshots_installation
    FOREIGN KEY (workspace_id) REFERENCES installations(workspace_id) ON DELETE CASCADE;

ALTER TABLE guard_events
    ADD CONSTRAINT fk_guard_events_installation
    FOREIGN KEY (workspace_id) REFERENCES installations(workspace_id) ON DELETE CASCADE;

ALTER TABLE installations
    ADD CONSTRAINT chk_installations_status
    CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE installations
    ADD CONSTRAINT chk_installations_cadence
    CHECK (default_reset_cadence IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'));

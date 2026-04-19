-- P1 integrity follow-up to V1_0_5.
--
-- DB-03: shedlock lock_until/locked_at were declared as TIMESTAMP (no time zone).
-- ShedLock compares lock_until against UTC, so a JVM/DB timezone mismatch or a DST
-- transition could corrupt the comparison and break multi-instance scheduling.
ALTER TABLE shedlock ALTER COLUMN lock_until TYPE TIMESTAMPTZ USING lock_until AT TIME ZONE 'UTC';
ALTER TABLE shedlock ALTER COLUMN locked_at  TYPE TIMESTAMPTZ USING locked_at  AT TIME ZONE 'UTC';

-- DB-04: orphaned rows in cutoff_jobs, guard_events, and project_lock_snapshots after an
-- installation is deleted clutter the DB and can be picked up by the scheduler. Tie them to
-- installations with ON DELETE CASCADE so uninstall cleanly wipes dependent data.
ALTER TABLE cutoff_jobs
    ADD CONSTRAINT fk_cutoff_jobs_installation
    FOREIGN KEY (workspace_id) REFERENCES installations(workspace_id) ON DELETE CASCADE;

ALTER TABLE project_lock_snapshots
    ADD CONSTRAINT fk_project_lock_snapshots_installation
    FOREIGN KEY (workspace_id) REFERENCES installations(workspace_id) ON DELETE CASCADE;

ALTER TABLE guard_events
    ADD CONSTRAINT fk_guard_events_installation
    FOREIGN KEY (workspace_id) REFERENCES installations(workspace_id) ON DELETE CASCADE;

-- DB-05: reject corrupt status / cadence strings at the DB level. InstallationStore already
-- falls back to ACTIVE/NONE on unknown values, but a CHECK constraint means the fallback is
-- only reached for truly corrupt rows rather than every future enum expansion.
ALTER TABLE installations
    ADD CONSTRAINT chk_installations_status
    CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE installations
    ADD CONSTRAINT chk_installations_cadence
    CHECK (default_reset_cadence IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'));

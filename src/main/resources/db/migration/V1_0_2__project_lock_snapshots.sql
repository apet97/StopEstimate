CREATE TABLE project_lock_snapshots (
    workspace_id              VARCHAR(64) NOT NULL,
    project_id                VARCHAR(64) NOT NULL,
    original_is_public        BOOLEAN     NOT NULL,
    original_memberships_json TEXT,
    original_user_groups_json TEXT,
    lock_reason               VARCHAR(64) NOT NULL,
    locked_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workspace_id, project_id)
);
CREATE INDEX idx_project_lock_snapshots_workspace ON project_lock_snapshots(workspace_id);

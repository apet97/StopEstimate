CREATE TABLE project_lock_snapshots (
    workspace_id              VARCHAR(64) NOT NULL,
    project_id                VARCHAR(64) NOT NULL,
    original_is_public        BOOLEAN     NOT NULL,
    original_memberships_json CLOB,
    original_user_groups_json CLOB,
    lock_reason               VARCHAR(64) NOT NULL,
    locked_at                 TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, project_id)
);
CREATE INDEX idx_project_lock_snapshots_workspace ON project_lock_snapshots(workspace_id);

CREATE TABLE guard_events (
    id                  BIGSERIAL PRIMARY KEY,
    workspace_id        VARCHAR(64) NOT NULL,
    project_id          VARCHAR(64),
    event_type          VARCHAR(64) NOT NULL,
    guard_reason        VARCHAR(64),
    source              VARCHAR(128),
    payload_fingerprint VARCHAR(128),
    outcome             VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_guard_events_workspace_created ON guard_events(workspace_id, created_at DESC);
CREATE INDEX idx_guard_events_project_created ON guard_events(workspace_id, project_id, created_at DESC);

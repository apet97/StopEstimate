CREATE TABLE webhook_registrations (
    id                BIGSERIAL PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL REFERENCES installations(workspace_id) ON DELETE CASCADE,
    route_path        VARCHAR(256) NOT NULL,
    event_type        VARCHAR(64),
    webhook_token_enc TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_webhook_registrations_workspace_path UNIQUE (workspace_id, route_path)
);
CREATE INDEX idx_webhook_registrations_workspace ON webhook_registrations(workspace_id);

CREATE TABLE installations (
    workspace_id            VARCHAR(64)  PRIMARY KEY,
    addon_id                VARCHAR(64)  NOT NULL,
    addon_user_id           VARCHAR(64),
    owner_user_id           VARCHAR(64),
    installation_token_enc  TEXT         NOT NULL,
    backend_url             VARCHAR(512) NOT NULL,
    reports_url             VARCHAR(512) NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    default_reset_cadence   VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    installed_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_installations_status ON installations(status);

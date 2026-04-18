CREATE TABLE cutoff_jobs (
    job_id        VARCHAR(64) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    project_id    VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64) NOT NULL,
    time_entry_id VARCHAR(64) NOT NULL,
    cutoff_at     TIMESTAMP   NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cutoff_jobs_workspace ON cutoff_jobs(workspace_id);
CREATE INDEX idx_cutoff_jobs_workspace_project ON cutoff_jobs(workspace_id, project_id);
CREATE INDEX idx_cutoff_jobs_cutoff_at ON cutoff_jobs(cutoff_at);
CREATE INDEX idx_cutoff_jobs_time_entry ON cutoff_jobs(workspace_id, time_entry_id);

-- idx_webhook_registrations_workspace is covered by the composite UNIQUE constraint
-- uq_webhook_registrations_workspace_path; the workspace_id column is its prefix and
-- Postgres uses the unique index to satisfy workspace_id-only lookups. The duplicate
-- standalone index only pays the write cost.
DROP INDEX IF EXISTS idx_webhook_registrations_workspace;

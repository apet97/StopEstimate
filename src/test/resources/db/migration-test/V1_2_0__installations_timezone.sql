-- Persist the workspace IANA timezone (e.g. "Europe/Belgrade") so reset-window reports
-- align with workspace-local midnight. Nullable; null -> UTC fallback in ProjectUsageService.
-- Populated lazily on the first post-install reconcile via InstallReconcileRetrier.
ALTER TABLE installations ADD COLUMN timezone VARCHAR(64);

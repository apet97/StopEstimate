package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record PendingCutoffJob(
		String jobId,
		String workspaceId,
		String projectId,
		String userId,
		String timeEntryId,
		Instant cutoffAt,
		Instant createdAt) implements Serializable {

	public static PendingCutoffJob create(String workspaceId, String projectId, String userId, String timeEntryId, Instant cutoffAt) {
		return new PendingCutoffJob(UUID.randomUUID().toString(), workspaceId, projectId, userId, timeEntryId, cutoffAt, Instant.now());
	}
}

package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.time.Instant;

public record RunningTimeEntry(
		String workspaceId,
		String projectId,
		String userId,
		String timeEntryId,
		Instant start,
		boolean billable) implements Serializable {
}

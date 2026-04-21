package com.devodox.stopatestimate.model;

import java.time.Instant;
import java.util.List;

public record ProjectLockSnapshot(
		String workspaceId,
		String projectId,
		boolean originalPublic,
		List<ProjectMemberAccess> originalMembers,
		List<String> originalUserGroupIds,
		String reason,
		Instant lockedAt) {
}

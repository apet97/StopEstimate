package com.devodox.stopatestimate.model;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

public record ProjectState(
		String workspaceId,
		String projectId,
		String name,
		boolean isPublic,
		List<ProjectMemberAccess> directMembers,
		List<String> userGroupIds,
		RateInfo defaultHourlyRate,
		RateInfo defaultCostRate,
		ProjectCaps caps) implements Serializable {

	public Optional<RateInfo> costRateForUser(String userId) {
		return directMembers.stream()
				.filter(member -> userId.equals(member.userId()))
				.map(ProjectMemberAccess::costRate)
				.filter(RateInfo::present)
				.findFirst()
				.or(() -> Optional.ofNullable(defaultCostRate).filter(RateInfo::present));
	}
}

package com.devodox.stopatestimate.model;

public record ProjectMemberAccess(
		String userId,
		RateInfo hourlyRate,
		RateInfo costRate,
		String membershipType,
		String membershipStatus) {
}

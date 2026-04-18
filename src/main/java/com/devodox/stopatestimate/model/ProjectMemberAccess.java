package com.devodox.stopatestimate.model;

import java.io.Serializable;

public record ProjectMemberAccess(
		String userId,
		RateInfo hourlyRate,
		RateInfo costRate,
		String membershipType,
		String membershipStatus) implements Serializable {
}

package com.devodox.stopatestimate.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ProjectGuardSummary(
        String workspaceId,
        String projectId,
        String projectName,
        boolean activeCaps,
        boolean locked,
        String status,
        String reason,
        long trackedTimeMs,
        long timeLimitMs,
        BigDecimal budgetUsage,
        BigDecimal budgetLimit,
        int runningEntryCount,
        Instant cutoffAt,
        Instant nextResetAt,
        Instant lockedAt) {
}

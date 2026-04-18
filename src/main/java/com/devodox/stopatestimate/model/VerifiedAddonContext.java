package com.devodox.stopatestimate.model;

import java.util.Map;

public record VerifiedAddonContext(
        String workspaceId,
        String addonId,
        String userId,
        String backendUrl,
        String reportsUrl,
        String language,
        String theme,
        Map<String, Object> claims
) {
}

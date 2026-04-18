package com.devodox.stopatestimate.model;

public record StatusChangedPayload(
        String addonId,
        String workspaceId,
        String status
) {
}

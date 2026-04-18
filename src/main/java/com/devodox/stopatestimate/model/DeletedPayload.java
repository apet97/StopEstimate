package com.devodox.stopatestimate.model;

public record DeletedPayload(
        String addonId,
        String workspaceId,
        String asUser
) {
}
